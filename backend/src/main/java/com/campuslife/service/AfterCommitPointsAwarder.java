package com.campuslife.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 「主流程提交之后」才发积分的桥（F10 交易任务埋点）。
 *
 * 两个必须一起满足的点：
 *   1. **提交之后才发**：发分失败（积分表锁等待、连接抖动……）不能把外卖订单完成 / 兼职结算
 *      一起回滚。设计稿要求「发分失败只记日志，不影响交易主流程」。
 *   2. **必须单开新事务**：afterCommit 回调执行时，外层事务虽然已提交，但连接与事务同步资源
 *      仍绑定在当前线程上；此时直接调 @Transactional(REQUIRED) 的 PointsService 会"加入"
 *      那个已经提交的事务，语句可能被丢弃、或在连接清理时被回滚。这里用 REQUIRES_NEW
 *      挂起外层、单开一个短事务（同 DispatchService.autoDispatch 在 afterCommit 里的做法）。
 *
 * 交易侧的三个发分场景都经过这里：
 *   food_order  外卖订单完成      order:{orderId}
 *   job_settle  兼职合约结算成功  contract:{contractId}
 *   market_trade 二手成交确认     trade:{tradeId}:{userId}
 *
 * PointsService 仍是唯一发分入口：本类只负责"什么时候发、用哪个事务发"。
 */
@Slf4j
@Service
public class AfterCommitPointsAwarder {

    private final PointsService points;
    private final TransactionTemplate freshTransaction;

    public AfterCommitPointsAwarder(PointsService points, PlatformTransactionManager transactionManager) {
        this.points = points;
        this.freshTransaction = new TransactionTemplate(transactionManager);
        this.freshTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 发分。任何异常（包括新事务提交失败）都只进日志，绝不抛给调用方。
     *
     * @return 实际发放结果；失败/跳过返回 null（调用方只用于日志，不需要处理）
     */
    public PointsService.AwardResult award(Long userId, String taskKey, String bizKey, String remark) {
        if (userId == null || userId < 1) return null;
        try {
            PointsService.AwardResult result = freshTransaction.execute(status ->
                points.award(userId, taskKey, bizKey, remark));
            log.info("[campus] 交易发分 task={} user={} biz={} result={}", taskKey, userId, bizKey, result);
            return result;
        } catch (RuntimeException e) {
            log.warn("[campus] 交易发分失败（不影响主流程） task={} user={} biz={}: {}",
                taskKey, userId, bizKey, e.getMessage());
            return null;
        }
    }
}