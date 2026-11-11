package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.AdminModuleMapper;
import com.campuslife.mapper.CampusMapper;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 建圈审核的真实 MySQL 集成测试（设计稿用例 10）。
 *
 * 覆盖「管理端通过/驳回 → 库里的状态迁移 + 站内通知」，以及三条边界：
 *   - 驳回不填原因必须被拦，且不落库；
 *   - 校管不能审别校圈子；
 *   - 通知写失败不影响审核结果（best-effort，靠数据库触发器模拟通知表异常）。
 *
 * 用临时 schema（MysqlIntegrationSupport），不连 campus_life。
 */
class CircleAuditIntegrationTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static AdminModuleService service;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        jdbc.execute("ALTER TABLE notification ADD COLUMN post_id BIGINT NULL");
        jdbc.execute(CIRCLE_TABLE);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("circle-audit-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(AdminModuleMapper.class);
        configuration.addMapper(CampusMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        service = new AdminModuleService(
            mock(AuditService.class),
            template.getMapper(AdminModuleMapper.class),
            template.getMapper(CampusMapper.class));
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static long seedCircle(long creatorId, long schoolId) {
        jdbc.update("""
            INSERT INTO circle(name,slug,category,description,school_id,status,creator_id,audit_status)
            VALUES(?,?,?,?,?,0,?,'pending')
            """, "夜跑团", "u" + creatorId + "-" + System.nanoTime(), "运动", "晚上一起跑步", schoolId, creatorId);
        return jdbc.queryForObject("SELECT MAX(id) FROM circle", Long.class);
    }

    private static Map<String, Object> circleRow(long id) {
        return jdbc.queryForMap("SELECT status, audit_status, audit_reason FROM circle WHERE id=?", id);
    }

    private static List<Map<String, Object>> notifications(long userId) {
        return jdbc.queryForList("SELECT title, content, category, post_id FROM notification WHERE user_id=?", userId);
    }

    // ==================== 用例 ====================

    /** 通过：status=1、audit_status='passed'，创建者收到「圈子审核通过」，并且能在列表 SQL 里查到。 */
    @Test
    void approvingCircleMakesItVisibleAndNotifiesCreator() {
        long creator = 7_200_001L;
        long circleId = seedCircle(creator, 5L);

        db.transactionTemplate.execute(s -> { service.setCircleStatus(null, circleId, 1, null); return null; });

        Map<String, Object> row = circleRow(circleId);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(1);
        assertThat(row.get("audit_status")).isEqualTo("passed");
        assertThat(String.valueOf(row.get("audit_reason"))).isEmpty();

        List<Map<String, Object>> notices = notifications(creator);
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("title")).isEqualTo("圈子审核通过");
        assertThat(String.valueOf(notices.get(0).get("content"))).contains("夜跑团").contains("可以在圈子列表看到");
        assertThat(notices.get(0).get("category")).isEqualTo("circle");
        assertThat(((Number) notices.get(0).get("post_id")).longValue()).isEqualTo(circleId);

        // 列表可见性口径：管理端「待审核」筛选 status=0 不再包含它
        Integer pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM circle WHERE id=? AND status=0", Integer.class, circleId);
        assertThat(pending).isZero();
    }

    /** 驳回：必须带原因；原因落库并原样进通知。 */
    @Test
    void rejectingCircleStoresReasonAndNotifiesCreator() {
        long creator = 7_200_002L;
        long circleId = seedCircle(creator, 5L);

        db.transactionTemplate.execute(s -> { service.setCircleStatus(5L, circleId, 0, "  名称含违规词  "); return null; });

        Map<String, Object> row = circleRow(circleId);
        assertThat(((Number) row.get("status")).intValue()).isZero();
        assertThat(row.get("audit_status")).isEqualTo("rejected");
        assertThat(row.get("audit_reason")).isEqualTo("名称含违规词");

        List<Map<String, Object>> notices = notifications(creator);
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("title")).isEqualTo("圈子审核未通过");
        assertThat(String.valueOf(notices.get(0).get("content"))).contains("名称含违规词");
    }

    /** 驳回不填原因：直接拒绝，状态与通知都不动。 */
    @Test
    void rejectingWithoutReasonIsBlockedAndLeavesCircleUntouched() {
        long creator = 7_200_003L;
        long circleId = seedCircle(creator, 5L);

        assertThatThrownBy(() -> db.transactionTemplate.execute(s -> {
            service.setCircleStatus(5L, circleId, 0, "   ");
            return null;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("请填写驳回原因");

        assertThat(((Number) circleRow(circleId).get("status")).intValue()).isZero();
        assertThat(circleRow(circleId).get("audit_status")).isEqualTo("pending");
        assertThat(notifications(creator)).isEmpty();
    }

    /** 校管只能审本校：跨校请求 403，且不落库、不发通知。 */
    @Test
    void schoolAdministratorCannotAuditAnotherSchool() {
        long creator = 7_200_004L;
        long circleId = seedCircle(creator, 5L);

        assertThatThrownBy(() -> db.transactionTemplate.execute(s -> {
            service.setCircleStatus(9L, circleId, 1, null);
            return null;
        })).isInstanceOf(ForbiddenException.class).hasMessageContaining("只能审核本校圈子");

        assertThat(((Number) circleRow(circleId).get("status")).intValue()).isZero();
        assertThat(notifications(creator)).isEmpty();
    }

    /** 通知失败不影响审核：触发器让 notification 插入报错，审核结果仍提交。 */
    @Test
    void notificationFailureDoesNotRollBackTheAudit() {
        long creator = 7_200_005L;
        long circleId = seedCircle(creator, 5L);

        jdbc.execute("""
            CREATE TRIGGER trg_notification_boom BEFORE INSERT ON notification
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'notification table is broken'
            """);
        try {
            db.transactionTemplate.execute(s -> { service.setCircleStatus(null, circleId, 1, null); return null; });
        } finally {
            jdbc.execute("DROP TRIGGER trg_notification_boom");
        }

        // 审核结果没有被通知失败拖回滚
        Map<String, Object> row = circleRow(circleId);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(1);
        assertThat(row.get("audit_status")).isEqualTo("passed");
        // 通知确实没写进去（证明我们走的是 catch 分支，不是「插入成功」）
        assertThat(notifications(creator)).isEmpty();
    }

    // ==================== 建表 ====================

    private static final String CIRCLE_TABLE = """
        CREATE TABLE circle (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          name VARCHAR(32) NOT NULL,
          slug VARCHAR(64) NOT NULL,
          category VARCHAR(16) NOT NULL DEFAULT '兴趣',
          description VARCHAR(255) NOT NULL DEFAULT '',
          cover VARCHAR(255) NULL,
          theme VARCHAR(16) NOT NULL DEFAULT 'blue',
          hot TINYINT NOT NULL DEFAULT 0,
          sort INT NOT NULL DEFAULT 0,
          school_id BIGINT NULL,
          status TINYINT NOT NULL DEFAULT 1,
          creator_id BIGINT NULL,
          member_count INT NOT NULL DEFAULT 0,
          post_count INT NOT NULL DEFAULT 0,
          audit_status VARCHAR(16) NOT NULL DEFAULT 'passed',
          audit_reason VARCHAR(200) NULL,
          audit_at DATETIME NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_circle_slug (slug)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
}