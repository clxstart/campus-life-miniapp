package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 已验证支付结果的唯一入账口（finding-02/04/11）。
 *
 * 回调、对账、模拟支付都走这里，保证同一套规则：
 *  1. 只接受「已验签 / 已向渠道查询确认」的结果，未验证数据永远不进 payment_record；
 *  2. 金额、商户订单号必须与本地订单一致，否则登记异常单、不改订单状态；
 *  3. 流水按 transaction_id 唯一入库，重复回调幂等；
 *  4. 订单用条件 UPDATE 原子改为已付；与关单并发时输家按回读状态进入补偿路径。
 *
 * 调用方不应持有事务：本方法自己开一个短事务，渠道网络请求必须在事务外完成。
 */
@Service
@RequiredArgsConstructor
public class PaymentLedgerService {
    public enum Outcome { APPLIED, DUPLICATE, IGNORED, EXCEPTION }

    /** 已付及之后的状态：再次收到同一交易号的成功结果视为重复 */
    static final Set<String> PAID_OR_LATER = Set.of("paid", "accepted", "delivering", "picked", "completed", "refunding", "refunded");

    private final UserOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentExceptionMapper exceptionMapper;
    private final PaymentAlerts alerts;
    /** V3.1 券：支付成功核销，并记平台补贴账。 */
    private final CouponService couponService;
    private final com.campuslife.mapper.EarningsLedgerMapper earningsLedger;

    @Transactional
    public Outcome applyVerifiedPayment(String outTradeNo, String transactionId, String tradeState,
                                        Integer amountCents, String rawBody, String source) {
        if (blank(outTradeNo) || blank(transactionId)) {
            alerts.warn("ledger", "missing_ids", "source={} 结果缺少订单号或交易号", source);
            return Outcome.IGNORED;
        }
        // 非成功状态（NOTPAY/CLOSED/USERPAYING…）不入账，交给对账流程决定是否关单
        if (!"SUCCESS".equalsIgnoreCase(tradeState)) return Outcome.IGNORED;
        int amount = amountCents == null ? -1 : amountCents;

        UserOrder order = orderMapper.selectOne(new LambdaQueryWrapper<UserOrder>()
            .eq(UserOrder::getOutTradeNo, outTradeNo).last("LIMIT 1"));
        if (order == null) {
            exceptionMapper.insertIgnore(null, outTradeNo, transactionId, Math.max(amount, 0), "UNKNOWN_ORDER", "manual", "渠道成功但本地无此订单，需人工核实");
            alerts.warn("ledger", "unknown_order", "outTradeNo={} txn={}", outTradeNo, transactionId);
            return Outcome.EXCEPTION;
        }
        if (order.getPayAmountCents() == null || amount != order.getPayAmountCents()) {
            exceptionMapper.insertIgnore(order.getId(), outTradeNo, transactionId, Math.max(amount, 0), "AMOUNT_MISMATCH", "manual",
                "渠道金额 " + amount + " 分与订单应付 " + order.getPayAmountCents() + " 分不符");
            alerts.warn("ledger", "amount_mismatch", "order={} txn={}", order.getId(), transactionId);
            return Outcome.EXCEPTION;
        }

        upsertVerifiedRecord(order.getId(), outTradeNo, transactionId, tradeState, amount, rawBody);

        if (orderMapper.markPaidIfUnpaid(order.getId(), transactionId, amount) == 1) {
            // 券核销与平台补贴入账，与「订单改已付」同一事务：
            // 要么都成立，要么都不成立，不会出现「已用券但账没记」。
            applyCoupon(order);
            alerts.count("ledger", "applied_" + source);
            return Outcome.APPLIED;
        }

        // 条件更新没命中：回读当前状态决定是重复回调还是补偿
        UserOrder current = orderMapper.selectById(order.getId());
        String status = current == null ? "" : String.valueOf(current.getStatus());
        if (PAID_OR_LATER.contains(status)) {
            if (transactionId.equals(current.getTransactionId())) return Outcome.DUPLICATE;
            exceptionMapper.insertIgnore(order.getId(), outTradeNo, transactionId, amount, "DUPLICATE_PAYMENT", "manual",
                "订单已由交易 " + current.getTransactionId() + " 支付，又收到另一笔成功交易");
            alerts.warn("ledger", "duplicate_payment", "order={} txn={}", order.getId(), transactionId);
            return Outcome.EXCEPTION;
        }
        if ("closed".equals(status) || "cancelled".equals(status)) {
            // 渠道已扣款、本地已关单：登记待退款异常单，由 PaymentCompensationService 自动退款或转人工
            exceptionMapper.insertIgnore(order.getId(), outTradeNo, transactionId, amount, "PAID_AFTER_CLOSE", "open",
                "订单已" + ("closed".equals(status) ? "超时关闭" : "取消") + "，渠道仍扣款成功");
            alerts.warn("ledger", "paid_after_close", "order={} txn={}", order.getId(), transactionId);
            return Outcome.EXCEPTION;
        }
        exceptionMapper.insertIgnore(order.getId(), outTradeNo, transactionId, amount, "UNEXPECTED_STATE", "manual",
            "订单状态 " + status + " 下收到成功支付");
        alerts.warn("ledger", "unexpected_state", "order={} status={}", order.getId(), status);
        return Outcome.EXCEPTION;
    }

    /**
     * 券核销 + 平台补贴账。
     *
     * 商家收入不扣券：券是平台出的钱，所以单独记一条 platform_subsidy；
     * 退款时由 MerchantRefundStatusService 把这条账置 void，券作废不退。
     */
    private void applyCoupon(UserOrder order) {
        if (order.getCouponId() == null) return;
        couponService.useByOrder(order.getId());
        BigDecimal discount = order.getCouponDiscount();
        int cents = discount == null ? 0
            : discount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
        if (cents > 0) {
            earningsLedger.record("delivery:" + order.getId() + ":subsidy", "delivery", order.getId(), null,
                "platform_subsidy", cents, "recorded", "平台券补贴（商家不承担）", null);
        }
    }

    /**
     * 写入已验证流水；同一交易号已存在时幂等。
     * 历史版本会把未验签通知写成 verified=0 的行，若攻击者预先占用了真实交易号，
     * 这里把该行升级为已验证内容，而不是因「已存在」误判为重复而漏记账。
     */
    private void upsertVerifiedRecord(Long orderId, String outTradeNo, String transactionId, String tradeState,
                                      int amount, String rawBody) {
        PaymentRecord existing = findByTransaction(transactionId);
        if (existing == null) {
            PaymentRecord record = new PaymentRecord();
            record.setOrderId(orderId); record.setOutTradeNo(outTradeNo); record.setTransactionId(transactionId);
            record.setAmountCents(amount); record.setTradeState(tradeState); record.setRawBody(rawBody == null ? "" : rawBody);
            record.setVerified(1); record.setCreatedAt(LocalDateTime.now());
            try { paymentRecordMapper.insert(record); return; }
            catch (DuplicateKeyException race) { existing = findByTransaction(transactionId); }
        }
        if (existing != null && (existing.getVerified() == null || existing.getVerified() != 1)) {
            existing.setOrderId(orderId); existing.setOutTradeNo(outTradeNo); existing.setAmountCents(amount);
            existing.setTradeState(tradeState); existing.setRawBody(rawBody == null ? "" : rawBody); existing.setVerified(1);
            paymentRecordMapper.updateById(existing);
        }
    }

    private PaymentRecord findByTransaction(String transactionId) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
            .eq(PaymentRecord::getTransactionId, transactionId).last("LIMIT 1"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
