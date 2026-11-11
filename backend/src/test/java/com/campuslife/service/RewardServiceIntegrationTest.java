package com.campuslife.service;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.campuslife.entity.Coupon;
import com.campuslife.mapper.CouponMapper;
import com.campuslife.mapper.PointsMapper;
import com.campuslife.mapper.RewardMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新人礼包与积分兑换（功能 6）的真实 MySQL 集成测试。
 *
 * 覆盖设计稿第六节里可自动化的部分：兑换成功（扣分/发券/减库存/写流水）、积分不足整体回滚、
 * 售罄与并发售罄、并发不超扣、限购、下架、券面额 1:1、新人礼包幂等、券的可用/锁定/核销/释放/
 * 作废三态与门槛、过期券过滤、可用券列表顺序。
 *
 * 为什么必须用真库：这一层的正确性全压在数据库约束与事务上 —— 库存的 `WHERE stock > 0` 条件更新、
 * 扣分的 `WHERE balance >= ?` 条件更新、`point_log(user_id,biz_key)` 唯一键、整笔兑换的回滚。
 * 用 Mockito 打桩一个都测不出来。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 建临时 schema（campus_life_it_&lt;随机&gt;），结束即 DROP。
 * 未设置 MYSQL_TEST_URL 时整组跳过（跳过即「未验证」，报告必须写清）。
 */
class RewardServiceIntegrationTest {

    private static final long ME = 990101L;
    private static final String ITEM = "takeout_0_1";     // 10P / 库存 500 / 限购 10 / 门槛 1000 分

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static RewardService service;
    private static CouponService coupons;
    private static PointsService points;
    private static CouponMapper couponMapper;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        // 与 db/module-points-v31.sql 的种子一致：新人礼包 30P、日限 1 次
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) "
            + "VALUES('newbie_gift','points','新人礼包',30,0,1,1,5)");

        // MyBatis-Plus 的 BaseMapper（CouponMapper）必须走 MybatisConfiguration 才会注入 CRUD，
        // 普通 Configuration + addMapper 只会注册接口上的注解语句。id 策略对齐 application.yml 的 id-type: auto。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getDbConfig(configuration).setIdType(IdType.AUTO);
        configuration.setEnvironment(new Environment("reward-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(RewardMapper.class);
        configuration.addMapper(PointsMapper.class);
        configuration.addMapper(CouponMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        couponMapper = template.getMapper(CouponMapper.class);
        coupons = new CouponService(couponMapper);
        service = new RewardService(template.getMapper(RewardMapper.class), couponMapper, points);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    // ==================== 用例 ====================

    /** 设计稿 #4 兑换成功 + #11 流水一致 + #19 面额一致性：余额 30 换 10P 商品。 */
    @Test
    void redeemSuccessIssuesCouponDeductsPointsAndStock() {
        reset(30);
        Map<String, Object> result = tx(() -> service.redeem(ME, ITEM));

        assertThat(balance()).isEqualTo(20);
        assertThat(result.get("balance")).isEqualTo(20);
        assertThat(stock(ITEM)).isEqualTo(499);
        assertThat(count("SELECT COUNT(*) FROM redeem_record")).isEqualTo(1);
        assertThat(result.get("valueYuan")).isEqualTo("0.20");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.get("coupon");
        long couponId = ((Number) out.get("id")).longValue();
        assertThat(out.get("status")).isEqualTo(1);
        // 面额严格 1P = 1 分，不允许超过积分价值（设计稿 #19）
        assertThat(out.get("valueCents")).isEqualTo(10);
        assertThat(out.get("thresholdCents")).isEqualTo(1000);

        Coupon coupon = couponMapper.selectById(couponId);
        assertThat(coupon).isNotNull();
        assertThat(coupon.getUserId()).isEqualTo(ME);
        assertThat(coupon.getValueCents()).isEqualTo(10);
        assertThat(coupon.getSource()).isEqualTo("redeem");
        assertThat(coupon.getStatus()).isEqualTo(1);
        assertThat(coupon.getExpireOn()).isAfter(LocalDate.now().minusDays(1));
        // 券列表里立刻可见
        assertThat(coupons.available(ME)).extracting(Coupon::getId).contains(couponId);

        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", ME);
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(-10);
        assertThat(((Number) log.get("balance_after")).intValue()).isEqualTo(20);
        assertThat(log.get("module")).isEqualTo("redeem");
        assertThat(String.valueOf(log.get("biz_key"))).startsWith("redeem:");
    }

    /** 设计稿 #5 积分不足：400「积分不足」，余额不变，不生成券、不扣库存（整笔回滚）。 */
    @Test
    void insufficientBalanceRollsBackEverything() {
        reset(5);
        assertThatThrownBy(() -> tx(() -> service.redeem(ME, ITEM)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("积分不足");

        assertThat(balance()).isEqualTo(5);
        assertThat(stock(ITEM)).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isZero();
        assertThat(count("SELECT COUNT(*) FROM redeem_record")).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_log")).isZero();
    }

    /** 设计稿 #6 售罄（库存为 0）：直接拒绝，任何副作用都不产生。 */
    @Test
    void soldOutItemIsRejected() {
        reset(100);
        jdbc.update("UPDATE reward_item SET stock=0 WHERE item_key=?", ITEM);

        assertThatThrownBy(() -> tx(() -> service.redeem(ME, ITEM)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已兑完");

        assertThat(balance()).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isZero();
        assertThat(count("SELECT COUNT(*) FROM redeem_record")).isZero();
    }

    /** 设计稿 #6 并发售罄：库存 1，两人并发兑换，只有 1 人成功。 */
    @Test
    void concurrentSoldOutOnlyOneWinner() throws Exception {
        reset(100);
        jdbc.update("UPDATE reward_item SET stock=1 WHERE item_key=?", ITEM);
        long other = ME + 1;
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,100,0)", other);

        List<Callable<Boolean>> jobs = new ArrayList<>();
        jobs.add(redeemAttempt(ME, ITEM));
        jobs.add(redeemAttempt(other, ITEM));
        assertThat(successes(jobs)).isEqualTo(1);

        assertThat(stock(ITEM)).isZero();
        assertThat(count("SELECT COUNT(*) FROM coupon")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM redeem_record")).isEqualTo(1);
        // 胜者是谁不确定（ME 或 other 都可能抢到），但只能有一位被扣 10 分
        Integer meLeft = jdbc.queryForObject("SELECT balance FROM user_points WHERE user_id=?", Integer.class, ME);
        Integer otherLeft = jdbc.queryForObject("SELECT balance FROM user_points WHERE user_id=?", Integer.class, other);
        assertThat((meLeft == null ? 0 : meLeft) + (otherLeft == null ? 0 : otherLeft)).isEqualTo(190);
        assertThat(Math.min(meLeft == null ? 0 : meLeft, otherLeft == null ? 0 : otherLeft)).isEqualTo(90);
        assertThat(Math.max(meLeft == null ? 0 : meLeft, otherLeft == null ? 0 : otherLeft)).isEqualTo(100);
    }

    /** 设计稿 #7 并发超扣：余额 10，并发 5 次兑换 10P，只成功 1 次，余额 0。 */
    @Test
    void concurrentRedeemNeverOverdraws() throws Exception {
        reset(10);
        jdbc.update("UPDATE reward_item SET stock=5, per_user_limit=5 WHERE item_key=?", ITEM);

        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) jobs.add(redeemAttempt(ME, ITEM));
        assertThat(successes(jobs)).isEqualTo(1);

        assertThat(balance()).isZero();
        assertThat(stock(ITEM)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM redeem_record")).isEqualTo(1);
    }

    /** 设计稿 #8 限购：per_user_limit=2 兑 3 次，第 3 次被拒。 */
    @Test
    void perUserLimitRejectsTheThirdRedeem() {
        reset(100);
        jdbc.update("UPDATE reward_item SET per_user_limit=2, stock=10 WHERE item_key=?", ITEM);

        tx(() -> service.redeem(ME, ITEM));
        tx(() -> service.redeem(ME, ITEM));
        assertThatThrownBy(() -> tx(() -> service.redeem(ME, ITEM)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("每人限兑 2 件");

        assertThat(balance()).isEqualTo(80);
        assertThat(stock(ITEM)).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM coupon")).isEqualTo(2);
    }

    /** 设计稿 #9 下架：enabled=0 再兑 → 400「已下架」。 */
    @Test
    void disabledItemIsRejected() {
        reset(100);
        jdbc.update("UPDATE reward_item SET enabled=0 WHERE item_key=?", ITEM);

        assertThatThrownBy(() -> tx(() -> service.redeem(ME, ITEM)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已下架");
        assertThat(count("SELECT COUNT(*) FROM coupon")).isZero();
        assertThat(balance()).isEqualTo(100);
    }

    /** 商品池：下架不返回，售罄/达限有标记（设计稿 2.2 的商品状态）。 */
    @Test
    void rewardsListHidesDisabledAndMarksSoldOutAndLimit() {
        reset(30);
        jdbc.update("UPDATE reward_item SET stock=0 WHERE item_key=?", ITEM);
        jdbc.update("UPDATE reward_item SET enabled=0 WHERE item_key='takeout_1'");

        Map<String, Object> out = service.rewards(ME);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).extracting(i -> i.get("itemKey")).doesNotContain("takeout_1");
        Map<String, Object> ten = items.stream().filter(i -> ITEM.equals(i.get("itemKey"))).findFirst().orElseThrow();
        assertThat(ten.get("soldOut")).isEqualTo(true);
        assertThat(ten.get("stock")).isEqualTo(0);
        assertThat(ten.get("valueCents")).isEqualTo(10);
        Map<String, Object> twentyFive = items.stream()
            .filter(i -> "takeout_0_25".equals(i.get("itemKey"))).findFirst().orElseThrow();
        assertThat(twentyFive.get("soldOut")).isEqualTo(false);
        assertThat(twentyFive.get("limitReached")).isEqualTo(false);
        assertThat(out.get("balance")).isEqualTo(30);
    }

    /**
     * 设计稿 #1/#2 新人礼包与幂等：同一个人一辈子只发一次。
     *
     * 这里验证的是发放契约本身（biz_key='onboard' 的幂等 + 30P 入账）。
     * 建号时「首次登录才调用」的接线在 AuthService（本次授权范围外），未覆盖，见交付说明。
     */
    @Test
    void newbieGiftIsAwardedOnlyOncePerAccount() {
        reset(0);
        assertThat(points.award(ME, "newbie_gift", "onboard", "新人礼包"))
            .isEqualTo(PointsService.AwardResult.AWARDED);
        // 退出再登录 3 次：biz_key 相同，全部幂等命中
        for (int i = 0; i < 3; i++) {
            assertThat(points.award(ME, "newbie_gift", "onboard", "新人礼包"))
                .isEqualTo(PointsService.AwardResult.ALREADY);
        }

        assertThat(balance()).isEqualTo(30);
        assertThat(count("SELECT COUNT(*) FROM point_log WHERE user_id=" + ME)).isEqualTo(1);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", ME);
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(30);
        assertThat(log.get("biz_key")).isEqualTo("newbie_gift:onboard");
    }

    /** 设计稿 #20 过期券 + #10 券可见：可用列表只留 status=1 且未过期，按新到旧排列。 */
    @Test
    void availableCouponsHideExpiredAndUsedAndSortNewestFirst() {
        reset(0);
        long usable = insertCoupon(ME, 100, 0, LocalDate.now().plusDays(5), 1);
        long used = insertCoupon(ME, 100, 0, LocalDate.now().plusDays(5), 3);
        long expired = insertCoupon(ME, 100, 0, LocalDate.now().minusDays(1), 1);
        long newest = insertCoupon(ME, 50, 0, LocalDate.now().plusDays(9), 1);

        assertThat(coupons.available(ME)).extracting(Coupon::getId)
            .containsExactly(newest, usable)
            .doesNotContain(used, expired);

        // 过期券不可用（下单前校验）
        assertThatThrownBy(() -> coupons.usable(ME, expired, new BigDecimal("50.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已过期");
    }

    /**
     * 设计稿 #15/#16/#17 券核销三态：可用 →(下单) 锁定 →(支付) 核销 / 关单释放 / 退款作废。
     * 外加 #18 使用门槛与「不能拿别人的券」。
     */
    @Test
    void couponStateMachineLockUseReleaseVoidAndThreshold() {
        reset(0);
        long id = insertCoupon(ME, 100, 2000, LocalDate.now().plusDays(5), 1);

        // #18 门槛：满 20 元，15 元订单不可用
        assertThatThrownBy(() -> coupons.usable(ME, id, new BigDecimal("15.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("满 20.00 元可用");
        Coupon coupon = coupons.usable(ME, id, new BigDecimal("20.00"));
        assertThat(coupon.getValueCents()).isEqualTo(100);

        // 别人的券 / 不存在的券
        assertThatThrownBy(() -> coupons.usable(ME + 1, id, new BigDecimal("20.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("优惠券不存在");

        // 下单锁定：第二单锁同一张券命中 0 行
        long order = 7001L;
        assertThat(coupons.lock(ME, id, order)).isEqualTo(1);
        assertThat(coupons.lock(ME, id, 7002L)).isZero();
        Coupon locked = couponMapper.selectById(id);
        assertThat(locked.getStatus()).isEqualTo(2);
        assertThat(locked.getOrderId()).isEqualTo(order);
        assertThat(locked.getLockedAt()).isNotNull();
        // 锁定后不可再次下单
        assertThatThrownBy(() -> coupons.usable(ME, id, new BigDecimal("20.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已使用或已失效");

        // 支付成功核销：重复核销幂等（影响 0 行）
        assertThat(coupons.useByOrder(order)).isEqualTo(1);
        assertThat(coupons.useByOrder(order)).isZero();
        Coupon used = couponMapper.selectById(id);
        assertThat(used.getStatus()).isEqualTo(3);
        assertThat(used.getUsedAt()).isNotNull();

        // #16 关单释放：锁定的券回到可用（已核销的不动）
        long id2 = insertCoupon(ME, 100, 0, LocalDate.now().plusDays(5), 1);
        coupons.lock(ME, id2, 7003L);
        assertThat(coupons.releaseByOrder(7003L)).isEqualTo(1);
        Coupon released = couponMapper.selectById(id2);
        assertThat(released.getStatus()).isEqualTo(1);
        assertThat(released.getOrderId()).isNull();
        assertThat(released.getLockedAt()).isNull();
        assertThat(coupons.usable(ME, id2, new BigDecimal("20.00"))).isNotNull();
        assertThat(coupons.releaseByOrder(7003L)).isZero();

        // #17 退款作废：锁定中或已核销的都置 0，不可复用
        assertThat(coupons.voidByOrder(order)).isEqualTo(1);
        assertThat(couponMapper.selectById(id).getStatus()).isZero();
        assertThat(coupons.voidByOrder(order)).isZero();
        assertThat(coupons.available(ME)).extracting(Coupon::getId).doesNotContain(id);
    }

    /** 券面额缺失的老数据不允许被当成「可用」（券表改造的兼容线）。 */
    @Test
    void couponWithoutValueIsRejected() {
        reset(0);
        long id = insertCoupon(ME, 0, 0, LocalDate.now().plusDays(5), 1);
        assertThatThrownBy(() -> coupons.usable(ME, id, new BigDecimal("50.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("没有可抵扣金额");
    }

    // ==================== 脚手架 ====================

    /** 每个用例前清账并重置商品池/余额，保证互不干扰。 */
    private static void reset(int balance) {
        jdbc.update("DELETE FROM redeem_record");
        jdbc.update("DELETE FROM coupon");
        jdbc.update("DELETE FROM reward_item");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        points.evictCatalog();

        // 与 db/module-reward-v31.sql 的四档种子一致（价格/库存/限购/门槛）
        seedItem("takeout_1", "外卖立减 1 元", 100, 200, 3, 2000, 10);
        seedItem("takeout_0_5", "外卖立减 0.5 元", 50, 300, 6, 1500, 20);
        seedItem("takeout_0_25", "外卖立减 0.25 元", 25, 400, 8, 1500, 30);
        seedItem(ITEM, "外卖立减 0.1 元", 10, 500, 10, 1000, 40);

        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,?,0)", ME, balance);
    }

    private static void seedItem(String key, String name, int price, int stock, int limit, int threshold, int sort) {
        jdbc.update("""
            INSERT INTO reward_item(item_key,name,zone,price_p,cash_cents,stock,per_user_limit,
                                    coupon_title,coupon_amount,coupon_expire,threshold_cents,expire_days,enabled,sort)
            VALUES(?,?,'virtual',?,0,?,?,'外卖立减券','0.10 元','30天',?,30,1,?)
            """, key, name, price, stock, limit, threshold, sort);
    }

    /** 直接落一张券，用于核销三态/过期这类与兑换无关的用例。 */
    private static long insertCoupon(long userId, int valueCents, int thresholdCents, LocalDate expireOn, int status) {
        Coupon coupon = new Coupon();
        coupon.setUserId(userId);
        coupon.setTitle("外卖立减券");
        coupon.setAmount(valueCents + " 分");
        coupon.setExpireAt(expireOn.toString());
        coupon.setExpireOn(expireOn);
        coupon.setValueCents(valueCents);
        coupon.setThresholdCents(thresholdCents);
        coupon.setScope("takeout");
        coupon.setSource("redeem");
        coupon.setStatus(status);
        couponMapper.insert(coupon);
        return coupon.getId();
    }

    private static <T> T tx(Supplier<T> body) {
        return db.transactionTemplate.execute(status -> body.get());
    }

    private static Callable<Boolean> redeemAttempt(long userId, String itemKey) {
        return () -> {
            try {
                db.transactionTemplate.execute(status -> service.redeem(userId, itemKey));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    private static int successes(List<Callable<Boolean>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            int ok = 0;
            for (Future<Boolean> f : pool.invokeAll(jobs)) if (Boolean.TRUE.equals(f.get())) ok++;
            return ok;
        } finally {
            pool.shutdown();
        }
    }

    private static int balance() {
        List<Integer> v = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, ME);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static int stock(String itemKey) {
        return jdbc.queryForObject("SELECT stock FROM reward_item WHERE item_key=?", Integer.class, itemKey);
    }

    private static int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
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
        CREATE TABLE redeem_record (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          item_key VARCHAR(32) NOT NULL,
          price_p INT NOT NULL,
          coupon_id BIGINT NOT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_redeem_user (user_id, created_at),
          KEY idx_redeem_item (item_key, user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE coupon (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          title VARCHAR(64) NOT NULL,
          amount VARCHAR(16) NOT NULL DEFAULT '',
          expire_at VARCHAR(16) NOT NULL DEFAULT '',
          status TINYINT NOT NULL DEFAULT 1,
          value_cents INT NOT NULL DEFAULT 0,
          threshold_cents INT NOT NULL DEFAULT 0,
          scope VARCHAR(16) NOT NULL DEFAULT 'takeout',
          expire_on DATE NULL,
          locked_at DATETIME NULL,
          used_at DATETIME NULL,
          order_id BIGINT NULL,
          source VARCHAR(16) NOT NULL DEFAULT 'seed',
          KEY idx_coupon_user_state (user_id, status, expire_on)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}