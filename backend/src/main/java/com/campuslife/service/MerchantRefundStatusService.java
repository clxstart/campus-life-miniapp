package com.campuslife.service;

import com.campuslife.entity.RefundRecord;
import com.campuslife.mapper.EarningsLedgerMapper;
import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * 退款账本的唯一状态机（用户退款、商家拒单退款、关单后到账补偿共用）。
 *
 * 单调性：refund_record.success 是终态；pay_status.refunded 是终态；
 * order.status.refunded 是终态。迟到的 PROCESSING/失败结果只能命中条件 UPDATE 的 0 行，
 * 不会把已经解决的退款退回处理中或失败（finding-followup：租约重叠防护）。
 */
@Service
@RequiredArgsConstructor
public class MerchantRefundStatusService {
    private final MerchantMapper mapper;
    private final PaymentExceptionMapper exceptionMapper;
    private final RefundRecordMapper refundRecordMapper;
    /** V3.1 券：退款后券作废、平台补贴置 void（券是平台真实支出，不退券）。 */
    private final CouponService couponService;
    private final EarningsLedgerMapper earningsLedger;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String applyProviderResult(String outRefundNo, Refund refund) {
        if (refund == null || !Objects.equals(outRefundNo, refund.getOutRefundNo())) {
            throw new IllegalArgumentException("微信退款响应与本地退款单不匹配");
        }
        Map<String,Object> context = requireRefund(outRefundNo);
        validateTransaction(context, refund.getTransactionId(), refund.getOutTradeNo());
        validateAmount(context, refund.getAmount() == null ? null : refund.getAmount().getRefund(),
            refund.getAmount() == null ? null : refund.getAmount().getTotal());
        return apply(context, refund.getStatus(), refund.getRefundId(), null, false);
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
        // 退款通知可能先于补偿轮询到达：按退款单号判断它是否属于「关单后到账」补偿，
        // 若是，同一事务里把 payment_exception 一起收敛，并允许 closed/cancelled → refunded
        Long exceptionId = exceptionMapper.findIdByOutRefundNo(notification.getOutRefundNo());
        String status = apply(context, notification.getRefundStatus(), notification.getRefundId(), null, exceptionId != null);
        if (exceptionId != null) markExceptionStatus(exceptionId, status, notification.getRefundId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String markFailed(String outRefundNo, String reason) {
        Map<String,Object> context = requireRefund(outRefundNo);
        return apply(context, Status.ABNORMAL, null, reason, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String keepPending(String outRefundNo, String reason) {
        Map<String,Object> context = requireRefund(outRefundNo);
        return apply(context, Status.PROCESSING, null, reason, false);
    }

    /**
     * 领取一条「关单后到账」异常单并建立标准退款账本（finding-followup）。
     *
     * 原子性：claimForRefund 只允许 status=open 时命中；退款单号 PAC+异常单 id 确定性生成。
     * 同一事务里写 refund_record，保证「异常单已提交退款」和「账本存在」不会只成功一半；
     * 若订单已有退款记录（每订单唯一），不制造第二条互相矛盾的记录，直接转人工核对。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimCompensation(long exceptionId, Long orderId, String outRefundNo, int amountCents, String reason) {
        if (exceptionMapper.claimForRefund(exceptionId, outRefundNo) != 1) return false;
        if (orderId == null) return true;
        RefundRecord existing = refundRecordMapper.findByOrderId(orderId);
        if (existing != null) {
            exceptionMapper.markManual(exceptionId,
                "订单已有退款记录 " + existing.getOutRefundNo() + "，需人工核对", 0);
            return false;
        }
        int inserted = refundRecordMapper.insertRefundIfAbsent(orderId, outRefundNo, amountCents, reason);
        if (inserted != 1) {
            RefundRecord other = refundRecordMapper.findByOrderId(orderId);
            exceptionMapper.markManual(exceptionId,
                "退款账本写入冲突（" + (other == null ? "未知" : other.getOutRefundNo()) + "），需人工核对", 0);
            return false;
        }
        return true;
    }

    /**
     * 补偿退款结果落账：refund_record / user_order / payment_exception 在同一个短事务里一致收敛。
     * 只有渠道返回 SUCCESS 才会写 resolved；PROCESSING 保持 refund_submitted；失败转 manual。
     * 旧节点迟到的结果不会把 resolved 改回去（payment_exception 的 UPDATE 带来源状态条件）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String applyCompensationResult(long exceptionId, String outRefundNo, Refund refund) {
        if (refund == null || !Objects.equals(outRefundNo, refund.getOutRefundNo())) {
            throw new IllegalArgumentException("微信退款响应与本地退款单不匹配");
        }
        Map<String,Object> context = requireRefund(outRefundNo);
        validateTransaction(context, refund.getTransactionId(), refund.getOutTradeNo());
        validateAmount(context, refund.getAmount() == null ? null : refund.getAmount().getRefund(),
            refund.getAmount() == null ? null : refund.getAmount().getTotal());
        Status providerStatus = refund.getStatus();
        String status = apply(context, providerStatus, refund.getRefundId(), null, true);
        markExceptionStatus(exceptionId, status, refund.getRefundId());
        return status;
    }

    private String apply(Map<String,Object> context, Status providerStatus, String refundId, String reason, boolean compensation) {
        if (providerStatus == null) throw new IllegalArgumentException("微信退款状态缺失");
        String status = switch (providerStatus) {
            case SUCCESS -> "success";
            case CLOSED, ABNORMAL -> "failed";
            case PROCESSING -> "pending";
        };
        String previous = text(context.get("refundStatus"));
        // 终态不可回退：已成功不再接受任何迟到结果；已失败也不被迟到的 PROCESSING 改回 pending
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
        // 补偿场景的订单在到账前已 closed/cancelled，成功时使用更宽的收敛语句，保证四张账可核对。
        if ("success".equals(status)) {
            // 券作废且平台补贴冲销：不冲销会留下「平台为一张已退款订单补贴了 N 元」
            couponService.voidByOrder(orderId);
            earningsLedger.voidByBizNo("delivery:" + orderId + ":subsidy");
            if (compensation) mapper.updateOrderStatusForCompensation(orderId);
            else mapper.updateOrderStatusForRefund(orderId, "refunded");
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

    /** 把统一账本状态同步到异常单；SQL 带来源状态条件，resolved 不会被迟到结果回退。 */
    private void markExceptionStatus(Long exceptionId, String status, String refundId) {
        switch (status) {
            case "success" -> exceptionMapper.markResolved(exceptionId,
                "已自动退款 " + (refundId == null ? "" : refundId), 0);
            case "pending" -> exceptionMapper.markRefundSubmitted(exceptionId, "退款处理中", 0);
            default -> exceptionMapper.markManual(exceptionId, "渠道退款失败，需人工处理", 0);
        }
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
