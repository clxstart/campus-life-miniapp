package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.RiderMapper;
import com.campuslife.mapper.UserOrderMapper;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户/管理端退款编排（finding-followup）。
 *
 * 事务边界：
 *  - {@link RefundInitiationService#initiate} 在独立短事务里完成「锁订单行 + 状态迁移 + 写退款账本」；
 *  - 渠道调用（微信 create/query）在事务提交之后，绝不持有数据库事务或行锁；
 *  - 渠道结果落账由 {@link MerchantRefundStatusService} 的 REQUIRES_NEW 短事务完成，重复通知幂等。
 *
 * 幂等语义：
 *  - 退款单号确定性生成（RF + orderId），并且 refund_record 每订单唯一；
 *  - 并发/超时重试永远复用既有退款单号，不生成第二个可提交渠道的退款单；
 *  - 只有账本状态仍是 pending 才会再次提交渠道；success/failed 直接返回既有状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRefundService {

    public record Outcome(Long orderId, String outRefundNo, String refundStatus, boolean idempotent) { }

    private final RefundInitiationService initiation;
    private final MerchantRefundService merchantRefundService;
    private final MerchantRefundStatusService statusService;
    private final UserOrderMapper orderMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final RiderMapper riderMapper;
    private final WxPayProperties properties;
    private final Environment environment;
    private final PaymentAlerts alerts;

    /** 是否允许在「微信支付未开启」时用模拟退款跑通流程；生产 profile 下永远不允许。 */
    @Value("${campus.pay.simulate:false}")
    private boolean simulate;

    public Outcome refund(Long orderId, Long userId, String reason) {
        return doRefund(orderId, userId, false, reason);
    }

    /** 管理端退款：权限已由 AdminInterceptor + AdminOrderService.assertAccessible 完成，这里只跳过「下单人」校验。 */
    public Outcome refundAsAdmin(Long orderId, String reason) {
        return doRefund(orderId, null, true, reason);
    }

    private Outcome doRefund(Long orderId, Long userId, boolean admin, String reason) {
        // 模拟退款的准入必须在任何数据库写入之前判定，避免「已把订单改成 refunding 才发现不允许模拟」
        if (!properties.isEnabled()) requireSimulationAllowed(orderId);

        RefundInitiationService.Initiated initiated = initiation.initiate(orderId, userId, admin, reason);
        String status = initiated.refundStatus();

        // 只有 pending 才允许（重新）提交渠道；已是 success/failed 时返回既有状态，绝不换号重提
        if ("pending".equals(status)) {
            try {
                status = properties.isEnabled()
                    ? merchantRefundService.submitAfterCommit(orderId)
                    : simulateSuccess(orderId, initiated.outRefundNo());
            } catch (RuntimeException e) {
                alerts.warn("refund", "submit_uncertain", "订单 {} 退款提交结果不确定：{}", orderId, e.getClass().getSimpleName());
                status = "pending";
            }
        }

        RefundRecord latest = refundRecordMapper.findByOrderId(orderId);
        return new Outcome(orderId,
            latest == null ? initiated.outRefundNo() : latest.getOutRefundNo(),
            latest == null ? status : latest.getStatus(),
            !initiated.created());
    }

    /**
     * 模拟退款：走与真实渠道完全相同的落账状态机（MerchantRefundStatusService），
     * 只是不调用微信接口。生产 profile 下 requireSimulationAllowed 已提前拒绝。
     */
    private String simulateSuccess(Long orderId, String outRefundNo) {
        UserOrder order = orderMapper.selectById(orderId);
        if (order == null) throw new IllegalStateException("订单不存在");
        long cents = order.getPayAmountCents() == null ? 0 : order.getPayAmountCents();
        Refund refund = new Refund();
        refund.setOutRefundNo(outRefundNo);
        refund.setOutTradeNo(order.getOutTradeNo());
        refund.setTransactionId(order.getTransactionId());
        refund.setRefundId("SIM-REFUND-" + orderId);
        refund.setStatus(Status.SUCCESS);
        Amount amount = new Amount();
        amount.setRefund(cents);
        amount.setTotal(cents);
        amount.setCurrency("CNY");
        refund.setAmount(amount);
        String status = statusService.applyProviderResult(outRefundNo, refund);
        if ("success".equals(status) && order.getRiderId() != null) {
            try { riderMapper.refreshRiderStatus(order.getRiderId()); } catch (RuntimeException ignored) { }
        }
        return status;
    }

    /**
     * 模拟退款的准入：必须显式打开 campus.pay.simulate，且生产 profile 下一律拒绝。
     * 与 PayService 的支付模拟保持同一套规则，避免「能模拟支付不能模拟退款」的半配置状态。
     */
    private void requireSimulationAllowed(Long orderId) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                throw new IllegalStateException("生产环境禁止模拟退款（campus.pay.simulate），请配置微信支付商户参数并开启 wxpay.enabled");
            }
        }
        if (!simulate) {
            throw new IllegalStateException("微信支付未配置，暂不能在线退款，请稍后再试");
        }
        log.warn("[campus] 模拟退款生效：订单 {} 未调用微信接口，直接按已退款处理", orderId);
    }
}
