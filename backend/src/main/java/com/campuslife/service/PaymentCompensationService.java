package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.ha.ClusterLock;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 「关单后到账」自动退款补偿（finding-04 / finding-followup）。
 *
 * 资金安全设计：
 *  - 退款单号由异常单 id 确定性生成（PAC + id），重试/多节点重叠时提交的是同一个 out_refund_no，
 *    微信按单号去重，不会退两次；
 *  - 先用条件 UPDATE 把 open 领取为 refund_submitted 并写标准 refund_record（同一短事务），
 *    同一条只会有一个节点建立账本；
 *  - 渠道结果不确定（超时/异常）时保持 refund_submitted，下一轮先查询再决定，绝不盲目换单号重提；
 *  - 只有渠道确认 SUCCESS 才把 refund_record / 订单 / payment_exception 收敛为已退款；
 *    PROCESSING 保持处理中，失败/多次无法确认转 manual 并告警；
 *  - 账本状态更新全部带来源状态条件：租约过期后旧节点恢复，迟到的失败/处理中结果
 *    无法把新节点已写好的 resolved 改回去；
 *  - 网络请求不持有数据库事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCompensationService {
    static final int MAX_ATTEMPTS = 6;

    private final PaymentExceptionMapper mapper;
    private final ObjectProvider<RefundService> refundServiceProvider;
    private final WxPayProperties properties;
    private final PaymentAlerts alerts;
    private final ClusterLock clusterLock;
    private final MerchantRefundStatusService statusService;

    @Value("${campus.pay.compensation.auto-refund:true}")
    private boolean autoRefund = true;

    /** 租约长度：单轮时间预算必须明显小于它，正常情况下租约不会在执行中过期 */
    static final Duration LEASE = Duration.ofMinutes(5);

    /**
     * 单轮时间预算（默认 180s < 5min 租约）：超出即停止领取新单、顺延到下一轮。
     * 最坏情况下已开始的单条渠道调用仍可能越过租约，重叠由逐条领取 + 确定性单号 + 单调条件更新兜底。
     */
    @Value("${campus.pay.compensation.budget-ms:180000}")
    private long budgetMs = 180_000;

    @Scheduled(fixedDelayString = "${campus.pay.compensation.interval-ms:120000}", initialDelay = 90000)
    public void run() {
        clusterLock.runExclusive("pay-compensation", LEASE, this::runOnce);
    }

    public int runOnce() {
        long deadline = System.currentTimeMillis() + budgetMs;
        List<Map<String, Object>> batch = mapper.selectCompensationBatch(20);
        int handled = 0;
        for (Map<String, Object> row : batch) {
            if (System.currentTimeMillis() > deadline) {
                alerts.warn("compensation", "budget_exceeded", "补偿单轮预算耗尽，剩余异常单顺延到下一轮");
                break;
            }
            try { if (handle(row)) handled++; }
            catch (RuntimeException e) { alerts.warn("compensation", "handle_error", "异常单 {} 处理失败：{}", row.get("id"), e.getClass().getSimpleName()); }
        }
        return handled;
    }

    static String refundNo(long exceptionId) { return "PAC" + exceptionId; }

    /**
     * 已提交过的异常单优先使用数据库里记录的 out_refund_no：
     * 这样即使将来编号规则变化，重试也绝不会换号重提。
     */
    static String refundNo(long exceptionId, Object stored) {
        String value = stored == null ? "" : String.valueOf(stored).trim();
        return value.isEmpty() || "null".equals(value) ? refundNo(exceptionId) : value;
    }

    /** payment_exception.note 是 varchar(255)，严格模式下超长会报错，先截断 */
    static String note(String text) {
        if (text == null) return "";
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    boolean handle(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String status = String.valueOf(row.get("status"));
        int attempts = row.get("attempts") instanceof Number n ? n.intValue() : 0;
        RefundService refundService = refundServiceProvider.getIfAvailable();
        if (!autoRefund || !properties.isEnabled() || refundService == null) {
            // 条件更新：已 resolved 的单据不会被「关闭自动退款」误改回 manual
            mapper.markManual(id, "未开启自动退款或微信退款未配置，需人工退款", 0);
            alerts.warn("compensation", "manual_required", "异常单 {} 需人工退款", id);
            return false;
        }
        String outRefundNo = refundNo(id, row.get("outRefundNo"));
        if ("open".equals(status)) {
            Long orderId = row.get("orderId") instanceof Number n ? n.longValue() : null;
            int amount = row.get("amountCents") instanceof Number n ? n.intValue() : 0;
            if (!statusService.claimCompensation(id, orderId, outRefundNo, amount, "订单已关闭，退还支付款")) {
                return false; // 已被其他节点领取，或账本冲突已转人工
            }
            submit(id, row, outRefundNo, refundService, attempts + 1);
            return true;
        }
        // refund_submitted：先查询渠道结果；查不到再用同一单号重提
        try {
            QueryByOutRefundNoRequest query = new QueryByOutRefundNoRequest();
            query.setOutRefundNo(outRefundNo);
            Refund refund = refundService.queryByOutRefundNo(query);
            if (refund == null) throw new IllegalStateException("渠道退款查询返回空结果");
            statusService.applyCompensationResult(id, outRefundNo, refund);
            return true;
        } catch (IllegalArgumentException invalidResult) {
            // 结果与本地账本不匹配（金额/单号），不能当成暂时性故障重试，直接转人工
            mapper.markManual(id, note("退款结果与本地账本不匹配，需人工核实：" + invalidResult.getMessage()), 0);
            alerts.warn("compensation", "invalid_result", "异常单 {} 退款结果不匹配", id);
            return false;
        } catch (RuntimeException queryError) {
            if (attempts >= MAX_ATTEMPTS) {
                mapper.markManual(id, "多次无法确认退款结果，需人工核实", 0);
                alerts.warn("compensation", "gave_up", "异常单 {} 退款结果多次无法确认", id);
                return false;
            }
            mapper.markRefundSubmitted(id, "退款结果查询失败，稍后重试", 1);
            submit(id, row, outRefundNo, refundService, attempts + 1);
            return true;
        }
    }

    private void submit(long id, Map<String, Object> row, String outRefundNo, RefundService refundService, int attempts) {
        long amount = ((Number) row.get("amountCents")).longValue();
        CreateRequest request = new CreateRequest();
        request.setTransactionId(String.valueOf(row.get("transactionId")));
        request.setOutRefundNo(outRefundNo);
        request.setReason("订单已关闭，退还支付款");
        if (properties.getRefundNotifyUrl() != null && !properties.getRefundNotifyUrl().isBlank()) request.setNotifyUrl(properties.getRefundNotifyUrl());
        AmountReq amountReq = new AmountReq();
        amountReq.setRefund(amount); amountReq.setTotal(amount); amountReq.setCurrency("CNY");
        request.setAmount(amountReq);
        try {
            Refund refund = refundService.create(request);
            if (refund == null) throw new IllegalStateException("渠道退款返回空结果");
            statusService.applyCompensationResult(id, outRefundNo, refund);
        } catch (IllegalArgumentException invalidResult) {
            mapper.markManual(id, note("退款结果与本地账本不匹配，需人工核实：" + invalidResult.getMessage()), 0);
            alerts.warn("compensation", "invalid_result", "异常单 {} 退款结果不匹配", id);
        } catch (RuntimeException e) {
            // 结果不确定：保持 refund_submitted，下一轮先查询；绝不换号盲目重提
            alerts.warn("compensation", "submit_uncertain", "异常单 {} 退款提交结果不确定：{}", id, e.getClass().getSimpleName());
        }
    }
}
