package com.campuslife.service;

import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PointsMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外卖订单完成发分（F10 交易任务埋点）的真实 MySQL 集成测试。
 *
 * 对应设计稿 `docs/v3.1/交易任务埋点-功能说明与测试手册.md` 第六节用例 1-4：
 *   1 外卖完成发分 / 2 模拟支付不发 / 4 外卖日上限 / 14 外卖 order:{orderId} 幂等。
 * 这三条在手册里原本标着「⚠️ 未自动化：发分挂在 MerchantService.complete() 的『事务提交后』
 * 回调上，需要 Spring 事务同步 + FeatureFlags 才成立，手工 new 出来的服务测的是另一张对象图」。
 * 本测试正是把那张对象图搭出来：真库临时 schema + DataSourceTransactionManager +
 * 真实 AfterCommitPointsAwarder，因此可以断言「提交后才发 / 回滚不发 / 幂等」这三件
 * 手工很难说清楚的事。
 *
 * 关键装配（与生产一致的最小集）：
 *   - MerchantService 直接 new（7 个依赖，complete() 只用 mapper / awarder / flags，其余传 null）；
 *     没有 Spring 代理，所以 @Transactional 不生效 —— 事务边界完全由用例里的
 *     TransactionTemplate.execute 控制，这恰好让我们能观察「事务没提交时余额还是旧值」。
 *   - AfterCommitPointsAwarder 用 DataSourceTransactionManager 的 REQUIRES_NEW 模板发分；
 *     afterCommit 回调里外层的连接/MyBatis 会话会被 suspend，发分走独立事务。
 *   - FeatureFlags 用匿名子类，enabledStrict("points.award.simulated.pay") 恒为 false。
 *
 * 不碰 campus_life：{@link MysqlIntegrationSupport} 每次跑建临时 schema
 * （campus_life_it_&lt;随机&gt;），结束即 DROP。未设置 MYSQL_TEST_URL / MYSQL_TEST_USER /
 * MYSQL_TEST_PASSWORD 时整组跳过（跳过即「未验证」，交付报告必须写明）。
 */
class MerchantPointsAwardIntegrationTest {

    private static final long BUYER = 990001L;
    private static final long SHOP = 7001L;
    /** 与 db/module-points-v31.sql 的种子一致：10P/20G、日限 2、模块 commerce。 */
    private static final String TASK_FOOD = "food_order";
    private static final String TASK_MODULE = "commerce";

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static MerchantService merchant;
    private static AfterCommitPointsAwarder awarder;
    private static PointsService points;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        // 脚手架建的 user_order 缺这几列，而 MerchantMapper.order() 会 SELECT 它们：
        // items / total / rider_name 以及 requireOwnedOrder 的 shop_id。
        for (String ddl : EXTRA_ORDER_COLUMNS) jdbc.execute(ddl);
        for (String ddl : TABLES) jdbc.execute(ddl);
        // task_catalog 的 food_order 行照 module-points-v31.sql：commerce / 10P / 20G / 日限 2。
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            TASK_FOOD, TASK_MODULE, "外卖点单完成", 10, 20, 2, 1, 40);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
            "merchant-points-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(MerchantMapper.class);
        configuration.addMapper(PointsMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        awarder = new AfterCommitPointsAwarder(points, new DataSourceTransactionManager(db.dataSource));
        // 模拟支付开关按生产默认：未开启（enabledStrict 查不到 → false）。
        FeatureFlags flags = new FeatureFlags(null) {
            @Override
            public boolean enabledStrict(String key) {
                return false;
            }

            @Override
            public boolean isEnabled(String key) {
                return true;
            }
        };
        merchant = new MerchantService(
            template.getMapper(MerchantMapper.class), null, null, null, null, awarder, flags);
        tx = db.transactionTemplate;
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    /** 每个用例前清账：余额从 100 起，这样「旧值 → 新值」不会和「本来就没有行」混淆。 */
    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM user_order");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,100,100)", BUYER);
        points.evictCatalog();
    }

    // ==================== 用例 1：外卖完成发分 + 提交后才发 ====================

    @Test
    void completedFoodOrderAwardsBuyerOnce() {
        seedOrder(5001L, "accepted", "paid", "wx-txn-5001");

        // 「提交后才发」：complete() 在事务里执行完毕、但 execute 还没返回（事务未提交）时，
        // afterCommit 回调不能提前跑 —— 余额仍是旧的 100，point_log 仍是空的。
        int[] duringTransaction = new int[2];
        tx.execute(status -> {
            merchant.complete(SHOP, 5001L);
            duringTransaction[0] = balance();
            duringTransaction[1] = pointLogs();
            return null;
        });
        assertThat(duringTransaction[0]).as("事务提交前余额必须还是旧值").isEqualTo(100);
        assertThat(duringTransaction[1]).as("事务提交前不应有积分流水").isZero();

        // execute 返回（事务已提交、afterCommit 已执行）：+10P / +20G，流水写明来由与模块。
        assertThat(balance()).isEqualTo(110);
        assertThat(growth()).isEqualTo(20);
        assertThat(pointLogs()).isEqualTo(1);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", BUYER);
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(10);
        assertThat(((Number) log.get("g_amount")).intValue()).isEqualTo(20);
        assertThat(((Number) log.get("balance_after")).intValue()).isEqualTo(110);
        assertThat(log.get("remark")).isEqualTo("外卖订单完成");
        assertThat(log.get("module")).isEqualTo(TASK_MODULE);
        assertThat(log.get("biz_key")).isEqualTo("food_order:order:5001");
        assertThat(orderStatus(5001L)).isEqualTo("completed");
        // 订单详情页「+10 已到账」提示条的判据只认 point_log 流水。
        assertThat(merchant.pointsAwardedForOrder(5001L, BUYER)).isEqualTo(10);

        // 幂等（用例 14 的外卖侧）：重复 complete 被状态机拒绝；就算直接重复调用发分桥，
        // biz_key=order:5001 的唯一键也只放行一次。
        assertThatThrownBy(() -> merchant.complete(SHOP, 5001L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("订单状态不能从 completed");
        assertThat(awarder.award(BUYER, TASK_FOOD, "order:5001", "外卖订单完成"))
            .isEqualTo(PointsService.AwardResult.ALREADY);
        assertThat(balance()).isEqualTo(110);
        assertThat(growth()).isEqualTo(20);
        assertThat(pointLogs()).isEqualTo(1);
    }

    // ==================== 用例 2：模拟支付不发 ====================

    @Test
    void simulatedPaymentDoesNotAward() {
        seedOrder(5101L, "accepted", "paid", "SIM-OUT-5101");

        merchant.complete(SHOP, 5101L);

        // 订单本身正常完成，只是不发分：point_log 无行、余额/成长值不变；
        // 而且连 task_log 都不写（前置判断在进入 PointsService 之前就返回了）。
        assertThat(orderStatus(5101L)).isEqualTo("completed");
        assertThat(balance()).isEqualTo(100);
        assertThat(growth()).isZero();
        assertThat(pointLogs()).isZero();
        assertThat(taskLogs()).isZero();
        assertThat(merchant.pointsAwardedForOrder(5101L, BUYER)).isZero();
    }

    // ==================== 用例 4：外卖日上限（前 2 单） ====================

    @Test
    void dailyCapAwardsFirstTwoOrdersOnly() {
        long[] ids = {5201L, 5202L, 5203L};
        for (long id : ids) seedOrder(id, "accepted", "paid", "wx-txn-" + id);

        for (long id : ids) merchant.complete(SHOP, id);

        // 三单都完成，但只有前两单产生流水。
        assertThat(orderStatus(5201L)).isEqualTo("completed");
        assertThat(orderStatus(5202L)).isEqualTo("completed");
        assertThat(orderStatus(5203L)).isEqualTo("completed");

        assertThat(balance()).isEqualTo(120);   // 100 + 10 + 10
        assertThat(growth()).isEqualTo(40);     // 20 + 20
        assertThat(pointLogs()).isEqualTo(2);
        List<String> bizKeys = jdbc.queryForList(
            "SELECT biz_key FROM point_log WHERE user_id=? ORDER BY id", String.class, BUYER);
        assertThat(bizKeys).containsExactly("food_order:order:5201", "food_order:order:5202");

        // 第 3 单：日上限命中，task_log 仍留痕（biz_key=order:{id}），但不发分。
        assertThat(taskLogs()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key=? AND biz_key=?",
            Integer.class, BUYER, TASK_FOOD, "order:5203")).isEqualTo(1);
        assertThat(merchant.pointsAwardedForOrder(5203L, BUYER)).isZero();
    }

    // ==================== 用例：非实付订单不发 ====================

    @Test
    void nonPaidOrderDoesNotAward() {
        seedOrder(5301L, "accepted", "unpaid", "wx-txn-5301");

        merchant.complete(SHOP, 5301L);

        assertThat(orderStatus(5301L)).isEqualTo("completed");
        assertThat(balance()).isEqualTo(100);
        assertThat(growth()).isZero();
        assertThat(pointLogs()).isZero();
        assertThat(taskLogs()).isZero();
    }

    // ==================== 附加硬要求：外层事务回滚不发 ====================

    @Test
    void rollbackDoesNotAward() {
        seedOrder(5401L, "accepted", "paid", "wx-txn-5401");

        // complete() 已在事务里注册 afterCommit，但外层抛异常 → 回滚，回调被丢弃，
        // 这证明发分没有在「注册动作」时就提前执行。
        assertThatThrownBy(() -> tx.execute(status -> {
            merchant.complete(SHOP, 5401L);
            throw new IllegalStateException("模拟主流程失败");
        })).isInstanceOf(IllegalStateException.class)
           .hasMessage("模拟主流程失败");

        assertThat(balance()).isEqualTo(100);
        assertThat(growth()).isZero();
        assertThat(pointLogs()).isZero();
        assertThat(taskLogs()).isZero();
        // 订单更新同样被回滚：状态仍是 accepted，可重新完成。
        assertThat(orderStatus(5401L)).isEqualTo("accepted");
    }

    // ==================== 用例 14（外卖侧并发）：重复回调只发一次 ====================

    /**
     * 同一订单被 8 个线程同时 complete()：只能有 1 次成功、只发 1 次分。
     *
     * 这条补的是设计稿用例 14 里原先空着的那半句「并发重复回调仍靠 task_log 唯一键兜底」：
     * 实际上第一道闸是状态机的条件 UPDATE（accepted→completed 只命中一次），
     * 没抢到的那 7 个线程在 completeOrder 影响 0 行时就抛异常，根本走不到发分桥。
     * task_log 唯一键只是最后一道兜底。这里把真实的并发路径钉住。
     */
    @Test
    void concurrentCompleteAwardsOnlyOnce() throws Exception {
        seedOrder(5601L, "accepted", "paid", "wx-txn-5601");

        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        tx.execute(status -> {
                            merchant.complete(SHOP, 5601L);
                            return null;
                        });
                        return true;
                    } catch (RuntimeException rejected) {
                        // 状态机条件更新没抢到：completeOrder 影响 0 行 → IllegalStateException
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (java.util.concurrent.Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, java.util.concurrent.TimeUnit.SECONDS))) succeeded++;
            }

            assertThat(succeeded).as("accepted→completed 是条件更新，只能有一个线程成功").isEqualTo(1);
            assertThat(balance()).as("只发一次分").isEqualTo(110);
            assertThat(growth()).isEqualTo(20);
            assertThat(pointLogs()).isEqualTo(1);
            assertThat(taskLogs()).isEqualTo(1);
            assertThat(orderStatus(5601L)).isEqualTo("completed");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 工具 ====================

    private static void seedOrder(long id, String status, String payStatus, String transactionId) {
        jdbc.update("""
            INSERT INTO user_order(id,user_id,shop_id,status,pay_status,transaction_id,rider_id,total,items,rider_name)
            VALUES(?,?,?,?,?,?,NULL,25.00,'[]',NULL)
            """, id, BUYER, SHOP, status, payStatus, transactionId);
    }

    private static String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM user_order WHERE id=?", String.class, id);
    }

    private static int balance() {
        List<Integer> rows = jdbc.queryForList(
            "SELECT balance FROM user_points WHERE user_id=?", Integer.class, BUYER);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static int growth() {
        List<Integer> rows = jdbc.queryForList(
            "SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, BUYER);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static int pointLogs() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class);
        return rows == null ? 0 : rows;
    }

    private static int taskLogs() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_log", Integer.class);
        return rows == null ? 0 : rows;
    }

    // ==================== 建表 / 补列（最小集） ====================

    /** 脚手架已建 user_order，但缺 MerchantMapper.order() 查询用到的列，这里补齐（不重复建表）。 */
    private static final String[] EXTRA_ORDER_COLUMNS = {
        "ALTER TABLE user_order ADD COLUMN shop_id BIGINT NOT NULL DEFAULT 0",
        "ALTER TABLE user_order ADD COLUMN items TEXT NULL",
        "ALTER TABLE user_order ADD COLUMN total DECIMAL(10,2) NOT NULL DEFAULT 0",
        "ALTER TABLE user_order ADD COLUMN rider_name VARCHAR(64) NULL"
    };

    private static final String[] TABLES = {
        """
        CREATE TABLE user_points (
          user_id BIGINT PRIMARY KEY,
          balance INT NOT NULL DEFAULT 0,
          total_earned INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE point_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          amount INT NOT NULL,
          type VARCHAR(32) NOT NULL,
          ref_id BIGINT NULL,
          remark VARCHAR(100) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          module VARCHAR(16) NOT NULL DEFAULT 'commerce',
          biz_key VARCHAR(64) NULL,
          g_amount INT NOT NULL DEFAULT 0,
          balance_after INT NULL,
          KEY idx_user_time (user_id, created_at),
          UNIQUE KEY uk_user_biz (user_id, biz_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_growth (
          user_id BIGINT PRIMARY KEY,
          g_total INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_catalog (
          task_key VARCHAR(32) PRIMARY KEY,
          module VARCHAR(16) NOT NULL,
          name VARCHAR(32) NOT NULL,
          p_reward INT NOT NULL DEFAULT 0,
          g_reward INT NOT NULL DEFAULT 0,
          daily_cap INT NOT NULL DEFAULT 1,
          enabled TINYINT NOT NULL DEFAULT 1,
          sort INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          task_key VARCHAR(32) NOT NULL,
          biz_date DATE NOT NULL,
          biz_key VARCHAR(64) NOT NULL,
          p INT NOT NULL DEFAULT 0,
          g INT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_task_idem (user_id, task_key, biz_key),
          KEY idx_task_day (user_id, task_key, biz_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}