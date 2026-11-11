package com.campuslife.service;

import com.campuslife.ha.ClusterLock;
import com.campuslife.mapper.ForumRankMapper;
import com.campuslife.mapper.PointsMapper;
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

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 论坛日榜/周榜的真实 MySQL 集成测试（设计稿「六、测试计划」12 条里可自动化的部分）。
 *
 * 为什么必须用真库：这一层的正确性全压在数据库上 —— 算分 SQL 的 UNION ALL + GROUP BY、
 * uk_rank 挡重复结算、task_log 的 biz_key 挡重复发分、notification 的 source_key 挡重复通知。
 * 这些用 Mockito 打桩一个都测不出来。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 每次建临时 schema（campus_life_it_<随机>），结束即 DROP。
 * 未设置 MYSQL_TEST_URL / MYSQL_TEST_USER 时整组跳过 —— 跳过即「未验证」，交接说明里必须写清。
 */
class ForumRankServiceTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static ForumRankService service;
    private static ForumRankMapper rankMapper;
    private static PointsService points;

    /** 永远拿到租约的锁，用于正常路径。 */
    private static final ClusterLock OPEN_LOCK = new ClusterLock() {
        @Override public boolean tryLock(String name, Duration lease) { return true; }
        @Override public void unlock(String name) { }
    };

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        // 与 db/module-forum-rank-v31.sql 的种子一致
        seedTask("rank_daily", "论坛日榜上榜", 10, 15);
        seedTask("rank_weekly", "论坛周榜上榜", 15, 25);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration(db.dataSource));
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        rankMapper = template.getMapper(ForumRankMapper.class);
        points = new PointsService(template.getMapper(PointsMapper.class));
        service = new ForumRankService(rankMapper, points, OPEN_LOCK);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static Configuration configuration(DataSource ds) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("forum-rank-it", new SpringManagedTransactionFactory(), ds));
        configuration.addMapper(ForumRankMapper.class);
        configuration.addMapper(PointsMapper.class);
        return configuration;
    }

    private static void seedTask(String key, String name, int p, int g) {
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) "
            + "VALUES(?,?,?,?,?,?,?,?)", key, "points", name, p, g, 1, 1, 90);
    }

    /** 每个用例前清账，保证互不干扰。 */
    private static void reset() {
        for (String table : new String[]{"forum_rank", "task_log", "point_log", "user_points", "user_growth",
            "post_like", "comment_like", "comment", "post", "notification", "scheduler_lock"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM `user`");
        points.evictCatalog();
    }

    // ==================== 造数工具 ====================

    private static void user(long id) {
        jdbc.update("INSERT INTO `user`(id,nickname) VALUES(?,?)", id, "u" + id);
    }

    private static void post(long id, long userId, int status, LocalDateTime at) {
        jdbc.update("INSERT INTO post(id,user_id,title,content,status,created_at) VALUES(?,?,?,?,?,?)",
            id, userId, "标题", "正文", status, at);
    }

    private static void comment(long id, long postId, long userId, String content, LocalDateTime at) {
        jdbc.update("INSERT INTO comment(id,post_id,user_id,content,created_at) VALUES(?,?,?,?,?)",
            id, postId, userId, content, at);
    }

    private static void postLike(long postId, long userId, LocalDateTime at) {
        jdbc.update("INSERT INTO post_like(post_id,user_id,created_at) VALUES(?,?,?)", postId, userId, at);
    }

    private static void commentLike(long commentId, long userId, LocalDateTime at) {
        jdbc.update("INSERT INTO comment_like(comment_id,user_id,created_at) VALUES(?,?,?)", commentId, userId, at);
    }

    private static Map<String, Object> rankRow(String board, String periodKey, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM forum_rank WHERE board=? AND period_key=? AND user_id=?", board, periodKey, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int balance(long userId) {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, userId);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int growth(long userId) {
        List<Integer> v = jdbc.queryForList("SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, userId);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int count(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 1, 10, 0, 0);
    private static final String DAY_KEY = "2026-11-01";

    // ==================== 用例 1：日榜算分 ====================

    @Test
    void dailyScoreCountsPostsCommentsAndLikes() {
        reset();
        user(1); user(2); user(3);
        // 用户 1：发帖 2 篇 → 6 分
        post(11, 1, 1, DAY);
        post(12, 1, 1, DAY.plusMinutes(1));
        // 用户 2：评论 3 条（≥5 字）→ 6 分
        comment(21, 11, 2, "这个帖子写得很有帮助", DAY);
        comment(22, 11, 2, "我也想参加这样的活动", DAY);
        comment(23, 12, 2, "请问在哪里报名呢", DAY);
        // 用户 3：帖子不在本周期内，但有 5 个被赞 → 5 分
        post(13, 3, 1, DAY.minusDays(3));
        for (int i = 0; i < 5; i++) postLike(13, 100 + i, DAY.minusMinutes(i));

        Map<String, Object> summary = service.settle(ForumRankService.DAILY, DAY_KEY);

        assertThat(summary.get("rows")).isEqualTo(3);
        assertThat(((Number) rankRow("daily", DAY_KEY, 1).get("score")).intValue()).isEqualTo(6);
        assertThat(((Number) rankRow("daily", DAY_KEY, 2).get("score")).intValue()).isEqualTo(6);
        assertThat(((Number) rankRow("daily", DAY_KEY, 3).get("score")).intValue()).isEqualTo(5);
        // 同分按 user_id 升序，名次不重复
        assertThat(((Number) rankRow("daily", DAY_KEY, 1).get("rank_no")).intValue()).isEqualTo(1);
        assertThat(((Number) rankRow("daily", DAY_KEY, 2).get("rank_no")).intValue()).isEqualTo(2);
        assertThat(((Number) rankRow("daily", DAY_KEY, 3).get("rank_no")).intValue()).isEqualTo(3);
    }

    // ==================== 用例 2：短评论不计分 ====================

    @Test
    void shortCommentIsIgnored() {
        reset();
        user(1); user(2);
        post(11, 1, 1, DAY);
        comment(21, 11, 2, "顶", DAY);            // 1 字
        comment(22, 11, 2, "  ok  ", DAY);        // TRIM 后 2 字
        comment(23, 11, 2, "这个活动真不错", DAY);  // 7 字，计入

        service.settle(ForumRankService.DAILY, DAY_KEY);

        // 只有那条 7 字评论算 2 分；「顶」与 "ok" 都是 0 分，所以用户 2 得分 2 而不是 8
        assertThat(((Number) rankRow("daily", DAY_KEY, 2).get("score")).intValue()).isEqualTo(2);
    }

    // ==================== 用例 3：未过审帖不计分 ====================

    @Test
    void unapprovedPostIsIgnored() {
        reset();
        user(1); user(2);
        post(11, 1, 0, DAY);   // status=0，还在审核/已下架
        post(12, 2, 1, DAY);   // 正常过审

        service.settle(ForumRankService.DAILY, DAY_KEY);

        assertThat(rankRow("daily", DAY_KEY, 1)).isNull();
        assertThat(((Number) rankRow("daily", DAY_KEY, 2).get("score")).intValue()).isEqualTo(3);
    }

    // ==================== 用例 4：前 10 发放 ====================

    @Test
    void dailyTopTenGetRewardsAndEleventhGetsNothing() {
        reset();
        for (int i = 0; i < 12; i++) {
            long uid = 101 + i;
            user(uid);
            post(1000 + i, uid, 1, DAY);
        }

        Map<String, Object> summary = service.settle(ForumRankService.DAILY, DAY_KEY);

        assertThat(summary.get("rows")).isEqualTo(10);
        assertThat(summary.get("awarded")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE board='daily' AND period_key=?", DAY_KEY)).isEqualTo(10);
        // 前 10（同分按 user_id 升序 → 101..110）
        for (int i = 0; i < 10; i++) {
            long uid = 101 + i;
            assertThat(balance(uid)).isEqualTo(10);
            assertThat(growth(uid)).isEqualTo(15);
            assertThat(((Number) rankRow("daily", DAY_KEY, uid).get("awarded")).intValue()).isEqualTo(1);
        }
        // 第 11、12 名没有快照行，也没有分
        assertThat(rankRow("daily", DAY_KEY, 111)).isNull();
        assertThat(balance(111)).isZero();
    }

    // ==================== 用例 5：周榜前 20 ====================

    @Test
    void weeklyTopTwentyGetRewards() {
        reset();
        LocalDateTime inWeek = LocalDateTime.of(2026, 11, 4, 12, 0);
        for (int i = 0; i < 25; i++) {
            long uid = 201 + i;
            user(uid);
            post(2000 + i, uid, 1, inWeek);
        }
        String weekKey = ForumRankService.periodKeyOf(ForumRankService.WEEKLY, inWeek.toLocalDate());
        assertThat(weekKey).isEqualTo("2026-W45");

        Map<String, Object> summary = service.settle(ForumRankService.WEEKLY, weekKey);

        assertThat(summary.get("rows")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE board='weekly' AND period_key=?", weekKey)).isEqualTo(20);
        for (int i = 0; i < 20; i++) {
            long uid = 201 + i;
            assertThat(balance(uid)).isEqualTo(15);
            assertThat(growth(uid)).isEqualTo(25);
        }
        assertThat(balance(221)).isZero();
    }

    // ==================== 用例 6：重跑幂等 ====================

    @Test
    void resettleIsIdempotentAndDoesNotDoubleAward() {
        reset();
        for (int i = 0; i < 3; i++) {
            long uid = 301 + i;
            user(uid);
            post(3000 + i, uid, 1, DAY.minusMinutes(i));
        }

        service.resettle(ForumRankService.DAILY, DAY_KEY);
        int logsAfterFirst = count("SELECT COUNT(*) FROM point_log");
        int balanceAfterFirst = balance(301);

        Map<String, Object> second = service.resettle(ForumRankService.DAILY, DAY_KEY);

        assertThat(count("SELECT COUNT(*) FROM point_log")).isEqualTo(logsAfterFirst);
        assertThat(balance(301)).isEqualTo(balanceAfterFirst).isEqualTo(10);
        assertThat(second.get("awarded")).isEqualTo(3);   // 本轮仍算「已确认发放」，但没再发分
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE board='daily' AND period_key=? AND awarded=1", DAY_KEY))
            .isEqualTo(3);
        // task_log 的 biz_key=periodKey，重复结算只留一条事实
        assertThat(count("SELECT COUNT(*) FROM task_log WHERE user_id=301 AND task_key='rank_daily'")).isEqualTo(1);
    }

    @Test
    void resettleDropsUsersWhoFellOffTheBoard() {
        reset();
        user(401); user(402);
        post(4010, 401, 1, DAY);
        post(4020, 402, 1, DAY);
        // 先把发奖关掉：这样快照会落下来但 awarded 停在 0，模拟「发奖前」的状态
        jdbc.update("UPDATE task_catalog SET enabled=0 WHERE task_key='rank_daily'");
        points.evictCatalog();
        service.settle(ForumRankService.DAILY, DAY_KEY);
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE period_key=? AND awarded=0", DAY_KEY)).isEqualTo(2);
        assertThat(balance(401)).isZero();

        // 401 的帖子下架后重结算：还没发奖的旧快照行必须被清掉，不能继续挂在榜上
        jdbc.update("UPDATE post SET status=0 WHERE id=4010");
        jdbc.update("UPDATE task_catalog SET enabled=1 WHERE task_key='rank_daily'");
        points.evictCatalog();
        service.settle(ForumRankService.DAILY, DAY_KEY);

        assertThat(rankRow("daily", DAY_KEY, 401)).isNull();
        assertThat(rankRow("daily", DAY_KEY, 402)).isNotNull();
        assertThat(balance(402)).isEqualTo(10);
    }

    // ==================== 用例 7/8：时间窗边界 ====================

    @Test
    void lastMinutePostCountsToThatDayAndNextSecondToTheNextDay() {
        reset();
        user(1); user(2);
        post(11, 1, 1, LocalDateTime.of(2026, 11, 7, 23, 59, 59));
        post(12, 2, 1, LocalDateTime.of(2026, 11, 8, 0, 0, 0));

        service.resettle(ForumRankService.DAILY, "2026-11-07");
        service.resettle(ForumRankService.DAILY, "2026-11-08");

        assertThat(rankRow("daily", "2026-11-07", 1)).isNotNull();
        assertThat(rankRow("daily", "2026-11-07", 2)).isNull();
        assertThat(rankRow("daily", "2026-11-08", 2)).isNotNull();
        assertThat(rankRow("daily", "2026-11-08", 1)).isNull();
    }

    @Test
    void sundayAndMondayFallIntoDifferentWeeks() {
        reset();
        user(1); user(2);
        LocalDate sunday = LocalDate.of(2026, 11, 8);
        LocalDate monday = LocalDate.of(2026, 11, 9);
        post(11, 1, 1, sunday.atTime(23, 59, 59));
        post(12, 2, 1, monday.atTime(0, 1, 0));

        assertThat(ForumRankService.periodKeyOf(ForumRankService.WEEKLY, sunday)).isEqualTo("2026-W45");
        assertThat(ForumRankService.periodKeyOf(ForumRankService.WEEKLY, monday)).isEqualTo("2026-W46");

        service.resettle(ForumRankService.WEEKLY, "2026-W45");
        assertThat(rankRow("weekly", "2026-W45", 1)).isNotNull();
        assertThat(rankRow("weekly", "2026-W45", 2)).isNull();
        // 下一周还没结束，不能结算（否则会把还在变动的分数当成最终结果）
        assertThatThrownBy(() -> service.resettle(ForumRankService.WEEKLY, "2026-W46"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("还未结束");
    }

    // ==================== 用例 9：并列 ====================

    @Test
    void tieBreaksByUserIdAscending() {
        reset();
        user(9); user(5);
        post(91, 9, 1, DAY);
        post(51, 5, 1, DAY);

        service.settle(ForumRankService.DAILY, DAY_KEY);

        assertThat(((Number) rankRow("daily", DAY_KEY, 5).get("rank_no")).intValue()).isEqualTo(1);
        assertThat(((Number) rankRow("daily", DAY_KEY, 9).get("rank_no")).intValue()).isEqualTo(2);
    }

    // ==================== 用例 10/11：我的排名与空榜 ====================

    @Test
    void myRankOnlyForListedUsers() {
        reset();
        user(1); user(2);
        post(11, 1, 1, DAY);
        post(21, 2, 1, DAY);
        service.settle(ForumRankService.DAILY, DAY_KEY);

        Map<String, Object> board = service.board(ForumRankService.DAILY, 2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) board.get("me");
        assertThat(me.get("listed")).isEqualTo(true);
        assertThat(((Number) me.get("rankNo")).intValue()).isEqualTo(2);

        // 没上榜的人：不编造名次，前端按 listed=false 显示「你还没上榜」
        @SuppressWarnings("unchecked")
        Map<String, Object> outsider = (Map<String, Object>) service.board(ForumRankService.DAILY, 777L).get("me");
        assertThat(outsider.get("listed")).isEqualTo(false);

        // 未登录 → me 为 null（榜单本身仍然可看）
        assertThat(service.board(ForumRankService.DAILY, null).get("me")).isNull();
    }

    @Test
    void emptyBoardReturnsEmptyRowsAndNotSettled() {
        reset();
        Map<String, Object> board = service.board(ForumRankService.DAILY, null);

        assertThat((List<?>) board.get("rows")).isEmpty();
        assertThat(board.get("settled")).isEqualTo(false);
        assertThat(board.get("periodKey")).isEqualTo(LocalDate.now(ForumRankService.BEIJING).toString());
    }

    @Test
    void unsettledDayFallsBackToThePreviousSnapshot() {
        reset();
        user(1);
        post(11, 1, 1, DAY);
        service.settle(ForumRankService.DAILY, DAY_KEY);

        // 今天还没结算 → 展示最近一期，settled=false
        Map<String, Object> board = service.board(ForumRankService.DAILY, 1L);
        assertThat(board.get("periodKey")).isEqualTo(DAY_KEY);
        assertThat(board.get("settled")).isEqualTo(false);
        assertThat((List<?>) board.get("rows")).hasSize(1);
    }

    // ==================== 通知 ====================

    @Test
    void winnerGetsOneNotificationEvenAfterResettle() {
        reset();
        user(501);
        post(5010, 501, 1, DAY);
        service.resettle(ForumRankService.DAILY, DAY_KEY);
        service.resettle(ForumRankService.DAILY, DAY_KEY);

        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id=501")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT title, content FROM notification WHERE user_id=501");
        assertThat(String.valueOf(row.get("title"))).contains("日榜第 1 名");
        assertThat(String.valueOf(row.get("content"))).contains("+10 积分已到账");
    }

    // ==================== 调度 / 租约锁 ====================

    @Test
    void scheduledSettleRespectsClusterLock() {
        reset();
        user(601);
        // 昨天发的帖：定时任务结算的是「昨天」
        post(6010, 601, 1, LocalDate.now(ForumRankService.BEIJING).minusDays(1).atTime(10, 0));
        String yesterdayKey = ForumRankService.periodKeyOf(ForumRankService.DAILY,
            LocalDate.now(ForumRankService.BEIJING).minusDays(1));

        ClusterLock deny = new ClusterLock() {
            @Override public boolean tryLock(String name, Duration lease) { return false; }
            @Override public void unlock(String name) { }
        };
        new ForumRankService(rankMapper, points, deny).scheduledSettle();
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE board='daily' AND period_key=?", yesterdayKey)).isZero();

        // 拿到租约的节点才真正跑
        service.scheduledSettle();
        assertThat(count("SELECT COUNT(*) FROM forum_rank WHERE board='daily' AND period_key=?", yesterdayKey)).isEqualTo(1);
    }

    // ==================== 参数校验 ====================

    @Test
    void rejectsUnknownBoardTypeAndBadPeriod() {
        reset();
        assertThatThrownBy(() -> service.board("monthly", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("daily 或 weekly");
        assertThatThrownBy(() -> service.resettle(ForumRankService.DAILY, "2026-13-01"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resettle(ForumRankService.WEEKLY, "2026-45"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("yyyy-Www");
    }

    private static final String[] TABLES = {
        """
        CREATE TABLE `user` (
          id BIGINT PRIMARY KEY, nickname VARCHAR(32) NOT NULL, avatar VARCHAR(255) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE post (
          id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(64) NOT NULL DEFAULT '',
          content TEXT NOT NULL, status TINYINT NOT NULL DEFAULT 1,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE post_like (
          id BIGINT PRIMARY KEY AUTO_INCREMENT, post_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_post_user (post_id, user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE `comment` (
          id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
          content VARCHAR(255) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE comment_like (
          id BIGINT PRIMARY KEY AUTO_INCREMENT, comment_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_comment_user (comment_id, user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE forum_rank (
          id BIGINT PRIMARY KEY AUTO_INCREMENT, board VARCHAR(8) NOT NULL, period_key VARCHAR(16) NOT NULL,
          user_id BIGINT NOT NULL, score INT NOT NULL, rank_no INT NOT NULL,
          awarded TINYINT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          UNIQUE KEY uk_rank (board, period_key, user_id),
          KEY idx_rank_period (board, period_key, rank_no)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_points (
          user_id BIGINT PRIMARY KEY, balance INT NOT NULL DEFAULT 0, total_earned INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE point_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, amount INT NOT NULL,
          type VARCHAR(32) NOT NULL, ref_id BIGINT NULL, remark VARCHAR(100) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          module VARCHAR(16) NOT NULL DEFAULT 'pet', biz_key VARCHAR(64) NULL,
          g_amount INT NOT NULL DEFAULT 0, balance_after INT NULL,
          KEY idx_user_time (user_id, created_at), UNIQUE KEY uk_user_biz (user_id, biz_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_growth (
          user_id BIGINT PRIMARY KEY, g_total INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_catalog (
          task_key VARCHAR(32) PRIMARY KEY, module VARCHAR(16) NOT NULL, name VARCHAR(32) NOT NULL,
          p_reward INT NOT NULL DEFAULT 0, g_reward INT NOT NULL DEFAULT 0,
          daily_cap INT NOT NULL DEFAULT 1, enabled TINYINT NOT NULL DEFAULT 1, sort INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, task_key VARCHAR(32) NOT NULL,
          biz_date DATE NOT NULL, biz_key VARCHAR(64) NOT NULL, p INT NOT NULL DEFAULT 0, g INT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_task_idem (user_id, task_key, biz_key), KEY idx_task_day (user_id, task_key, biz_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}