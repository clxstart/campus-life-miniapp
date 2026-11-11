package com.campuslife.service;

import com.campuslife.mapper.PointsMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分账本的真实 MySQL 集成测试。
 *
 * 不碰 campus_life：每次跑都在临时 schema（campus_life_it_<随机>）里建最小表，结束即 DROP。
 * 未设置 MYSQL_TEST_URL / MYSQL_TEST_USER 时整组跳过（跳过即视为"未验证"，交付报告里必须写清）。
 *
 * 为什么要真库：这一层的正确性几乎全压在数据库约束上——唯一键挡幂等、条件 UPDATE 挡扣成负数、
 * GREATEST 挡 total_earned 漂移。这些用 Mockito 打桩是测不出来的。
 */
class PointsServiceIntegrationTest {

    private static final String USER = "999001";

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static PointsService service;
    private static SqlSessionTemplate template;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            "daily_sign", "points", "每日签到", 1, 1, 1, 1, 10);
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            "post_create", "forum", "发论坛帖", 5, 5, 3, 1, 30);
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            "off_task", "points", "已停用任务", 9, 9, 1, 0, 90);
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            "pet_dispatch", "pet", "宠物派遣奖励", 0, 0, 0, 1, 91);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration(db.dataSource));
        template = new SqlSessionTemplate(factory);
        service = new PointsService(template.getMapper(PointsMapper.class));
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
}

    private static Configuration configuration(DataSource ds) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("points-it", new SpringManagedTransactionFactory(), ds));
        configuration.addMapper(PointsMapper.class);
        return configuration;
    }

    /** 每个用例前清账，保证互不干扰。 */
    private static void reset() {
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        service.evictCatalog();
    }

    private static long uid() { return Long.parseLong(USER); }

    private static int balance() {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, uid());
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int growth() {
        List<Integer> v = jdbc.queryForList("SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, uid());
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int logCount() {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM point_log WHERE user_id=?", Integer.class, uid());
        return v == null ? 0 : v;
    }

    // ==================== 用例 ====================

    @Test
    void awardsOnceAndWritesBothLedgers() {
        reset();
        assertThat(service.award(uid(), "daily_sign", "2026-11-09", "每日签到"))
            .isEqualTo(PointsService.AwardResult.AWARDED);

        assertThat(balance()).isEqualTo(1);
        assertThat(growth()).isEqualTo(1);
        assertThat(logCount()).isEqualTo(1);

        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", uid());
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(1);
        assertThat(((Number) log.get("g_amount")).intValue()).isEqualTo(1);
        assertThat(((Number) log.get("balance_after")).intValue()).isEqualTo(1);
        assertThat(log.get("module")).isEqualTo("points");
        assertThat(log.get("biz_key")).isEqualTo("daily_sign:2026-11-09");
    }

    @Test
    void secondCallWithSameBizKeyIsIdempotent() {
        reset();
        service.award(uid(), "daily_sign", "2026-11-09", "每日签到");
        assertThat(service.award(uid(), "daily_sign", "2026-11-09", "每日签到"))
            .isEqualTo(PointsService.AwardResult.ALREADY);

        assertThat(balance()).isEqualTo(1);
        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    void dailyCapStopsTheFourthAward() {
        reset();
        assertThat(service.award(uid(), "post_create", "post-1", "发帖")).isEqualTo(PointsService.AwardResult.AWARDED);
        assertThat(service.award(uid(), "post_create", "post-2", "发帖")).isEqualTo(PointsService.AwardResult.AWARDED);
        assertThat(service.award(uid(), "post_create", "post-3", "发帖")).isEqualTo(PointsService.AwardResult.AWARDED);
        // 第 4 次：cap=3，超限不发分
        assertThat(service.award(uid(), "post_create", "post-4", "发帖")).isEqualTo(PointsService.AwardResult.CAPPED);

        assertThat(balance()).isEqualTo(15);   // 5 × 3
        assertThat(logCount()).isEqualTo(3);
        // 超限的 bizKey 留痕但不产生流水，避免同一业务被反复试探
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key='post_create'", Integer.class, uid());
        assertThat(rows).isEqualTo(4);
    }

    @Test
    void yesterdayCapDoesNotBlockToday() {
        reset();
        // 昨天已发满 3 次（post_create 日上限 3）
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO task_log(user_id,task_key,biz_date,biz_key,p,g) VALUES(?,?,?,?,?,?)",
                uid(), "post_create", yesterday, "old-" + i, 5, 5);
        }
        // 今天不受昨天计数影响
        assertThat(service.award(uid(), "post_create", "today-1", "发帖"))
            .isEqualTo(PointsService.AwardResult.AWARDED);
        assertThat(balance()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key='post_create' AND biz_date=?",
            Integer.class, uid(), LocalDate.now(ZoneId.of("Asia/Shanghai")))).isEqualTo(1);
    }

    @Test
    void businessDateUsesShanghaiEvenWhenServerDefaultZoneIsUtc() {
        reset();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertThat(service.award(uid(), "daily_sign", "tz-1", "每日签到"))
                .isEqualTo(PointsService.AwardResult.AWARDED);
            // biz_date 必须是北京时间当天，不随 JVM 默认时区漂移
            LocalDate bizDate = jdbc.queryForObject(
                "SELECT biz_date FROM task_log WHERE user_id=? AND biz_key='tz-1'", LocalDate.class, uid());
            assertThat(bizDate).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void disabledTaskAwardsNothing() {
        reset();
        assertThat(service.award(uid(), "off_task", "x", "停用")).isEqualTo(PointsService.AwardResult.DISABLED);
        assertThat(balance()).isZero();
        assertThat(logCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_log", Integer.class)).isZero();
    }

    @Test
    void spendRefusesToGoNegative() {
        reset();
        service.award(uid(), "daily_sign", "d1", "每日签到");   // 余额 1
        assertThat(service.spend(uid(), "pet", "feed-1", 10, "pet_feed", "喂食")).isFalse();
        assertThat(balance()).isEqualTo(1);
        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    void spendDeductsAndLogsNegativeWithBalanceAfter() {
        reset();
        service.award(uid(), "daily_sign", "d1", "每日签到");   // 1
        jdbc.update("UPDATE user_points SET balance = 50 WHERE user_id=?", uid());

        assertThat(service.spend(uid(), "pet", "wardrobe:7", 20, "pet_wardrobe", "兑换装扮")).isTrue();
        assertThat(balance()).isEqualTo(30);
        // 扣分不动成长值与累计获得
        assertThat(growth()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_earned FROM user_points WHERE user_id=?", Integer.class, uid()))
            .isEqualTo(1);

        Map<String, Object> log = jdbc.queryForMap(
            "SELECT * FROM point_log WHERE user_id=? AND biz_key='pet:wardrobe:7'", uid());
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(-20);
        assertThat(((Number) log.get("balance_after")).intValue()).isEqualTo(30);
    }

    @Test
    void repeatableSpendWithoutBizKeyIsAllowed() {
        reset();
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,100,100)", uid());
        // 喂食一天可以喂多次，bizKey 传 null 时不构成幂等拒绝
        assertThat(service.spend(uid(), "pet", null, 10, "pet_feed", "喂食")).isTrue();
        assertThat(service.spend(uid(), "pet", null, 10, "pet_feed", "喂食")).isTrue();
        assertThat(balance()).isEqualTo(80);
    }

    @Test
    void totalEarnedOnlyGrowsOnPositiveAwards() {
        reset();
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,0,0)", uid());
        service.awardCustom(uid(), "pet_dispatch", "dispatch-1", 12, 0, "派遣奖励");
        assertThat(balance()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT total_earned FROM user_points WHERE user_id=?", Integer.class, uid()))
            .isEqualTo(12);
        service.spend(uid(), "pet", "w-1", 12, "pet_wardrobe", "兑换");
        assertThat(balance()).isZero();
        // 扣分不能把「累计获得」冲掉
        assertThat(jdbc.queryForObject("SELECT total_earned FROM user_points WHERE user_id=?", Integer.class, uid()))
            .isEqualTo(12);
    }

    @Test
    void concurrentAwardWithSameBizKeyOnlyCountsOnce() throws Exception {
        reset();
        List<Callable<PointsService.AwardResult>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> service.award(uid(), "daily_sign", "same-key", "并发签到"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<PointsService.AwardResult>> futures = pool.invokeAll(jobs);
        pool.shutdown();

        int awarded = 0, already = 0;
        for (Future<PointsService.AwardResult> f : futures) {
            PointsService.AwardResult r = f.get();
            if (r == PointsService.AwardResult.AWARDED) awarded++;
            if (r == PointsService.AwardResult.ALREADY) already++;
        }
        assertThat(awarded).isEqualTo(1);
        assertThat(already).isEqualTo(9);
        assertThat(balance()).isEqualTo(1);
    }

    @Test
    void catalogEditsTakeEffectImmediatelyAfterEvict() {
        reset();
        jdbc.update("UPDATE task_catalog SET p_reward=7, g_reward=9 WHERE task_key='daily_sign'");
        service.evictCatalog();
        service.award(uid(), "daily_sign", "d-edit", "改过奖励");
        assertThat(balance()).isEqualTo(7);
        assertThat(growth()).isEqualTo(9);
        jdbc.update("UPDATE task_catalog SET p_reward=1, g_reward=1 WHERE task_key='daily_sign'");
        service.evictCatalog();
    }

    @Test
    void tasksListHidesDisabledAndReportsTodayDone() {
        reset();
        service.award(uid(), "daily_sign", "d-today", "签到");
        List<Map<String, Object>> tasks = service.tasks(uid());
        assertThat(tasks).extracting(t -> t.get("taskKey")).contains("daily_sign", "post_create").doesNotContain("off_task");
        Map<String, Object> sign = tasks.stream().filter(t -> "daily_sign".equals(t.get("taskKey"))).findFirst().orElseThrow();
        assertThat(sign.get("doneToday")).isEqualTo(1);
        assertThat(sign.get("available")).isEqualTo(false);
    }

    @Test
    void logsReturnNewestFirstWithPaging() {
        reset();
        service.award(uid(), "daily_sign", "a", "第一笔");
        service.award(uid(), "post_create", "b", "第二笔");
        service.award(uid(), "post_create", "c", "第三笔");
        Map<String, Object> page1 = service.logs(uid(), 1, 2);
        assertThat((List<?>) page1.get("list")).hasSize(2);
        assertThat(page1.get("total")).isEqualTo(3L);
        Map<String, Object> first = ((List<Map<String, Object>>) page1.get("list")).get(0);
        assertThat(first.get("remark")).isEqualTo("第三笔");
    }

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
          module VARCHAR(16) NOT NULL DEFAULT 'pet',
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