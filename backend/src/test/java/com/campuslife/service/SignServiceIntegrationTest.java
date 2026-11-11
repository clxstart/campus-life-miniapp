package com.campuslife.service;

import com.campuslife.mapper.PointsMapper;
import com.campuslife.mapper.SignMapper;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签到与连签的真实 MySQL 集成测试。
 *
 * 覆盖设计稿第六节中可自动化的用例：首次签到、重复签到、连签里程碑、断签重置、
 * 里程碑二次达成、并发双击、开关关闭、以及未签到时的状态回显。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 建临时 schema，结束即 DROP。
 * 未设置 MYSQL_TEST_URL 时整组跳过（跳过即"未验证"，交付报告必须写明）。
 */
class SignServiceIntegrationTest {

    private static final long UID = 990001L;

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static SignService service;
    private static PointsService points;
    private static SignMapper signMapper;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        seed("daily_sign", "points", "每日签到", 1, 1, 1);
        seed("sign_streak_3", "points", "连签 3 天", 5, 5, 1);
        seed("sign_streak_5", "points", "连签 5 天", 10, 10, 1);
        seed("sign_streak_7", "points", "连签 7 天", 15, 15, 1);
        seed("sign_streak_15", "points", "连签 15 天", 20, 20, 1);
        seed("sign_streak_20", "points", "连签 20 天", 30, 30, 1);
        seed("sign_streak_30", "points", "连签 30 天", 50, 50, 1);
        // 宠物签到并入同一账本（module=pet），用于「与宠物签到共存」用例
        seed("pet_checkin", "pet", "宠物签到", 5, 0, 1);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("sign-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(SignMapper.class);
        configuration.addMapper(PointsMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        points = new PointsService(template.getMapper(PointsMapper.class));
        signMapper = template.getMapper(SignMapper.class);
        service = new SignService(signMapper, points, List.of(), null);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static void seed(String key, String module, String name, int p, int g, int cap) {
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,1,0)",
            key, module, name, p, g, cap);
    }

    private static void reset() {
        jdbc.update("DELETE FROM sign_roll");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        jdbc.update("UPDATE task_catalog SET enabled=1");
        points.evictCatalog();
    }

    private static int balance() {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, UID);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int growth() {
        List<Integer> v = jdbc.queryForList("SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, UID);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static Map<String, Object> sign() {
        Map<String, Object> result = db.transactionTemplate.execute(status -> service.sign(UID));
        assertThat(result).isNotNull();
        return result;
    }

    private static LocalDate today() {
        return LocalDate.now(SignService.SHANGHAI);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> awarded(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("awarded");
    }

    // ==================== 用例 ====================

    @Test
    void firstSignGivesOneDayAndOnePoint() {
        reset();
        Map<String, Object> result = sign();
        assertThat(result.get("signedToday")).isEqualTo(true);
        assertThat(result.get("streakDays")).isEqualTo(1);
        assertThat(awarded(result)).extracting(m -> m.get("taskKey")).containsExactly("daily_sign");
        assertThat(balance()).isEqualTo(1);
        assertThat(growth()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sign_roll", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatSignSameDayOnlyAwardsOnce() {
        reset();
        sign();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> again = sign();
            assertThat(again.get("signedToday")).isEqualTo(true);
            // 幂等返回也必须带上真实的连签天数，否则页面会显示"已连续签到 0 天"
            assertThat(again.get("streakDays")).isEqualTo(1);
            assertThat(awarded(again)).isEmpty();
        }
        assertThat(balance()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log WHERE user_id=?", Integer.class, UID)).isEqualTo(1);
    }

    @Test
    void thirdConsecutiveDayGrantsMilestone() {
        reset();
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,2)", UID, today().minusDays(1));
        Map<String, Object> result = sign();
        assertThat(result.get("streakDays")).isEqualTo(3);
        assertThat(awarded(result)).extracting(m -> m.get("taskKey"))
            .containsExactly("daily_sign", "sign_streak_3");
        assertThat(balance()).isEqualTo(6);  // 1 + 5
        assertThat(growth()).isEqualTo(6);
    }

    @Test
    void missingYesterdayResetsStreakToOne() {
        reset();
        // 两天前签过 5 天，但昨天没签 → 今天从 1 开始，不补里程碑
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,5)", UID, today().minusDays(2));
        Map<String, Object> result = sign();
        assertThat(result.get("streakDays")).isEqualTo(1);
        assertThat(awarded(result)).extracting(m -> m.get("taskKey")).containsExactly("daily_sign");
    }

    @Test
    void milestoneCanBeEarnedAgainAfterReset() {
        reset();
        // 历史上已经拿过「连签 3 天」这一档（task_log 与 point_log 都要有，模拟真实发放）
        LocalDate tenDaysAgo = today().minusDays(10);
        jdbc.update("INSERT INTO task_log(user_id,task_key,biz_date,biz_key,p,g) VALUES(?,?,?,?,?,?)",
            UID, "sign_streak_3", tenDaysAgo, tenDaysAgo.toString(), 5, 5);
        jdbc.update("INSERT INTO point_log(user_id,amount,type,module,biz_key,g_amount) VALUES(?,?,'sign_streak_3','points',?,?)",
            UID, 5, "sign_streak_3:" + tenDaysAgo, 5);
        // 断签（昨天没签）后重新连到 3 天
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,2)", UID, today().minusDays(1));
        Map<String, Object> result = sign();
        assertThat(awarded(result)).extracting(m -> m.get("taskKey"))
            .containsExactly("daily_sign", "sign_streak_3");
        assertThat(balance()).isEqualTo(6);
        // 同一档里程碑在不同日期各发一次：历史那次 + 今天这次
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_log WHERE user_id=? AND type='sign_streak_3'", Integer.class, UID))
            .isEqualTo(2);
    }

    /** 设计稿用例 8：与宠物签到共存，两笔都在同一账本、module 不同。 */
    @Test
    void petCheckinAndDailySignWriteSeparateLedgerModules() {
        reset();
        // 宠物签到走同一条 PointsService 通道（module=pet，bizKey=日期）
        db.transactionTemplate.execute(status ->
            points.award(UID, "pet_checkin", today().toString(), "宠物签到"));
        Map<String, Object> result = sign();
        assertThat(awarded(result)).extracting(m -> m.get("taskKey")).containsExactly("daily_sign");
        assertThat(balance()).isEqualTo(6);    // 宠物 5 + 签到 1
        assertThat(growth()).isEqualTo(1);     // 宠物签到 g=0，只有签到 +1G
        assertThat(jdbc.queryForList(
            "SELECT module FROM point_log WHERE user_id=? ORDER BY id", String.class, UID))
            .containsExactly("pet", "points");
    }

    @Test
    void concurrentDoubleClickOnlyAwardsOnce() throws Exception {
        reset();
        List<Callable<Map<String, Object>>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) jobs.add(SignServiceIntegrationTest::sign);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Map<String, Object>>> futures = pool.invokeAll(jobs);
        pool.shutdown();
        int awardedCount = 0;
        for (Future<Map<String, Object>> f : futures) {
            Map<String, Object> response = f.get();
            // 并发下的失败方也必须是 signedToday=true、streakDays=1：
            // 早先「先 count 再 insert」的写法会让失败方用旧快照返回 false/0
            assertThat(response.get("signedToday")).isEqualTo(true);
            assertThat(response.get("streakDays")).isEqualTo(1);
            awardedCount += awarded(response).size();
        }
        assertThat(awardedCount).isEqualTo(1);
        assertThat(balance()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sign_roll", Integer.class)).isEqualTo(1);
    }

    @Test
    void disabledTaskRefusesSignAndDoesNotMarkSigned() {
        reset();
        jdbc.update("UPDATE task_catalog SET enabled=0 WHERE task_key='daily_sign'");
        points.evictCatalog();
        Map<String, Object> result = sign();
        assertThat(result.get("signedToday")).isEqualTo(false);
        assertThat(awarded(result)).isEmpty();
        assertThat(result.get("message")).isEqualTo("签到暂未开放");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sign_roll", Integer.class)).isZero();
    }

    @Test
    void statusShowsYesterdayStreakBeforeSigningToday() {
        reset();
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,4)", UID, today().minusDays(1));
        Map<String, Object> status = service.status(UID);
        assertThat(status.get("signedToday")).isEqualTo(false);
        assertThat(status.get("streakDays")).isEqualTo(4);
        Map<String, Object> next = (Map<String, Object>) status.get("nextMilestone");
        assertThat(next.get("days")).isEqualTo(5);
        assertThat(next.get("p")).isEqualTo(10);
    }

    /**
     * 设计稿用例 6：跨天边界——昨天 23:59 签过一次，今天再签应是「连签 +1」，
     * 且 sign_roll 落两行不同 biz_date（一天一行，不是覆盖同一行）。
     *
     * 真等到 23:59/00:01 需要可注入的 Clock，服务没有这个扩展点；
     * 这里用「直接写昨天那一行」复现同一判定路径：连签只查『昨天有没有行』。
     */
    @Test
    void crossDaySignAdvancesStreakAndWritesTwoBizDates() {
        reset();
        LocalDate yesterday = today().minusDays(1);
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,2)", UID, yesterday);
        Map<String, Object> result = sign();
        assertThat(result.get("streakDays")).isEqualTo(3);
        assertThat(awarded(result)).extracting(m -> m.get("taskKey"))
            .containsExactly("daily_sign", "sign_streak_3");
        // 两行、日期相差一天，连签是累加而不是覆盖
        assertThat(jdbc.queryForList("SELECT biz_date FROM sign_roll WHERE user_id=? ORDER BY biz_date",
            LocalDate.class, UID)).containsExactly(yesterday, today());
        assertThat(jdbc.queryForObject("SELECT streak_days FROM sign_roll WHERE user_id=? AND biz_date=?",
            Integer.class, UID, today())).isEqualTo(3);
    }

    /**
     * 设计稿用例 6 的**真时钟**版本：23:59:59 签一次、次日 00:00:01 再签。
     *
     * 原实现直接 `LocalDate.now(SHANGHAI)`，这条边界只能靠「造昨天那行」近似（见上一条用例）。
     * `SignService` 现在把「取当前时间」收敛到可注入的 `Clock`（容器无 Clock bean 时回落上海系统时钟，
     * 生产行为不变），于是可以真的把时钟拨到边界两侧，断言两个业务日期、连签 +1。
     */
    @Test
    void signsOnEitherSideOfShanghaiMidnightLandOnDifferentDays() {
        reset();
        LocalDate dayOne = LocalDate.of(2026, 3, 1);

        // 3 月 1 日 23:59:59（上海）：签第一次
        SignService beforeMidnight = new SignService(signMapper, points, List.of(),
            Clock.fixed(dayOne.atTime(23, 59, 59).atZone(SignService.SHANGHAI).toInstant(), SignService.SHANGHAI));
        assertThat(beforeMidnight.sign(UID).get("streakDays")).isEqualTo(1);

        // 3 月 2 日 00:00:01（上海）：同日再签会被判「今天已签」，但拨到次日必须算新的一天
        SignService justAfterMidnight = new SignService(signMapper, points, List.of(),
            Clock.fixed(dayOne.plusDays(1).atTime(0, 0, 1).atZone(SignService.SHANGHAI).toInstant(), SignService.SHANGHAI));
        Map<String, Object> second = justAfterMidnight.sign(UID);

        assertThat(second.get("streakDays")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT biz_date FROM sign_roll WHERE user_id=? ORDER BY biz_date",
            LocalDate.class, UID)).containsExactly(dayOne, dayOne.plusDays(1));
    }

    /**
     * 设计稿用例 7：服务器/JVM 默认时区不是北京时间时，仍按 Asia/Shanghai 分日。
     *
     * 做法：临时把默认时区改成一个「当前日期与上海不同」的时区（跨 19 点时用 UTC+14，
     * 其余用 UTC-11，两者必有一个与上海相差一天），再签到，断言落库日期是上海今天。
     * 如果代码写了不带时区的 LocalDate.now()，落库日期会变成默认时区那天，这里立刻失败。
     */
    @Test
    void signUsesShanghaiDateEvenWhenJvmDefaultZoneIsFarAway() {
        reset();
        LocalDate shanghaiToday = today();
        ZoneId farZone = null;
        for (String id : List.of("Pacific/Kiritimati", "Pacific/Midway", "Pacific/Pago_Pago")) {
            if (!LocalDate.now(ZoneId.of(id)).equals(shanghaiToday)) { farZone = ZoneId.of(id); break; }
        }
        assertThat(farZone).as("必须找到一个与上海日期不同的时区（UTC+14/UTC-11 必居其一）").isNotNull();
        assertThat(LocalDate.now(farZone)).isNotEqualTo(shanghaiToday);

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(farZone));
            Map<String, Object> result = sign();
            assertThat(result.get("streakDays")).isEqualTo(1);
            // 落库的是上海今天，不是默认时区那天
            assertThat(jdbc.queryForObject("SELECT biz_date FROM sign_roll WHERE user_id=?", LocalDate.class, UID))
                .isEqualTo(shanghaiToday);
            // 状态读取用同一口径：已签
            assertThat(service.status(UID).get("signedToday")).isEqualTo(true);
            // 再签一次走主键冲突分支，仍判为「今天已签」
            assertThat(awarded(sign())).isEmpty();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** 首次进来还没签过：连签 0 天、下一档是第一档 3 天（页面显示「已连续签到 0 天」不报错）。 */
    @Test
    void neverSignedReportsZeroStreakAndFirstMilestone() {
        reset();
        Map<String, Object> status = service.status(UID);
        assertThat(status.get("signedToday")).isEqualTo(false);
        assertThat(status.get("streakDays")).isEqualTo(0);
        Map<String, Object> next = (Map<String, Object>) status.get("nextMilestone");
        assertThat(next.get("days")).isEqualTo(3);
        assertThat(next.get("p")).isEqualTo(5);
        assertThat(next.get("g")).isEqualTo(5);
    }

    /** 六档里最后一档（连签 30 天，+50P/+50G）也能发放；超过 30 天后不再有下一档。 */
    @Test
    void thirtiethDayGrantsLastMilestone() {
        reset();
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,29)", UID, today().minusDays(1));
        Map<String, Object> result = sign();
        assertThat(result.get("streakDays")).isEqualTo(30);
        assertThat(awarded(result)).extracting(m -> m.get("taskKey"))
            .containsExactly("daily_sign", "sign_streak_30");
        assertThat(balance()).isEqualTo(51);   // 1 + 50
        assertThat(growth()).isEqualTo(51);
        assertThat(result.get("nextMilestone")).isNull();
        assertThat(service.status(UID).get("nextMilestone")).isNull();
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
        CREATE TABLE sign_roll (
          user_id BIGINT NOT NULL,
          biz_date DATE NOT NULL,
          streak_days INT NOT NULL DEFAULT 1,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id, biz_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}