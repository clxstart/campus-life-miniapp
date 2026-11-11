package com.campuslife.service;

import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 并发退款验证（需要 MYSQL_TEST_URL，未设置则跳过）。
 *
 * 证明的是数据库层事实，不是 mock 顺序调用：
 *  - 同一订单并发领取只有一个事务命中条件 UPDATE；
 *  - 只有赢家能写入退款账本，且退款单号确定（RF + orderId）；
 *  - 每订单唯一约束会拒绝第二个可提交渠道的退款单号；
 *  - REPEATABLE READ 下，等待后的当前读能看到赢家刚提交的 refunding 状态与退款记录。
 */
class RefundConcurrencyIntegrationTest {
    private static MysqlIntegrationSupport db;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void concurrentClaimsCreateExactlyOneChannelSubmittableRefund() throws Exception {
        db.insertPaidOrder(100L, 3L, "paid", "paid", 1500, "T100", "W100");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try (SqlSession session = db.sessionFactory.openSession(false)) {
                        UserOrderMapper orders = session.getMapper(UserOrderMapper.class);
                        if (orders.claimRefund(100L) == 1) {
                            RefundRecordMapper refunds = session.getMapper(RefundRecordMapper.class);
                            int inserted = refunds.insertRefundIfAbsent(100L, "RF100", 1500, "并发退款");
                            session.commit();
                            return inserted == 1 ? 1 : -1;
                        }
                        session.rollback();
                        return 0;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Integer> result : results) winners += result.get(30, TimeUnit.SECONDS);
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=100", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT out_refund_no FROM refund_record WHERE order_id=100", String.class)).isEqualTo("RF100");
        assertThat(db.jdbc.queryForObject("SELECT status FROM user_order WHERE id=100", String.class)).isEqualTo("refunding");
        assertThat(db.jdbc.queryForObject("SELECT pay_status FROM user_order WHERE id=100", String.class)).isEqualTo("refunding");
    }

    @Test
    void retryAndDuplicateNumberCannotCreateASecondRefund() throws Exception {
        db.insertPaidOrder(101L, 3L, "paid", "paid", 1500, "T101", "W101");
        try (SqlSession session = db.sessionFactory.openSession(false)) {
            UserOrderMapper orders = session.getMapper(UserOrderMapper.class);
            RefundRecordMapper refunds = session.getMapper(RefundRecordMapper.class);
            assertThat(orders.claimRefund(101L)).isEqualTo(1);
            assertThat(refunds.insertRefundIfAbsent(101L, "RF101", 1500, "首次退款")).isEqualTo(1);
            session.commit();
        }
        // 用不同单号再次写入：唯一键 uk_refund_order 使其 0 行命中，不能生成第二个可提交渠道的退款单
        try (SqlSession session = db.sessionFactory.openSession(false)) {
            RefundRecordMapper refunds = session.getMapper(RefundRecordMapper.class);
            assertThat(refunds.insertRefundIfAbsent(101L, "RF101-DUP", 1500, "重复请求")).isZero();
            session.commit();
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=101", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT out_refund_no FROM refund_record WHERE order_id=101", String.class)).isEqualTo("RF101");

        // 非 INSERT IGNORE 的直插会被数据库拒绝，证明约束真实存在而不是靠应用层巧合
        assertThatThrownBy(() -> db.jdbc.update(
            "INSERT INTO refund_record(order_id,out_refund_no,amount_cents,reason,status) VALUES(101,'RF101-RAW',1500,'raw','pending')"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loserWaitsForWinnerThenCurrentReadSeesCommittedState() throws Exception {
        db.insertPaidOrder(102L, 3L, "paid", "paid", 1500, "T102", "W102");
        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> winner = pool.submit(() -> {
                try (SqlSession session = db.sessionFactory.openSession(false)) {
                    UserOrderMapper orders = session.getMapper(UserOrderMapper.class);
                    RefundRecordMapper refunds = session.getMapper(RefundRecordMapper.class);
                    assertThat(orders.claimRefund(102L)).isEqualTo(1);
                    assertThat(refunds.insertRefundIfAbsent(102L, "RF102", 1500, "并发退款")).isEqualTo(1);
                    inserted.countDown();
                    Thread.sleep(400); // 持锁未提交：输家必须在这里等待
                    session.commit();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });

            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            try (SqlSession loser = db.sessionFactory.openSession(false)) {
                UserOrderMapper orders = loser.getMapper(UserOrderMapper.class);
                RefundRecordMapper refunds = loser.getMapper(RefundRecordMapper.class);
                int claimed = orders.claimRefund(102L);
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertThat(claimed).isZero();
                assertThat(waitedMs).isGreaterThanOrEqualTo(200L);

                // 当前读：看到赢家提交后的 refunding，而不是事务开始前的 paid 快照
                UserOrder current = orders.findByIdForUpdate(102L);
                assertThat(current).isNotNull();
                assertThat(current.getStatus()).isEqualTo("refunding");
                assertThat(current.getPayStatus()).isEqualTo("refunding");

                // 赢家已提交，本事务的第一条普通读建立快照时能看到唯一退款记录
                RefundRecord record = refunds.findByOrderId(102L);
                assertThat(record).isNotNull();
                assertThat(record.getOutRefundNo()).isEqualTo("RF102");
                loser.commit();
            }
            winner.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentOrdersMayEachHaveOneRefund() throws Exception {
        db.insertPaidOrder(103L, 3L, "paid", "paid", 1500, "T103", "W103");
        db.insertPaidOrder(104L, 4L, "accepted", "paid", 2000, "T104", "W104");
        try (SqlSession session = db.sessionFactory.openSession(false)) {
            UserOrderMapper orders = session.getMapper(UserOrderMapper.class);
            RefundRecordMapper refunds = session.getMapper(RefundRecordMapper.class);
            assertThat(orders.claimRefund(103L)).isEqualTo(1);
            assertThat(refunds.insertRefundIfAbsent(103L, "RF103", 1500, "退款")).isEqualTo(1);
            assertThat(orders.claimRefund(104L)).isEqualTo(1);
            assertThat(refunds.insertRefundIfAbsent(104L, "RF104", 2000, "退款")).isEqualTo(1);
            session.commit();
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_record", Integer.class)).isGreaterThanOrEqualTo(2);
    }
}
