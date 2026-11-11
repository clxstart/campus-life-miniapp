package com.campuslife.service;

import com.campuslife.mapper.PetCompanionMapper;
import com.campuslife.mapper.PetMapper;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 宠物积分回归（F1 设计稿用例 8 / F6 设计稿用例 12）。
 *
 * 这轮改造把宠物的四处加减分（签到加分、喂食扣分、衣柜兑换扣分、派遣奖励）
 * 全部改成走 PointsService。回归要证明的三件事：
 *   1. 积分仍由 user_points 唯一账本变动，且流水 module='pet'（能和其它域区分）；
 *   2. 衣柜兑换仍正常扣分并写 pet_item_owned（不能扣了分不到货、也不能到货没扣分）；
 *   3. 同一件衣服重复兑换、余额不足兑换这两条边界仍然拦住。
 *
 * 用真实 MySQL 临时 schema + 真 mapper，不 mock 余额。
 */
class PetPointsRegressionIntegrationTest {

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static PointsService points;
    private static PetService pet;
    private static PetCompanionService companion;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        // 宠物签到走统一任务体系：p=5、日限 1；提现用的播种任务 p=100
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,1,?)",
            "pet_checkin", "pet", "宠物签到", 5, 0, 1, 80);
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,1,?)",
            "seed_balance", "points", "测试播种", 100, 0, 5, 90);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("pet-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(PointsMapper.class);
        configuration.addMapper(PetMapper.class);
        configuration.addMapper(PetCompanionMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        points.evictCatalog();
        pet = new PetService(template.getMapper(PetMapper.class), points);
        companion = new PetCompanionService(template.getMapper(PetCompanionMapper.class),
            template.getMapper(PetMapper.class), points);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    // ==================== 工具 ====================

    private static void cleanup(long uid) {
        for (String table : List.of("user_pet", "pet_interaction_log", "user_points", "user_growth",
            "point_log", "task_log", "pet_item_owned")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", uid);
        }
        points.evictCatalog();
    }

    private static int balance(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, uid);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static void seedBalance(long uid, int amount) {
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,?,?) ON DUPLICATE KEY UPDATE balance=VALUES(balance)",
            uid, amount, amount);
    }

    private static int petLogCount(long uid, String type) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_log WHERE user_id=? AND module='pet' AND type=?", Integer.class, uid, type);
    }

    private static int ownedCount(long uid, long itemId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM pet_item_owned WHERE user_id=? AND item_id=?", Integer.class, uid, itemId);
    }

    // ==================== 用例 ====================

    /** F1 用例 8：宠物签到仍 +5，流水 module='pet'，且一天只能签一次。 */
    @Test
    void petCheckinStillAwardsPointsThroughUnifiedLedger() {
        long uid = 7_300_001L;
        cleanup(uid);
        pet.petOf(uid); // 建宠物 + 建积分行

        Map<String, Object> after = pet.interact(uid, "checkin");

        assertThat(balance(uid)).isEqualTo(5);
        assertThat(petLogCount(uid, "pet_checkin")).isEqualTo(1);
        Integer amount = jdbc.queryForObject(
            "SELECT amount FROM point_log WHERE user_id=? AND module='pet' AND type='pet_checkin'", Integer.class, uid);
        assertThat(amount).isEqualTo(5);
        // 统一任务体系里也要有事实行（日上限计数靠它）
        Integer taskRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key='pet_checkin'", Integer.class, uid);
        assertThat(taskRows).isEqualTo(1);
        assertThat(after.get("points")).isEqualTo(5);

        // 同一天再签：被日上限挡住，不重复加分
        assertThatThrownBy(() -> pet.interact(uid, "checkin"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("太多次");
        assertThat(balance(uid)).isEqualTo(5);
    }

    /** F1 用例 8 的下半句：喂食仍正常扣分，流水 module='pet'，余额不会被扣成负数。 */
    @Test
    void petFeedStillDeductsPointsThroughUnifiedLedger() {
        long uid = 7_300_002L;
        cleanup(uid);
        pet.petOf(uid);
        seedBalance(uid, 20);

        pet.interact(uid, "feed");

        assertThat(balance(uid)).isEqualTo(10);
        assertThat(petLogCount(uid, "pet_feed")).isEqualTo(1);
        Integer amount = jdbc.queryForObject(
            "SELECT amount FROM point_log WHERE user_id=? AND module='pet' AND type='pet_feed'", Integer.class, uid);
        assertThat(amount).isEqualTo(-10);
        Integer paid = jdbc.queryForObject(
            "SELECT cost_points FROM pet_interaction_log WHERE user_id=? AND action='feed'", Integer.class, uid);
        assertThat(paid).isEqualTo(10);
    }

    /** F6 用例 12：衣柜兑换仍扣分、写 pet_item_owned，流水 module='pet'；重复兑换不再扣分。 */
    @Test
    void wardrobePurchaseStillDeductsPointsAndRecordsOwnership() {
        long uid = 7_300_003L;
        cleanup(uid);
        pet.petOf(uid);
        seedBalance(uid, 100);
        long itemId = seedItem("scarf", "格纹围巾", "accessory", 30);

        Map<String, Object> result = companion.purchase(uid, "scarf");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("balance")).isEqualTo(70);
        assertThat(balance(uid)).isEqualTo(70);
        assertThat(ownedCount(uid, itemId)).isEqualTo(1);
        Integer spend = jdbc.queryForObject(
            "SELECT amount FROM point_log WHERE user_id=? AND module='pet' AND type='pet_wardrobe'", Integer.class, uid);
        assertThat(spend).isEqualTo(-30);

        // 已经拥有：拒绝，余额与拥有记录都不变
        assertThatThrownBy(() -> companion.purchase(uid, "scarf"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("已经拥有");
        assertThat(balance(uid)).isEqualTo(70);
        assertThat(ownedCount(uid, itemId)).isEqualTo(1);
    }

    /** 余额不足：明确报差多少分，不扣分、不到货、不写兑换流水。 */
    @Test
    void wardrobePurchaseRejectedWhenBalanceIsInsufficient() {
        long uid = 7_300_004L;
        cleanup(uid);
        pet.petOf(uid);
        seedBalance(uid, 25);
        long itemId = seedItem("glasses", "圆框眼镜", "accessory", 30);

        assertThatThrownBy(() -> companion.purchase(uid, "glasses"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("积分不足");

        assertThat(balance(uid)).isEqualTo(25);
        assertThat(ownedCount(uid, itemId)).isZero();
        assertThat(petLogCount(uid, "pet_wardrobe")).isZero();
    }

    private static long seedItem(String code, String name, String category, int price) {
        jdbc.update("""
            INSERT INTO pet_item(code,name,category,part_id,price,description,sort_num,status)
            VALUES(?,?,?,?,?,'',1,1)
            """, code, name, category, code, price);
        return jdbc.queryForObject("SELECT id FROM pet_item WHERE code=?", Long.class, code);
    }

    // ==================== 建表 ====================

    private static final String[] TABLES = {
        """
        CREATE TABLE user_pet (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          name VARCHAR(20) DEFAULT '小星',
          level INT DEFAULT 1,
          exp INT DEFAULT 0,
          hunger INT DEFAULT 80,
          mood INT DEFAULT 80,
          last_feed_at DATETIME NULL,
          last_touch_at DATETIME NULL,
          last_decay_at DATETIME NULL,
          species VARCHAR(16) DEFAULT 'cat',
          tint VARCHAR(16) DEFAULT 'natural',
          accessory VARCHAR(16) DEFAULT 'scarf',
          mood_tag VARCHAR(16) DEFAULT 'calm',
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          UNIQUE KEY uk_user (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE pet_interaction_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          action VARCHAR(16) NOT NULL,
          exp_gain INT DEFAULT 0,
          cost_points INT DEFAULT 0,
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          KEY idx_user_time (user_id, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE pet_item (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          code VARCHAR(32) NOT NULL,
          name VARCHAR(32) NOT NULL,
          category VARCHAR(16) NOT NULL DEFAULT 'accessory',
          part_id VARCHAR(32) NOT NULL DEFAULT '',
          price INT NOT NULL DEFAULT 0,
          description VARCHAR(255) NOT NULL DEFAULT '',
          sort_num INT NOT NULL DEFAULT 0,
          status TINYINT NOT NULL DEFAULT 1,
          UNIQUE KEY uk_item_code (code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE pet_item_owned (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          item_id BIGINT NOT NULL,
          equipped TINYINT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_user_item (user_id, item_id)
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