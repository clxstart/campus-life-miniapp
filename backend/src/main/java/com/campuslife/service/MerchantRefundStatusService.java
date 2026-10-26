package com.campuslife.service;

import com.campuslife.mapper.MerchantMapper;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MerchantRefundStatusService {
    private final MerchantMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String applyProviderResult(String outRefundNo, Refund refund) {
        if (refund == null || !Objects.equals(outRefundNo, refund.getOutRefundNo())) {
            throw new IllegalArgumentException("微信退款响应与本地退款单不匹配");
        }
        Map<String,Object> context = requireRefund(outRefundNo);
        validateTransaction(context, refund.getTransactionId(), refund.getOutTradeNo());
        validateAmount(context, refund.getAmount() == null ? null : refund.getAmount().getRefund(),
            refund.getAmount() == null ? null : refund.getAmount().getTotal());
        return apply(context, refund.getStatus(), refund.getRefundId(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyNotification(RefundNotification notification) {
        if (notification == null || blank(notification.getOutRefundNo())) {
            throw new IllegalArgumentException("微信退款通知缺少退款单号");
        }
        Map<String,Object> context = requireRefund(notification.getOutRefundNo());
        validateTransaction(context, notification.getTransactionId(), notification.getOutTradeNo());
        validateAmount(context, notification.getAmount() == null ? null : notification.getAmount().getRefund(),
            notification.getAmount() == null ? null : notification.getAmount().getTotal());
        apply(context, notification.getRefundStatus(), notification.getRefundId(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String markFailed(String outRefundNo, String reason) {
        Map<String,Object> context = requireRefund(outRefundNo);
        return apply(context, Status.ABNORMAL, null, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String keepPending(String outRefundNo, String reason) {
        Map<String,Object> context = requireRefund(outRefundNo);
        return apply(context, Status.PROCESSING, null, reason);
    }

    private String apply(Map<String,Object> context, Status providerStatus, String refundId, String reason) {
        if (providerStatus == null) throw new IllegalArgumentException("微信退款状态缺失");
        String status = switch (providerStatus) {
            case SUCCESS -> "success";
            case CLOSED, ABNORMAL -> "failed";
            case PROCESSING -> "pending";
        };
        String previous = text(context.get("refundStatus"));
        if ("success".equals(previous) || ("failed".equals(previous) && "pending".equals(status))) return previous;
        Long orderId = number(context.get("orderId"));
        Long userId = number(context.get("userId"));
        String outRefundNo = String.valueOf(context.get("outRefundNo"));
        String message = reason == null ? "" : reason;
        mapper.updateRefund(outRefundNo, refundId, status, message);

        String payStatus = switch (status) {
            case "success" -> "refunded";
            case "pending" -> "refunding";
            default -> "paid";
        };
        mapper.updatePayStatus(orderId, payStatus);

        // 同步订单主状态：退款成功 → refunded，退款中 → refunding。
        // 之前这里只改 pay_status 不改 status，导致订单列表里仍显示「已完成/配送中」，
        // 与退款单状态不一致。商家拒单与用户退款现在共用这一处逻辑。
        if ("success".equals(status)) {
            mapper.updateOrderStatusForRefund(orderId, "refunded");
        } else if ("pending".equals(status)) {
            mapper.updateOrderStatusForRefund(orderId, "refunding");
        }

        String title;
        String content;
        switch (status) {
            case "success" -> {
                title = "退款已完成";
                content = "订单 #" + orderId + " 的退款已到账。";
            }
            case "pending" -> {
                title = "退款处理中";
                content = "订单 #" + orderId + " 已提交退款，到账进度以支付渠道为准。";
            }
            default -> {
                title = "退款待人工处理";
                content = "订单 #" + orderId + " 已取消，但退款未完成。平台将核实后处理。";
            }
        }
        mapper.notifyRefund(userId, title, content, "refund-" + orderId + "-" + status);
        return status;
    }

    private Map<String,Object> requireRefund(String outRefundNo) {
        Map<String,Object> context = mapper.refundContextByNumber(outRefundNo);
        if (context == null) throw new IllegalArgumentException("找不到对应的本地退款记录");
        return context;
    }

    private void validateTransaction(Map<String,Object> context, String transactionId, String outTradeNo) {
        String expectedTransactionId = text(context.get("transactionId"));
        String expectedOutTradeNo = text(context.get("outTradeNo"));
        if (!blank(expectedTransactionId) && !Objects.equals(expectedTransactionId, transactionId)) {
            throw new IllegalArgumentException("微信退款通知的支付单号不匹配");
        }
        if (!blank(expectedOutTradeNo) && !Objects.equals(expectedOutTradeNo, outTradeNo)) {
            throw new IllegalArgumentException("微信退款通知的商户订单号不匹配");
        }
    }

    private void validateAmount(Map<String,Object> context, Long refundCents, Long totalCents) {
        Object expected = context.get("amountCents");
        if (!(expected instanceof Number n) || refundCents == null || totalCents == null ||
            n.longValue() != refundCents || n.longValue() != totalCents) {
            throw new IllegalArgumentException("微信退款金额与本地记录不匹配");
        }
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
