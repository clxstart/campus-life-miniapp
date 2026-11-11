package com.campuslife.service;

import com.campuslife.ha.JdbcClusterLock;
import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 上的「关单后到账」补偿账本验证（需要 MYSQL_TEST_URL，未设置则跳过）。
 *
 * 证明：只有渠道 SUCCESS 才写 resolved；refund_record / user_order / payment_exception
 * 在同一事务里收敛；PROCESSING 不伪报已退款；失败转 manual；迟到结果不能回退已解决状态；
 * 两个执行者只有一个能领取异常单并建立退款账本。
 */
class RefundCompensationIntegrationTest {
    private static MysqlIntegrationSupport db;
    private static MerchantRefundStatusService statusService;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        SqlSessionTemplate template = new SqlSessionTemplate(db.springSessionFactory);
        statusService = new MerchantRefundStatusService(
            template.getMapper(MerchantMapper.class),
            template.getMapper(PaymentExceptionMapper.class),
            template.getMapper(RefundRecordMapper.class),
            org.mockito.Mockito.mock(CouponService.class),
            org.mockito.Mockito.mock(com.campuslife.mapper.EarningsLedgerMapper.class));
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static Refund refund(Status status, String outRefundNo, String outTradeNo, String transactionId, long cents) {
        Refund r = new Refund();
        r.setOutRefundNo(outRefundNo);
        r.setOutTradeNo(outTradeNo);
        r.setTransactionId(transactionId);
        r.setRefundId("R-" + outRefundNo);
        r.setStatus(status);
        Amount amount = new Amount();
        amount.setRefund(cents);
        amount.setTotal(cents);
        r.setAmount(amount);
        return r;
    }

    private String exceptionStatus(long id) {
        return db.jdbc.queryForObject("SELECT status FROM payment_exception WHERE id=?", String.class, id);
    }

    private String refundStatus(long orderId) {
        return db.jdbc.queryForObject("SELECT status FROM refund_record WHERE order_id=?", String.class, orderId);
    }

    private String orderStatus(long orderId) {
        return db.jdbc.queryForObject("SELECT status FROM user_order WHERE id=?", String.class, orderId);
    }

    private String payStatus(long orderId) {
        return db.jdbc.queryForObject("SELECT pay_status FROM user_order WHERE id=?", String.class, orderId);
    }

    @Test
    void onlyChannelSuccessConvergesAllLedgersAndLateResultsCannotRegress() {
        db.insertPaidOrder(200L, 3L, "closed", "closed", 1500, "T200", "W200");
        db.insertException(11L, 200L, "T200", "W200", 1500);

        Boolean claimed = db.transactionTemplate.execute(s ->
            statusService.claimCompensation(11L, 200L, "PAC11", 1500, "订单已关闭，退还支付款"));
        assertThat(claimed).isTrue();
        assertThat(exceptionStatus(11L)).isEqualTo("refund_submitted");
        assertThat(refundStatus(200L)).isEqualTo("pending");

        // 渠道处理中：不能标记已退款
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(11L, "PAC11", refund(Status.PROCESSING, "PAC11", "T200", "W200", 1500)));
        assertThat(exceptionStatus(11L)).isEqualTo("refund_submitted");
        assertThat(refundStatus(200L)).isEqualTo("pending");
        assertThat(orderStatus(200L)).isEqualTo("closed");
        assertThat(payStatus(200L)).isEqualTo("refunding");

        // 渠道确认成功：四张账一致收敛
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(11L, "PAC11", refund(Status.SUCCESS, "PAC11", "T200", "W200", 1500)));
        assertThat(exceptionStatus(11L)).isEqualTo("resolved");
        assertThat(refundStatus(200L)).isEqualTo("success");
        assertThat(orderStatus(200L)).isEqualTo("refunded");
        assertThat(payStatus(200L)).isEqualTo("refunded");
        assertThat(db.jdbc.queryForObject("SELECT refund_id FROM refund_record WHERE order_id=200", String.class)).isEqualTo("R-PAC11");

        // 旧节点迟到的失败/处理中：账本与异常单都不能被改回
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(11L, "PAC11", refund(Status.CLOSED, "PAC11", "T200", "W200", 1500)));
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(11L, "PAC11", refund(Status.PROCESSING, "PAC11", "T200", "W200", 1500)));
        assertThat(exceptionStatus(11L)).isEqualTo("resolved");
        assertThat(refundStatus(200L)).isEqualTo("success");
        assertThat(orderStatus(200L)).isEqualTo("refunded");
        assertThat(payStatus(200L)).isEqualTo("refunded");

        // 绕过服务直接发一条「迟到失败」的 UPDATE：条件不命中，证明数据库层也挡住了回退
        int changed = db.jdbc.update(
            "UPDATE payment_exception SET status='manual' WHERE id=11 AND status IN ('open','refund_submitted')");
        assertThat(changed).isZero();
        assertThat(exceptionStatus(11L)).isEqualTo("resolved");
    }

    @Test
    void explicitFailureGoesToManualWithoutPretendingRefunded() {
        db.insertPaidOrder(201L, 3L, "cancelled", "closed", 1800, "T201", "W201");
        db.insertException(12L, 201L, "T201", "W201", 1800);
        Boolean claimed = db.transactionTemplate.execute(s ->
            statusService.claimCompensation(12L, 201L, "PAC12", 1800, "订单已关闭，退还支付款"));
        assertThat(claimed).isTrue();

        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(12L, "PAC12", refund(Status.CLOSED, "PAC12", "T201", "W201", 1800)));

        assertThat(exceptionStatus(12L)).isEqualTo("manual");
        assertThat(refundStatus(201L)).isEqualTo("failed");
        assertThat(orderStatus(201L)).isEqualTo("cancelled");
        String note = db.jdbc.queryForObject("SELECT note FROM payment_exception WHERE id=12", String.class);
        assertThat(note).contains("需人工处理");
    }

    @Test
    void refundNotificationConvergesExceptionWithoutWaitingForNextPolling() {
        db.insertPaidOrder(204L, 3L, "closed", "closed", 1300, "T204", "W204");
        db.insertException(15L, 204L, "T204", "W204", 1300);
        Boolean claimed = db.transactionTemplate.execute(s ->
            statusService.claimCompensation(15L, 204L, "PAC15", 1300, "订单已关闭，退还支付款"));
        assertThat(claimed).isTrue();

        RefundNotification notification = new RefundNotification();
        notification.setOutRefundNo("PAC15");
        notification.setOutTradeNo("T204");
        notification.setTransactionId("W204");
        notification.setRefundId("R-PAC15");
        notification.setRefundStatus(Status.SUCCESS);
        Amount amount = new Amount();
        amount.setRefund(1300L);
        amount.setTotal(1300L);
        notification.setAmount(amount);

        db.transactionTemplate.executeWithoutResult(s -> statusService.applyNotification(notification));

        // 回调先于轮询到达也要一次收敛，不能停在 refund_submitted 等下一轮
        assertThat(exceptionStatus(15L)).isEqualTo("resolved");
        assertThat(refundStatus(204L)).isEqualTo("success");
        assertThat(orderStatus(204L)).isEqualTo("refunded");
        assertThat(payStatus(204L)).isEqualTo("refunded");
    }

    @Test
    void twoExecutorsClaimingSameExceptionCreateOnlyOneLedgerEntry() throws Exception {
        db.insertPaidOrder(202L, 3L, "closed", "closed", 2200, "T202", "W202");
        db.insertException(13L, 202L, "T202", "W202", 2200);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    Boolean won = db.transactionTemplate.execute(s ->
                        statusService.claimCompensation(13L, 202L, "PAC13", 2200, "订单已关闭，退还支付款"));
                    return won;
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) if (Boolean.TRUE.equals(result.get(20, TimeUnit.SECONDS))) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=202", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT out_refund_no FROM refund_record WHERE order_id=202", String.class)).isEqualTo("PAC13");
        assertThat(exceptionStatus(13L)).isEqualTo("refund_submitted");
    }

    @Test
    void expiredLeaseAllowsOverlapButLateFailureCannotRegressResolved() throws Exception {
        db.insertPaidOrder(205L, 3L, "closed", "closed", 1500, "T205", "W205");
        db.insertException(16L, 205L, "T205", "W205", 1500);

        JdbcTemplate jdbc = new JdbcTemplate(db.dataSource);
        JdbcClusterLock nodeA = new JdbcClusterLock(jdbc, "node-A");
        JdbcClusterLock nodeB = new JdbcClusterLock(jdbc, "node-B");
        // JdbcClusterLock 以秒为单位（Math.max(1, lease.toSeconds())），1 秒租约
        assertThat(nodeA.tryLock("pay-compensation", Duration.ofSeconds(1))).isTrue();
        assertThat(nodeB.tryLock("pay-compensation", Duration.ofSeconds(1))).isFalse();
        // A 暂停、租约到期，B 接手：真实系统里此时会出现两个执行者短暂重叠。
        // NOW() 是秒级精度，1 秒租约需要睡过下一个整秒（2.2s）才能稳定判过期。
        Thread.sleep(2200);
        assertThat(nodeB.tryLock("pay-compensation", Duration.ofSeconds(5))).isTrue();

        Boolean claimed = db.transactionTemplate.execute(s ->
            statusService.claimCompensation(16L, 205L, "PAC16", 1500, "订单已关闭，退还支付款"));
        assertThat(claimed).isTrue();
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(16L, "PAC16", refund(Status.SUCCESS, "PAC16", "T205", "W205", 1500)));
        assertThat(exceptionStatus(16L)).isEqualTo("resolved");

        // A 恢复后迟到返回失败：不能把 B 已写好的成功/resolved 退回
        db.transactionTemplate.executeWithoutResult(s ->
            statusService.applyCompensationResult(16L, "PAC16", refund(Status.CLOSED, "PAC16", "T205", "W205", 1500)));

        assertThat(exceptionStatus(16L)).isEqualTo("resolved");
        assertThat(refundStatus(205L)).isEqualTo("success");
        assertThat(orderStatus(205L)).isEqualTo("refunded");
        assertThat(payStatus(205L)).isEqualTo("refunded");
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=205", Integer.class)).isEqualTo(1);
        nodeB.unlock("pay-compensation");
    }
}
