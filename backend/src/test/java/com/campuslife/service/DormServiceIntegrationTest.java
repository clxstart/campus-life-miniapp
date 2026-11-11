package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.ha.LocalClusterLock;
import com.campuslife.mapper.DormMapper;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 宿舍域的真实 MySQL 集成测试。
 *
 * 覆盖设计稿第六节 15 条用例中可自动化的部分：
 *   建宿 / 未认证 / 重复建宿 / 邀请码加入 / 错误邀请码 / 满员 / 一人一宿舍 /
 *   全员签到 / 未全员不发放 / 重复回调不重复计数 / 中途退出当日不补发 /
 *   周榜冠军与并列 / 奶茶券库存不足降级 / 解散保留历史 / 加入前看不到楼栋房间。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 建临时 schema，结束即 DROP。
 * 未设置 MYSQL_TEST_URL / MYSQL_TEST_USER 时整组跳过（跳过即「未验证」，交付报告必须写明）。
 *
 * 认证状态用 Mockito 替换：本测试只验证宿舍域，认证本身有独立测试；
 * 但「未认证不能建宿」这条前置必须用桩验证，不能因为省事就不断言。
 */
class DormServiceIntegrationTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static DormService service;
    private static PointsService points;
    private static DormMapper dormMapper;
    private static VerificationService verification;

    private static final LocalDate TODAY = LocalDate.now(DormService.BEIJING);

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        seedTask("dorm_all_sign", "dorm", "宿舍全员签到", 5, 8, 1);
        seedTask("dorm_week_first", "points", "宿舍周榜第一", 0, 30, 1);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("dorm-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(DormMapper.class);
        configuration.addMapper(PointsMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        dormMapper = template.getMapper(DormMapper.class);
        verification = mock(VerificationService.class);
        when(verification.status(anyLong())).thenReturn(Map.of("verified", true));
        service = new DormService(dormMapper, points, verification, new LocalClusterLock());
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    @BeforeEach
    void reset() {
        when(verification.status(anyLong())).thenReturn(Map.of("verified", true));
        jdbc.update("DELETE FROM dorm_sign");
        jdbc.update("DELETE FROM dorm_member");
        jdbc.update("DELETE FROM dorm");
        jdbc.update("DELETE FROM sign_roll");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        jdbc.update("DELETE FROM coupon");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM reward_item");
        jdbc.update("DELETE FROM user");
        jdbc.update("DELETE FROM school");
        points.evictCatalog();
    }

    // ==================== 用例 1-7：建宿与加入 ====================

    @Test
    void createsDormWithSixCharCodeAndLeaderRole() {
        users(1);
        Map<String, Object> me = service.create(1L, "6号楼312", "6号楼", "312");

        assertThat(me.get("joined")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> dorm = (Map<String, Object>) me.get("dorm");
        assertThat(dorm.get("name")).isEqualTo("6号楼312");
        assertThat(String.valueOf(dorm.get("inviteCode"))).matches("[A-HJ-NP-Z2-9]{6}");
        assertThat(((Number) dorm.get("memberCount")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> self = (Map<String, Object>) me.get("me");
        assertThat(self.get("role")).isEqualTo("leader");
    }

    @Test
    void unverifiedUserCannotCreate() {
        users(1);
        when(verification.status(anyLong())).thenReturn(Map.of("verified", false));

        assertThatThrownBy(() -> service.create(1L, "6号楼312", "6号楼", "312"))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("学生认证");
        assertThat(count("SELECT COUNT(*) FROM dorm")).isZero();
    }

    @Test
    void duplicateCreateIsRejected() {
        users(1);
        service.create(1L, "A", "", "");
        assertThatThrownBy(() -> service.create(1L, "B", "", ""))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("你已在宿舍中");
    }

    @Test
    void joinsWithInviteCodeAndSeesTwoMembers() {
        users(1, 2);
        Map<String, Object> created = service.create(1L, "6号楼312", "6号楼", "312");
        String code = code(created);

        Map<String, Object> joined = service.join(2L, code.toLowerCase());
        assertThat(joined.get("joined")).isEqualTo(true);
        assertThat(count("SELECT COUNT(*) FROM dorm_member")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> dorm = (Map<String, Object>) joined.get("dorm");
        assertThat(((Number) dorm.get("memberCount")).intValue()).isEqualTo(2);
    }

    @Test
    void wrongInviteCodeIsRejected() {
        users(1, 2);
        service.create(1L, "A", "", "");
        assertThatThrownBy(() -> service.join(2L, "000000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("邀请码无效");
    }

    @Test
    void ninthMemberIsRejectedWhenFull() {
        users(1, 2, 3, 4, 5, 6, 7, 8, 9);
        String code = code(service.create(1L, "A", "", ""));
        for (long user = 2; user <= 8; user++) service.join(user, code);

        assertThat(count("SELECT COUNT(*) FROM dorm_member")).isEqualTo(8);
        assertThatThrownBy(() -> service.join(9L, code))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已满");
    }

    @Test
    void userCannotBeInTwoDorms() {
        users(1, 2, 3);
        String a = code(service.create(1L, "A", "", ""));
        String b = code(service.create(3L, "B", "", ""));
        service.join(2L, a);

        assertThatThrownBy(() -> service.join(2L, b))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("请先退出当前宿舍");
    }

    @Test
    void leaderCannotLeaveWhileOthersRemainButMemberCan() {
        users(1, 2);
        String code = code(service.create(1L, "A", "", ""));
        service.join(2L, code);

        assertThatThrownBy(() -> service.leave(1L))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("舍长退出");

        service.leave(2L);
        assertThat(count("SELECT COUNT(*) FROM dorm_member")).isEqualTo(1);

        // 只剩舍长一人：退出时空壳宿舍自动解散
        service.leave(1L);
        assertThat(count("SELECT COUNT(*) FROM dorm_member")).isZero();
        assertThat(count("SELECT COUNT(*) FROM dorm WHERE status=1")).isZero();
    }

    // ==================== 用例 8-11：全员签到 ====================

    @Test
    void everyoneSigningAwardsAllMembersOnce() {
        users(1, 2, 3, 4);
        String code = code(service.create(1L, "A", "", ""));
        for (long user = 2; user <= 4; user++) service.join(user, code);

        for (long user = 1; user <= 4; user++) {
            signFact(user, TODAY);
            service.onSigned(user, TODAY);
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dorm_sign");
        assertThat(((Number) row.get("signed_count")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("member_count")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("all_done")).intValue()).isEqualTo(1);

        for (long user = 1; user <= 4; user++) {
            assertThat(balance(user)).as("用户 %s 积分", user).isEqualTo(5);
            assertThat(growth(user)).as("用户 %s 成长值", user).isEqualTo(8);
        }
    }

    @Test
    void partialSigningAwardsNothing() {
        users(1, 2, 3, 4);
        String code = code(service.create(1L, "A", "", ""));
        for (long user = 2; user <= 4; user++) service.join(user, code);

        for (long user = 1; user <= 3; user++) {
            signFact(user, TODAY);
            service.onSigned(user, TODAY);
        }

        assertThat(count("SELECT COUNT(*) FROM user_points")).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dorm_sign");
        assertThat(((Number) row.get("signed_count")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("all_done")).intValue()).isZero();
    }

    @Test
    void duplicateCallbackDoesNotCountOrPayAgain() {
        users(1, 2);
        String code = code(service.create(1L, "A", "", ""));
        service.join(2L, code);

        for (long user = 1; user <= 2; user++) {
            signFact(user, TODAY);
            service.onSigned(user, TODAY);
        }
        // 重复回调（例如重放/并发重复通知）
        service.onSigned(1L, TODAY);
        service.onSigned(2L, TODAY);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dorm_sign");
        assertThat(((Number) row.get("signed_count")).intValue()).isEqualTo(2);
        // 每人只发一次，不能变成 16
        assertThat(growth(1L)).isEqualTo(8);
        assertThat(growth(2L)).isEqualTo(8);
    }

    @Test
    void memberLeavingAfterPartialSignDoesNotTriggerRetroactiveAward() {
        users(1, 2, 3, 4);
        String code = code(service.create(1L, "A", "", ""));
        for (long user = 2; user <= 4; user++) service.join(user, code);

        // 8 人场景缩成 4 人：先签 3 人（未全员），第 4 人退出
        for (long user = 1; user <= 3; user++) {
            signFact(user, TODAY);
            service.onSigned(user, TODAY);
        }
        service.leave(4L);
        // 成员数变了，但当日判定基数已冻结为 4，3/4 不算全员
        service.onSigned(3L, TODAY);
        assertThat(count("SELECT COUNT(*) FROM dorm_sign WHERE all_done=1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_points")).isZero();

        // 次日按新成员数（3 人）判定，全员到齐才发放
        LocalDate tomorrow = TODAY.plusDays(1);
        for (long user = 1; user <= 3; user++) {
            signFact(user, tomorrow);
            service.onSigned(user, tomorrow);
        }
        assertThat(growth(1L)).isEqualTo(8);
        assertThat(growth(3L)).isEqualTo(8);
    }

    @Test
    void concurrentLastSignerStillAwardsOncePerMember() throws Exception {
        users(1, 2);
        String code = code(service.create(1L, "A", "", ""));
        service.join(2L, code);
        signFact(1L, TODAY);
        service.onSigned(1L, TODAY);
        signFact(2L, TODAY);

        // 两个人同时收到「最后一名成员签到」的通知：条件更新只能让一个领到发分资格
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = List.of(
                () -> { service.onSigned(2L, TODAY); return null; },
                () -> { service.onSigned(2L, TODAY); return null; });
            for (Future<Void> future : pool.invokeAll(tasks)) future.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(growth(1L)).isEqualTo(8);
        assertThat(growth(2L)).isEqualTo(8);
    }

    // ==================== 用例 12-13：周榜与奶茶券 ====================

    @Test
    void weeklyChampionAwardsEveryMemberAndTieGoesToSmallerDormId() {
        users(1, 2, 3, 4);
        String a = code(service.create(1L, "A", "", ""));
        service.join(2L, a);
        String b = code(service.create(3L, "B", "", ""));
        service.join(4L, b);
        long dormA = dormIdOf(1L);
        long dormB = dormIdOf(3L);

        LocalDate monday = TODAY.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        taskFact(1L, monday, "p-1", 10);
        taskFact(3L, monday, "p-2", 10); // 并列：dorm_id 小者（A）应夺冠

        Map<String, Object> summary = service.settleWeek(monday);

        assertThat(((Number) summary.get("championDormId")).longValue()).isEqualTo(Math.min(dormA, dormB));
        assertThat(((Number) summary.get("awarded")).intValue()).isEqualTo(2);
        assertThat(growth(1L)).isEqualTo(30);
        assertThat(growth(2L)).isEqualTo(30);
        assertThat(growth(3L)).isZero();
        assertThat(growth(4L)).isZero();
    }

    @Test
    void weeklyChampionWithMilkTeaStockZeroOnlyGrantsGrowth() {
        users(1, 2);
        String a = code(service.create(1L, "A", "", ""));
        service.join(2L, a);
        // milk_tea 已配置但库存为 0（设计稿允许的降级路径）
        rewardItem("milk_tea", 0);

        LocalDate monday = DormService.mondayOf(TODAY.minusWeeks(1));
        taskFact(1L, monday, "p-1", 12);
        Map<String, Object> summary = service.settleWeek(monday);

        assertThat(((Number) summary.get("coupons")).intValue()).isZero();
        assertThat(growth(1L)).isEqualTo(30);
        assertThat(growth(2L)).isEqualTo(30);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isZero();
    }

    @Test
    void weeklyChampionGrantsMilkTeaCouponWhenStockAvailable() {
        users(1, 2);
        String a = code(service.create(1L, "A", "", ""));
        service.join(2L, a);
        rewardItem("milk_tea", 2);

        LocalDate monday = DormService.mondayOf(TODAY.minusWeeks(1));
        taskFact(1L, monday, "p-1", 12);
        Map<String, Object> summary = service.settleWeek(monday);

        assertThat(((Number) summary.get("coupons")).intValue()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isEqualTo(2);
        assertThat(stockOf("milk_tea")).isZero();
        // 券与成长值都到账
        assertThat(growth(1L)).isEqualTo(30);
    }

    // ==================== 用例 14-15：解散与隐私 ====================

    @Test
    void dismissRemovesMembersButKeepsSignHistory() {
        users(1, 2);
        String code = code(service.create(1L, "A", "", ""));
        service.join(2L, code);
        long dormId = dormIdOf(1L);
        signFact(1L, TODAY);
        service.onSigned(1L, TODAY);

        service.dismiss(dormId, 999L, "违规宿舍", null);

        assertThat(count("SELECT COUNT(*) FROM dorm WHERE status=0")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dorm_member")).isZero();
        assertThat(count("SELECT COUNT(*) FROM dorm_sign")).isEqualTo(1);
        assertThatThrownBy(() -> service.join(3L, code))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已解散");
    }

    @Test
    void nonMemberCannotSeeBuildingOrRoomBeforeJoining() {
        users(1, 2, 3);
        Map<String, Object> created = service.create(1L, "6号楼312", "6号楼", "312");
        String code = code(created);

        Map<String, Object> stranger = service.me(2L);
        assertThat(stranger.get("joined")).isEqualTo(false);
        assertThat(stranger.get("dorm")).isNull();

        // 邀请码查询只返回加入所需字段，不泄露楼栋/房间（隐私口径）
        Map<String, Object> lookup = dormMapper.dormByCode(code);
        assertThat(lookup).doesNotContainKeys("building", "room");
    }

    @Test
    void dissolvedDormNotifiesMembersAndDegradesToNotJoined() {
        users(1);
        service.create(1L, "A", "", "");
        long dormId = dormIdOf(1L);
        service.dismiss(dormId, 999L, "测试解散", null);

        // 成员关系已解除：/me 只剩「未加入」态（设计稿：解散后成员被移出）
        Map<String, Object> me = service.me(1L);
        assertThat(me.get("joined")).isEqualTo(false);
        assertThat(me.get("dorm")).isNull();

        // 「该宿舍已解散」通过通知告知（成员关系解除后页面无从自行判断）
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);
        String content = jdbc.queryForObject("SELECT content FROM notification", String.class);
        assertThat(content).contains("已解散");
    }

    @Test
    void adminListScopesSchoolAdministrator() {
        jdbc.update("INSERT INTO school(id,name) VALUES(1,'A大'),(2,'B大')");
        usersWithSchool(1L, 1L);
        usersWithSchool(2L, 2L);
        service.create(1L, "A宿舍", "", "");
        service.create(2L, "B宿舍", "", "");

        Map<String, Object> all = service.adminList(1, 20, null, null, null, null);
        assertThat(((Number) all.get("total")).longValue()).isEqualTo(2);

        Map<String, Object> scoped = service.adminList(1, 20, null, null, null, 1L);
        assertThat(((Number) scoped.get("total")).longValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) scoped.get("list");
        assertThat(list.get(0).get("name")).isEqualTo("A宿舍");

        assertThatThrownBy(() -> service.dismiss(dormIdOf(2L), 999L, "越权", 1L))
            .isInstanceOf(ForbiddenException.class);
    }

    // ==================== 工具 ====================

    private static void users(long... ids) {
        for (long id : ids) {
            jdbc.update("INSERT INTO user(id,nickname,avatar,school_id) VALUES(?,?,?,NULL)",
                id, "用户" + id, "");
        }
    }

    private static void usersWithSchool(long id, long schoolId) {
        jdbc.update("INSERT INTO user(id,nickname,avatar,school_id) VALUES(?,?,?,?)", id, "用户" + id, "", schoolId);
    }

    private static void signFact(long userId, LocalDate date) {
        jdbc.update("INSERT INTO sign_roll(user_id,biz_date,streak_days) VALUES(?,?,1) "
            + "ON DUPLICATE KEY UPDATE streak_days=streak_days", userId, date);
    }

    private static void taskFact(long userId, LocalDate date, String bizKey, int p) {
        jdbc.update("INSERT INTO task_log(user_id,task_key,biz_date,biz_key,p,g) VALUES(?,?,?,?,?,0)",
            userId, "seed_task", date, bizKey, p);
    }

    private static void rewardItem(String key, int stock) {
        jdbc.update("""
            INSERT INTO reward_item(item_key,name,zone,price_p,cash_cents,stock,per_user_limit,
              coupon_title,coupon_amount,coupon_expire,expire_days,enabled,sort)
            VALUES(?,?,'limited',0,0,?,1,'奶茶券','1 杯','30天',30,1,90)
            """, key, "宿舍冠军奶茶券", stock);
    }

    private static void seedTask(String key, String module, String name, int p, int g, int cap) {
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) "
            + "VALUES(?,?,?,?,?,?,1,0)", key, module, name, p, g, cap);
    }

    private static String code(Map<String, Object> me) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dorm = (Map<String, Object>) me.get("dorm");
        return String.valueOf(dorm.get("inviteCode"));
    }

    private static long dormIdOf(long userId) {
        Long id = jdbc.queryForObject("SELECT dorm_id FROM dorm_member WHERE user_id=?", Long.class, userId);
        return id == null ? -1 : id;
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
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static int stockOf(String itemKey) {
        List<Integer> v = jdbc.queryForList("SELECT stock FROM reward_item WHERE item_key=?", Integer.class, itemKey);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static final String[] TABLES = {
        """
        CREATE TABLE user (
          id BIGINT PRIMARY KEY,
          nickname VARCHAR(64) NULL,
          avatar VARCHAR(255) NULL,
          school_id BIGINT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE school (
          id BIGINT PRIMARY KEY,
          name VARCHAR(64) NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE dorm (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          school_id BIGINT NULL,
          name VARCHAR(32) NOT NULL,
          building VARCHAR(32) NOT NULL DEFAULT '',
          room VARCHAR(16) NOT NULL DEFAULT '',
          leader_id BIGINT NOT NULL,
          invite_code CHAR(6) NOT NULL,
          member_limit TINYINT NOT NULL DEFAULT 8,
          status TINYINT NOT NULL DEFAULT 1,
          dismissed_by BIGINT NULL,
          dismissed_at DATETIME NULL,
          dismiss_reason VARCHAR(200) NOT NULL DEFAULT '',
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_dorm_code (invite_code),
          KEY idx_dorm_school (school_id, status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE dorm_member (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          dorm_id BIGINT NOT NULL,
          user_id BIGINT NOT NULL,
          role VARCHAR(16) NOT NULL DEFAULT 'member',
          joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_dorm_user (user_id),
          KEY idx_member_dorm (dorm_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE dorm_sign (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          dorm_id BIGINT NOT NULL,
          biz_date DATE NOT NULL,
          signed_count INT NOT NULL DEFAULT 0,
          member_count INT NOT NULL DEFAULT 0,
          all_done TINYINT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_dorm_sign (dorm_id, biz_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE sign_roll (
          user_id BIGINT NOT NULL,
          biz_date DATE NOT NULL,
          streak_days INT NOT NULL DEFAULT 1,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id, biz_date),
          KEY idx_sign_streak (biz_date, streak_days)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE reward_item (
          item_key VARCHAR(32) PRIMARY KEY,
          name VARCHAR(32) NOT NULL,
          zone VARCHAR(16) NOT NULL DEFAULT 'virtual',
          price_p INT NOT NULL,
          cash_cents INT NOT NULL DEFAULT 0,
          stock INT NOT NULL DEFAULT 0,
          per_user_limit INT NOT NULL DEFAULT 1,
          coupon_title VARCHAR(64) NOT NULL,
          coupon_amount VARCHAR(16) NOT NULL DEFAULT '',
          coupon_expire VARCHAR(16) NOT NULL DEFAULT '',
          threshold_cents INT NOT NULL DEFAULT 0,
          expire_days INT NOT NULL DEFAULT 30,
          enabled TINYINT NOT NULL DEFAULT 1,
          sort INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE coupon (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          title VARCHAR(64) NOT NULL,
          amount VARCHAR(16) DEFAULT '',
          expire_at VARCHAR(16) DEFAULT '',
          status TINYINT NOT NULL DEFAULT 1,
          value_cents INT NOT NULL DEFAULT 0,
          threshold_cents INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
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