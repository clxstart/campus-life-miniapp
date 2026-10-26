package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.mapper.MerchantMapper;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class MerchantRefundService {
    private final MerchantMapper mapper;
    private final ObjectProvider<RefundService> refundServiceProvider;
    private final WxPayProperties properties;
    private final MerchantRefundStatusService statusService;

    public String submitAfterCommit(Long orderId) {
        Map<String,Object> context = mapper.refundContext(orderId);
        if (context == null) return "failed";
        String outRefundNo = text(context.get("outRefundNo"));
        if (!"pending".equals(context.get("refundStatus"))) return text(context.get("refundStatus"));

        RefundService refundService = refundServiceProvider.getIfAvailable();
        if (!properties.isEnabled() || refundService == null) {
            return statusService.markFailed(outRefundNo, "微信退款未配置，需平台人工处理");
        }

        long amountCents = number(context.get("amountCents"));
        if (amountCents < 1 || !"paid".equals(context.get("payStatus"))) {
            return statusService.markFailed(outRefundNo, "原支付状态或退款金额无效，需平台人工处理");
        }
        String transactionId = text(context.get("transactionId"));
        String outTradeNo = text(context.get("outTradeNo"));
        if (transactionId.isBlank() && outTradeNo.isBlank()) {
            return statusService.markFailed(outRefundNo, "缺少原支付单号，需平台人工处理");
        }

        CreateRequest request = new CreateRequest();
        if (!transactionId.isBlank()) request.setTransactionId(transactionId);
        else request.setOutTradeNo(outTradeNo);
        request.setOutRefundNo(outRefundNo);
        request.setReason("商家无法接单");
        request.setNotifyUrl(properties.getRefundNotifyUrl());
        AmountReq amount = new AmountReq();
        amount.setRefund(amountCents);
        amount.setTotal(amountCents);
        amount.setCurrency("CNY");
        request.setAmount(amount);

        try {
            return statusService.applyProviderResult(outRefundNo, refundService.create(request));
        } catch (RuntimeException createError) {
            try {
                QueryByOutRefundNoRequest query = new QueryByOutRefundNoRequest();
                query.setOutRefundNo(outRefundNo);
                Refund refund = refundService.queryByOutRefundNo(query);
                return statusService.applyProviderResult(outRefundNo, refund);
            } catch (RuntimeException queryError) {
                return statusService.keepPending(outRefundNo, "微信退款结果暂时无法确认，请平台核实退款状态");
            }
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
