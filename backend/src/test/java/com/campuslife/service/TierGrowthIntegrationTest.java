package com.campuslife.service;

import com.campuslife.mapper.FeatureFlagMapper;
import com.campuslife.mapper.PointsMapper;
import com.campuslife.mapper.TierMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 段位与成长值的真实 MySQL 集成测试。
 *
 * 单元测试（TierServiceTest）把配置读成内存里的 8 行，验证的是纯推导；
 * 这里补的是单元测试覆盖不到的三件事：
 *   1. 门槛来自库里真实的 tier_config（含 enabled 口径）；
 *   2. g_total 在真实 SQL 下的「只涨不降」与并发累加不丢更新；
 *   3. user_privilege 的过期过滤是 SQL 里的 NOW() 条件，mock 掉就测不出来。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 建临时 schema，结束即 DROP。
 * 未设置 MYSQL_TEST_URL 时整组跳过（跳过即"未验证"，交付报告必须写明）。
 */
class TierGrowthIntegrationTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static TierService tier;
    private static PointsService points;
    private static FeatureFlags flags;
    private static FeatureFlagMapper flagMapper;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        for (Object[] row : TIERS) {
            jdbc.update("INSERT INTO tier_config(tier_code,name,g_threshold,enabled,sort) VALUES(?,?,?,?,?)", row);
        }
        for (int i = 1; i <= 12; i++) {
            jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,1,?)",
                "grow_" + i, "points", "成长任务 " + i, 1, 1, 1, i);
        }

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("tier-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(PointsMapper.class);
        configuration.addMapper(TierMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        flagMapper = mock(FeatureFlagMapper.class);
        flags = new FeatureFlags(flagMapper);
        setFlags(true, true);
        points = new PointsService(template.getMapper(PointsMapper.class));
        tier = new TierService(template.getMapper(TierMapper.class), flags, null); // null=系统时钟
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static void setFlags(boolean fastlane, boolean circle) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(flag("post.fastlane.enabled", fastlane));
        rows.add(flag("circle.create.enabled", circle));
        when(flagMapper.listAll()).thenReturn(rows);
        flags.invalidate();
    }

    private static Map<String, Object> flag(String key, boolean on) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("flagKey", key);
        row.put("enabled", on ? 1 : 0);
        row.put("remark", "");
        return row;
    }

    private static void reset(long uid) {
        jdbc.update("DELETE FROM user_points WHERE user_id=?", uid);
        jdbc.update("DELETE FROM user_growth WHERE user_id=?", uid);
        jdbc.update("DELETE FROM point_log WHERE user_id=?", uid);
        jdbc.update("DELETE FROM task_log WHERE user_id=?", uid);
        jdbc.update("DELETE FROM user_privilege WHERE user_id=?", uid);
        points.evictCatalog();
    }

    private static int growth(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, uid);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int balance(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, uid);
        return v.isEmpty() ? 0 : v.get(0);
    }

    // ==================== 用例 ====================

    /** 设计稿用例 1 / 2 / 3 / 4 / 6：真实 tier_config 上的推导与进度。 */
    @Test
    void derivesTiersAndProgressFromRealConfig() {
        assertThat(tier.forGrowth(0).get("code")).isEqualTo("bronze");
        assertThat(tier.forGrowth(59).get("code")).isEqualTo("bronze");
        assertThat(tier.forGrowth(60).get("code")).isEqualTo("silver");
        assertThat(tier.forGrowth(199).get("code")).isEqualTo("silver");
        assertThat(tier.forGrowth(200).get("code")).isEqualTo("gold");

        Map<String, Object> top = tier.forGrowth(9999);
        assertThat(top.get("code")).isEqualTo("gold");
        assertThat(top.get("maxOpen")).isEqualTo(true);
        assertThat(top.get("nextCode")).isNull();

        assertThat(tier.forGrowth(142).get("percent")).isEqualTo(58);
        assertThat(tier.forGrowth(0).get("percent")).isEqualTo(0);
    }

    /** 设计稿用例 1：新用户库里还没有 user_growth 行时，按 G=0 返回青铜、进度 0/60（不报空指针）。 */
    @Test
    void newUserWithoutGrowthRowIsBronzeWithZeroProgress() {
        long uid = 990016L;
        reset(uid);
        Map<String, Object> t = tier.forUser(uid);
        assertThat(t.get("code")).isEqualTo("bronze");
        assertThat(t.get("gTotal")).isEqualTo(0);
        assertThat(t.get("nextCode")).isEqualTo("silver");
        assertThat(t.get("nextThreshold")).isEqualTo(60);
        assertThat(t.get("percent")).isEqualTo(0);
        assertThat(t.get("maxOpen")).isEqualTo(false);
    }

    /** 设计稿用例 5：g_total 只涨不降——连发、重复发、扣分三条路径都不减 G。 */
    @Test
    void growthOnlyIncreases() {
        long uid = 990011L;
        reset(uid);
        db.transactionTemplate.execute(s -> points.award(uid, "grow_1", "d1", "任务1"));
        db.transactionTemplate.execute(s -> points.award(uid, "grow_2", "d2", "任务2"));
        assertThat(growth(uid)).isEqualTo(2);
        // 同一 bizKey 重复发放：幂等，不重复加
        db.transactionTemplate.execute(s -> points.award(uid, "grow_1", "d1", "任务1"));
        assertThat(growth(uid)).isEqualTo(2);
        // 扣积分（消费）不碰成长值
        db.transactionTemplate.execute(s -> points.spend(uid, "points", "spend-1", 1, "spend", "消费"));
        assertThat(balance(uid)).isEqualTo(1);
        assertThat(growth(uid)).isEqualTo(2);
    }

    /** 设计稿用例 7：10 个任务并发发放，g_total 精确等于 10，无丢更新。 */
    @Test
    void concurrentAwardsDoNotLoseGrowth() throws Exception {
        long uid = 990012L;
        reset(uid);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final int n = i;
            jobs.add(() -> db.transactionTemplate.execute(s ->
                points.award(uid, "grow_" + n, "c" + n, "并发任务 " + n)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            for (Future<Object> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdown();
        }
        assertThat(growth(uid)).isEqualTo(10);
    }

    /** 设计稿用例 12：体验段位只在有效期内抬高生效段位，过期立刻失效。 */
    @Test
    void expiredTrialDoesNotRaiseTier() {
        long uid = 990013L;
        reset(uid);
        jdbc.update("INSERT INTO user_privilege(user_id,priv_key,active,expires_at) VALUES(?,?,1, DATE_SUB(NOW(), INTERVAL 1 DAY))",
            uid, "tier_trial_silver");
        assertThat(tier.effectiveTierCode(uid)).isEqualTo("bronze");
        assertThat(tier.privileges(uid).get("priorityReply")).isEqualTo(false);

        jdbc.update("UPDATE user_privilege SET expires_at=DATE_ADD(NOW(), INTERVAL 7 DAY) WHERE user_id=? AND priv_key=?",
            uid, "tier_trial_silver");
        assertThat(tier.effectiveTierCode(uid)).isEqualTo("silver");
        assertThat(tier.privileges(uid).get("priorityReply")).isEqualTo(true);
        // 白银体验不给黄金特权
        assertThat(tier.privileges(uid).get("postFastlane")).isEqualTo(false);
        assertThat(tier.privileges(uid).get("circleCreate")).isEqualTo(false);
        // 展示口径仍按成长值（G=0 是青铜），不被体验段位改写
        assertThat(tier.forGrowth(tier.growthOf(uid)).get("code")).isEqualTo("bronze");
    }

    /** 开关关闭后对应特权立刻消失（模拟管理端改开关 + invalidate）。 */
    @Test
    void featureFlagOffRemovesPrivileges() {
        long uid = 990014L;
        reset(uid);
        jdbc.update("INSERT INTO user_growth(user_id,g_total) VALUES(?,200)", uid);
        assertThat(tier.privileges(uid).get("postFastlane")).isEqualTo(true);
        assertThat(tier.privileges(uid).get("circleCreate")).isEqualTo(true);

        setFlags(false, false);
        Map<String, Object> off = tier.privileges(uid);
        assertThat(off.get("postFastlane")).isEqualTo(false);
        assertThat(off.get("circleCreate")).isEqualTo(false);
        assertThat(off.get("priorityReply")).isEqualTo(true);
        setFlags(true, true);
    }

    /** 设计稿用例 8 的可验证部分：改门槛后清缓存即按新门槛推导（30 秒自动过期的等待未测）。 */
    @Test
    void thresholdChangeTakesEffectAfterCacheEvict() {
        long uid = 990015L;
        reset(uid);
        assertThat(tier.forGrowth(160).get("code")).isEqualTo("silver");
        jdbc.update("UPDATE tier_config SET g_threshold=150 WHERE tier_code='gold'");
        try {
            tier.evict();
            assertThat(tier.forGrowth(160).get("code")).isEqualTo("gold");
        } finally {
            jdbc.update("UPDATE tier_config SET g_threshold=200 WHERE tier_code='gold'");
            tier.evict();
        }
    }

    // ==================== 建表与种子 ====================

    private static final Object[][] TIERS = {
        {"bronze", "青铜·萌新", 0, 1, 10},
        {"silver", "白银·圈内熟人", 60, 1, 20},
        {"gold", "黄金·活跃分子", 200, 1, 30},
        {"platinum", "铂金·风云人物", 600, 0, 40},
        {"diamond", "钻石·一校红人", 1500, 0, 50},
        {"star", "星耀·校园顶流", 3500, 0, 60},
        {"king", "王者·城市之星", 8000, 0, 70},
        {"legend", "传奇·校宝名人堂", 18000, 0, 80}
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
          module VARCHAR(16) NOT NULL DEFAULT 'pet',
          biz_key VARCHAR(64) NULL,
          g_amount INT NOT NULL DEFAULT 0,
          balance_after INT NULL,
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
        """,
        """
        CREATE TABLE tier_config (
          tier_code VARCHAR(16) PRIMARY KEY,
          name VARCHAR(32) NOT NULL,
          g_threshold INT NOT NULL,
          s_threshold INT NOT NULL DEFAULT 0,
          honor_label VARCHAR(32) NOT NULL DEFAULT '',
          enabled TINYINT NOT NULL DEFAULT 1,
          sort INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_privilege (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          priv_key VARCHAR(32) NOT NULL,
          active TINYINT NOT NULL DEFAULT 1,
          activated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          expires_at DATETIME NULL,
          UNIQUE KEY uk_priv (user_id, priv_key),
          KEY idx_priv_active (user_id, active, expires_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}