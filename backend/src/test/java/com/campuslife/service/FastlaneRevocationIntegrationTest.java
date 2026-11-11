package com.campuslife.service;

import com.campuslife.mapper.AuditMapper;
import com.campuslife.mapper.CampusMapper;
import com.campuslife.mapper.UserMapper;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 发帖免审（黄金特权）被异步审核撤回的真实 MySQL 集成测试（设计稿用例 5）。
 *
 * 为什么单独一个类：生产路径里阻塞的是微信 HTTP（取图、换 token），
 * 拿不到 87014 就永远测不到「命中后下架」。所以把「审核结论落地」抽成
 * AuditService#applyImageVerdict（生产 auditImages 循环与这里共用同一段代码），
 * 测试只喂结论，不动微信调用，也不 sleep 等线程。
 *
 * 断言三件事：
 *   1. 帖子 status=0、audit_status='rejected'（真正下架）；
 *   2. audit_log 出现 source='fastlane_revoked'（能区分「免审后被撤回」与普通违规）；
 *   3. 作者收到「帖子已下架」通知；
 * 外加反例：没走免审的帖子被撤时不得伪造 fastlane_revoked。
 */
class FastlaneRevocationIntegrationTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static AuditService audit;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        // 脚手架自带 notification 表，但缺 post_id 列（生产表有）
        jdbc.execute("ALTER TABLE notification ADD COLUMN post_id BIGINT NULL");
        jdbc.execute(POST_TABLE);
        jdbc.execute(AUDIT_LOG_TABLE);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("fastlane-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(AuditMapper.class);
        configuration.addMapper(CampusMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        audit = new AuditService(
            template.getMapper(AuditMapper.class),
            mock(UserMapper.class),
            template.getMapper(CampusMapper.class),
            mock(UploadService.class),
            mock(PlatformTransactionManager.class),
            mock(Executor.class),
            mock(SensitiveWordEngine.class));
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    private static long seedPost(long userId, int status, String auditStatus) {
        jdbc.update("INSERT INTO post(user_id,title,content,image,status,audit_status) VALUES(?,?,?,?,?,?)",
            userId, "带图帖", "正文", "/uploads/a.jpg", status, auditStatus);
        return jdbc.queryForObject("SELECT MAX(id) FROM post", Long.class);
    }

    private static void seedFastlaneLog(long postId, long userId) {
        jdbc.update("""
            INSERT INTO audit_log(target_type,target_id,user_id,content_snippet,source,result,matched_words)
            VALUES('post',?,?,'带图帖','fastlane','pass','[]')
            """, postId, userId);
    }

    private static int postStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM post WHERE id=?", Integer.class, id);
    }

    private static String postAuditStatus(long id) {
        return jdbc.queryForObject("SELECT audit_status FROM post WHERE id=?", String.class, id);
    }

    private static int logCount(long postId, String source) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE target_type='post' AND target_id=? AND source=?",
            Integer.class, postId, source);
    }

    private static List<Map<String, Object>> notifications(long userId) {
        return jdbc.queryForList("SELECT title,content,category,post_id FROM notification WHERE user_id=?", userId);
    }

    // ==================== 用例 ====================

    /** 用例 5：黄金免审发带图帖 → 图片审核命中 87014 → 立即下架 + fastlane_revoked 留痕 + 通知作者。 */
    @Test
    void fastlaneImageHitTakesPostDownLogsRevocationAndNotifiesAuthor() {
        long userId = 7_100_001L;
        long postId = seedPost(userId, 1, "pending");
        seedFastlaneLog(postId, userId);

        boolean keepGoing = audit.applyImageVerdict("post", postId, userId, "/uploads/a.jpg", 87014);

        assertThat(keepGoing).isFalse(); // 命中即停，不再检查后面的图
        assertThat(postStatus(postId)).isZero();
        assertThat(postAuditStatus(postId)).isEqualTo("rejected");
        assertThat(logCount(postId, "fastlane")).isEqualTo(1);        // 上线时的留痕还在
        assertThat(logCount(postId, "fastlane_revoked")).isEqualTo(1); // 撤回留痕
        assertThat(logCount(postId, "wechat")).isEqualTo(1);          // 微信判定本身也有日志
        List<Map<String, Object>> rows = notifications(userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("帖子已下架");
        assertThat(String.valueOf(rows.get(0).get("content"))).contains("违反社区规范");
    }

    /** 反例：没走免审的帖子被图片审核撤回时，只下架，不得伪造 fastlane_revoked。 */
    @Test
    void normalPostImageHitTakesDownWithoutFakeFastlaneRevocation() {
        long userId = 7_100_002L;
        long postId = seedPost(userId, 1, "pending");

        boolean keepGoing = audit.applyImageVerdict("post", postId, userId, "/uploads/b.jpg", 87014);

        assertThat(keepGoing).isFalse();
        assertThat(postStatus(postId)).isZero();
        assertThat(logCount(postId, "fastlane_revoked")).isZero();
        assertThat(logCount(postId, "wechat")).isEqualTo(1);
        assertThat(notifications(userId)).hasSize(1);
    }

    /** 通过（errcode=0）：不动帖子状态，不写撤回日志。 */
    @Test
    void passingImageVerdictLeavesPostVisible() {
        long userId = 7_100_003L;
        long postId = seedPost(userId, 1, "pending");
        seedFastlaneLog(postId, userId);

        boolean keepGoing = audit.applyImageVerdict("post", postId, userId, "/uploads/c.jpg", 0);

        assertThat(keepGoing).isTrue();
        assertThat(postStatus(postId)).isEqualTo(1);
        assertThat(postAuditStatus(postId)).isEqualTo("pending");
        assertThat(logCount(postId, "fastlane_revoked")).isZero();
        assertThat(notifications(userId)).isEmpty();
    }

    /** 微信返回未知错误：落地层不动数据（生产循环会抛错并走「降级放行」分支）。 */
    @Test
    void unknownErrcodeDoesNotTouchThePost() {
        long userId = 7_100_004L;
        long postId = seedPost(userId, 1, "pending");

        boolean keepGoing = audit.applyImageVerdict("post", postId, userId, "/uploads/d.jpg", -1);

        assertThat(keepGoing).isTrue();
        assertThat(postStatus(postId)).isEqualTo(1);
        assertThat(logCount(postId, "wechat")).isZero();
        assertThat(notifications(userId)).isEmpty();
    }

    // ==================== 建表 ====================

    private static final String POST_TABLE = """
        CREATE TABLE post (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          title VARCHAR(64) NOT NULL,
          content TEXT NOT NULL,
          image VARCHAR(255),
          tag VARCHAR(16),
          location VARCHAR(64),
          like_count INT NOT NULL DEFAULT 0,
          comment_count INT NOT NULL DEFAULT 0,
          view_count INT NOT NULL DEFAULT 0,
          status TINYINT NOT NULL DEFAULT 1,
          audit_status VARCHAR(16) NOT NULL DEFAULT 'pending',
          audit_reason VARCHAR(200) NULL,
          audit_at DATETIME NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    private static final String AUDIT_LOG_TABLE = """
        CREATE TABLE audit_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          user_id BIGINT NULL,
          content_snippet VARCHAR(200) NULL,
          source VARCHAR(16) NOT NULL,
          result VARCHAR(16) NOT NULL,
          matched_words VARCHAR(200) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_target(target_type,target_id),
          KEY idx_created(created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
}