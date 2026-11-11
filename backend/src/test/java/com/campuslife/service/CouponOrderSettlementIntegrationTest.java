package com.campuslife.service;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.campuslife.dto.CreateOrderRequest;
import com.campuslife.mapper.CouponMapper;
import com.campuslife.mapper.DispatchMapper;
import com.campuslife.mapper.EarningsLedgerMapper;
import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.RecommendMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.ShopGoodsMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserAddressMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 券的结算、平台补贴与退款冲销的真库集成测试（设计稿第七节用例 13 / 14 / 17）。
 *
 * 这一层的正确性全压在数据库与事务上，mock 打桩测不出来：
 *   #13 抵扣结算：券额只从服务端算、券被锁定（status 1→2）、pay_amount_cents 与 total 一致；
 *   #14 商家不少收：平台补贴 = 券额入账，配送收入账不因用券而减少；
 *   #17 退款冲销：补贴置 void、券作废不退、用户只退实付额。
 *
 * 与设计稿的已知边界（本测试覆盖不到、报告里必须写明）：
 *   现有代码没有「商家收入」账目 —— earnings_ledger 里唯一的 delivery 收入行是
 *   DeliveryService/骑手侧的「配送报酬」，不是商家结算额；因此 #14 只能证明
 *   「补贴入账 = 券额 + 配送收入不被券扣减 + 商家侧计价基数（subtotal − shop_discount）不变」。
 *
 * 不碰 campus_life：MysqlIntegrationSupport 建临时 schema，结束即 DROP。
 * 未设置 MYSQL_TEST_URL 时整组跳过（跳过即「未验证」）。
 */
class CouponOrderSettlementIntegrationTest {

    private static final long UID = 990301L;
    private static final long SHOP_ID = 9901L;
    private static final long GOODS_ID = 99011L;
    private static final long COUPON_ID = 99101L;
    private static final long RIDER_ID = 77L;
    private static final String ADDRESS = "学生公寓 6 号楼 312";

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static BizService biz;
    private static CouponService couponService;
    private static PaymentLedgerService paymentLedger;
    private static MerchantRefundStatusService refundStatus;
    private static EarningsLedgerMapper earningsLedger;
    private static DispatchMapper dispatchMapper;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        // 脚手架自带的 user_order 只有支付/退款链路的最小列，这里换成与 module-food + module-payment
        // 一致、且带券与配送结算列的完整表（本组要真的走 BizService 下单）。
        jdbc.execute("DROP TABLE IF EXISTS user_order");
        for (String ddl : TABLES) jdbc.execute(ddl);

        jdbc.update("INSERT INTO `user`(id,nickname,avatar,school,school_id,status) VALUES(?,?,?,?,?,1)",
            UID, "测试用户", "", "测试大学", 1L);
        jdbc.update("""
            INSERT INTO shop(id,name,cover,category,rating,monthly_sales,min_price,delivery_fee,delivery_time,
                             discount,notice,discount_threshold,discount_amount,status,is_open,open_time,close_time,
                             school_id,order_count,view_count,contact_phone,address,owner_name,intro)
            VALUES(?,?,?,?,?,0,0,2.00,30,'满20减8','测试店铺',20.00,8.00,1,1,'00:00','00:00',1,0,0,'','','','')
            """, SHOP_ID, "测试外卖店", "/assets/home/food-milktea.webp", "快餐便当", new BigDecimal("4.9"));
        jdbc.update("INSERT INTO shop_goods(id,shop_id,category,name,price,monthly_sales,image,sort_num,status) VALUES(?,?,?,?,?,0,'',1,1)",
            GOODS_ID, SHOP_ID, "主食", "照烧鸡腿饭", new BigDecimal("50.00"));
        jdbc.update("INSERT INTO user_address(user_id,contact,phone,detail) VALUES(?,?,?,?)",
            UID, "林同学", "13800000000", ADDRESS);

        // MyBatis-Plus 的 BaseMapper 必须走 MybatisConfiguration 才会注入 CRUD；id 策略对齐 application.yml
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getDbConfig(configuration).setIdType(IdType.AUTO);
        configuration.setEnvironment(new Environment("coupon-order-it", new SpringManagedTransactionFactory(), db.dataSource));
        for (Class<?> mapper : MAPPERS) configuration.addMapper(mapper);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        couponService = new CouponService(template.getMapper(CouponMapper.class));
        earningsLedger = template.getMapper(EarningsLedgerMapper.class);
        dispatchMapper = template.getMapper(DispatchMapper.class);
        UserOrderMapper orderMapper = template.getMapper(UserOrderMapper.class);
        PaymentExceptionMapper exceptionMapper = template.getMapper(PaymentExceptionMapper.class);
        paymentLedger = new PaymentLedgerService(orderMapper, template.getMapper(PaymentRecordMapper.class),
            exceptionMapper, new PaymentAlerts(new SimpleMeterRegistry()), couponService, earningsLedger);
        refundStatus = new MerchantRefundStatusService(template.getMapper(MerchantMapper.class), exceptionMapper,
            template.getMapper(RefundRecordMapper.class), couponService, earningsLedger);
        biz = new BizService(
            template.getMapper(ShopMapper.class),
            template.getMapper(ShopGoodsMapper.class),
            orderMapper,
            template.getMapper(UserMapper.class),
            template.getMapper(UserAddressMapper.class),
            null,   // ModuleMapper：下单链路不用
            template.getMapper(RecommendMapper.class),
            new ObjectMapper(),
            null,   // AuditService：下单链路不用
            null,   // FeatureFlags：下单链路不用
            couponService);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    // ==================== 用例 ====================

    /**
     * 设计稿 #13 抵扣结算：100 元商品 + 满 20 减 8 + 1 元券 → 合计 95.00，券被锁定。
     *
     * 金额链：(50×2) − 8.00 − 1.00 + 2.00 打包 + 2.00 配送 = 95.00；pay_amount_cents = 9500。
     * 同一商品不用券的对照单是 96.00 —— 差的就是券额，证明抵扣真的生效而不是只写了个字段。
     */
    @Test
    void couponDiscountsTotalAndLocksCouponAtCheckout() {
        reset();

        Map<String, Object> withCoupon = createOrder(COUPON_ID, "co-it-coupon-13");
        long orderId = number(withCoupon.get("orderId"));
        assertThat((BigDecimal) withCoupon.get("total")).isEqualByComparingTo("95.00");
        assertThat((BigDecimal) withCoupon.get("couponDiscount")).isEqualByComparingTo("1.00");
        assertThat(((Number) withCoupon.get("payAmountCents")).intValue()).isEqualTo(9500);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_order WHERE id=?", orderId);
        // 服务端自己算的金额：店铺满减与券抵扣分列，前端改不了
        assertThat((BigDecimal) row.get("subtotal")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) row.get("discount_amount")).isEqualByComparingTo("8.00");
        assertThat((BigDecimal) row.get("coupon_discount")).isEqualByComparingTo("1.00");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("95.00");
        assertThat(((Number) row.get("pay_amount_cents")).intValue())
            .isEqualTo(((BigDecimal) row.get("total")).movePointRight(2).intValue());
        assertThat(((Number) row.get("coupon_id")).longValue()).isEqualTo(COUPON_ID);

        // 券被锁定：别人用不了，支付成功才核销
        Map<String, Object> coupon = jdbc.queryForMap("SELECT * FROM coupon WHERE id=?", COUPON_ID);
        assertThat(((Number) coupon.get("status")).intValue()).isEqualTo(2);
        assertThat(((Number) coupon.get("order_id")).longValue()).isEqualTo(orderId);
        assertThat(coupon.get("locked_at")).isNotNull();

        // 对照：同样商品不用券 → 96.00。差 1.00 就是券额
        Map<String, Object> plain = createOrder(null, "co-it-plain-13");
        assertThat((BigDecimal) plain.get("total")).isEqualByComparingTo("96.00");
        assertThat((BigDecimal) plain.get("couponDiscount")).isEqualByComparingTo("0");
        assertThat(((Number) plain.get("payAmountCents")).intValue()).isEqualTo(9600);

        // 同一张券再下一单：已锁定 → 明确报错，订单不落库（并发第二单不会重复抵扣）
        assertThatThrownBy(() -> createOrder(COUPON_ID, "co-it-coupon-13b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("优惠券");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_order", Integer.class)).isEqualTo(2);
    }

    /**
     * 设计稿 #14 商家不少收：支付成功后
     *  ① 券核销（status 3）；
     *  ② 平台补贴账 delivery:{orderId}:subsidy = 券额 100 分（平台出钱）；
     *  ③ 配送收入账 delivery:{orderId}:income 等于配送报酬，不因用券减少；
     *  ④ 商家侧计价基数 subtotal − shop_discount 仍是 92.00（券只进 coupon_discount）。
     */
    @Test
    void platformSubsidyCoversCouponAndIncomeIsNotReduced() {
        reset();
        Map<String, Object> created = createOrder(COUPON_ID, "co-it-coupon-14");
        long orderId = number(created.get("orderId"));
        String outTradeNo = String.valueOf(created.get("outTradeNo"));
        int payCents = ((Number) created.get("payAmountCents")).intValue();
        assertThat(payCents).isEqualTo(9500);

        PaymentLedgerService.Outcome outcome = db.transactionTemplate.execute(status ->
            paymentLedger.applyVerifiedPayment(outTradeNo, "WX-IT-14", "SUCCESS", payCents, "{}", "test"));
        assertThat(outcome).isEqualTo(PaymentLedgerService.Outcome.APPLIED);

        // ① 券核销
        assertThat(couponStatus()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM coupon WHERE id=?", Boolean.class, COUPON_ID))
            .isTrue();

        // ② 平台补贴 = 券额（100 分），状态 recorded
        Map<String, Object> subsidy = jdbc.queryForMap(
            "SELECT * FROM earnings_ledger WHERE biz_no=?", "delivery:" + orderId + ":subsidy");
        assertThat(subsidy.get("entry_type")).isEqualTo("platform_subsidy");
        assertThat(((Number) subsidy.get("amount_cents")).intValue()).isEqualTo(100);
        assertThat(subsidy.get("status")).isEqualTo("recorded");

        // ③ 配送收入账不因券减少：本单配送报酬 500 分，记 500 而不是 400
        jdbc.update("UPDATE user_order SET status='completed', delivery_pay_cents=500, rider_id=?, actual_rider_id=? WHERE id=?",
            RIDER_ID, RIDER_ID, orderId);
        DispatchService dispatch = new DispatchService(dispatchMapper, null, earningsLedger);
        assertThat(dispatch.recordDeliveryIncome(orderId, RIDER_ID)).isTrue();
        Map<String, Object> income = jdbc.queryForMap(
            "SELECT * FROM earnings_ledger WHERE biz_no=?", "delivery:" + orderId + ":income");
        assertThat(income.get("entry_type")).isEqualTo("delivery_income");
        assertThat(((Number) income.get("amount_cents")).intValue()).isEqualTo(500);
        assertThat(((Number) income.get("amount_cents")).intValue()).isNotEqualTo(400);

        // ④ 商家侧计价基数不含券
        assertThat(jdbc.queryForObject("SELECT subtotal - discount_amount FROM user_order WHERE id=?",
            BigDecimal.class, orderId)).isEqualByComparingTo("92.00");
    }

    /**
     * 设计稿 #17 退款冲销：用券订单退款后
     *  ① 用户只退实付额 95.00（9500 分），券额不退；
     *  ② 平台补贴条目置 void（账留痕不删）；
     *  ③ 券作废（status 0），不退回用户的券包；
     *  ④ 订单 status/pay_status 收敛为 refunded。
     */
    @Test
    void refundVoidsSubsidyAndCouponButOnlyPaidAmountGoesBack() {
        reset();
        Map<String, Object> created = createOrder(COUPON_ID, "co-it-coupon-17");
        long orderId = number(created.get("orderId"));
        String outTradeNo = String.valueOf(created.get("outTradeNo"));
        String transactionId = "WX-IT-17";
        int payCents = ((Number) created.get("payAmountCents")).intValue();
        db.transactionTemplate.execute(status ->
            paymentLedger.applyVerifiedPayment(outTradeNo, transactionId, "SUCCESS", payCents, "{}", "test"));
        assertThat(couponStatus()).isEqualTo(3);
        assertThat(ledgerStatus("delivery:" + orderId + ":subsidy")).isEqualTo("recorded");

        // 用户申请退款：只退实付额
        String outRefundNo = "RF-IT-17";
        jdbc.update("INSERT INTO refund_record(order_id,out_refund_no,refund_id,amount_cents,reason,status) "
            + "VALUES(?,?,'',?, '', 'pending')", orderId, outRefundNo, payCents);
        String result = db.transactionTemplate.execute(status ->
            refundStatus.applyProviderResult(outRefundNo, refund(Status.SUCCESS, outRefundNo, outTradeNo, transactionId, payCents)));
        assertThat(result).isEqualTo("success");

        // ② 补贴置 void，金额与记录仍在（不删账）
        Map<String, Object> subsidy = jdbc.queryForMap(
            "SELECT * FROM earnings_ledger WHERE biz_no=?", "delivery:" + orderId + ":subsidy");
        assertThat(subsidy.get("status")).isEqualTo("void");
        assertThat(((Number) subsidy.get("amount_cents")).intValue()).isEqualTo(100);

        // ③ 券作废不退
        assertThat(couponStatus()).isZero();
        // ④ 订单收敛
        assertThat(jdbc.queryForObject("SELECT status FROM user_order WHERE id=?", String.class, orderId))
            .isEqualTo("refunded");
        assertThat(jdbc.queryForObject("SELECT pay_status FROM user_order WHERE id=?", String.class, orderId))
            .isEqualTo("refunded");
        // ① 退款金额 = 实付 9500，不是无券价 9600
        int refunded = jdbc.queryForObject("SELECT amount_cents FROM refund_record WHERE order_id=?", Integer.class, orderId);
        assertThat(refunded).isEqualTo(payCents);
        assertThat(refunded).isNotEqualTo(9600);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_record WHERE order_id=?", String.class, orderId))
            .isEqualTo("success");
    }

    // ==================== 工具 ====================

    private static void reset() {
        jdbc.update("DELETE FROM user_order");
        jdbc.update("DELETE FROM coupon");
        jdbc.update("DELETE FROM earnings_ledger");
        jdbc.update("DELETE FROM payment_record");
        jdbc.update("DELETE FROM payment_exception");
        jdbc.update("DELETE FROM refund_record");
        jdbc.update("DELETE FROM notification");
        // 券：1 元券，满 20 元可用，外卖品类，30 天有效
        jdbc.update("""
            INSERT INTO coupon(id,user_id,title,amount,expire_at,status,value_cents,threshold_cents,scope,expire_on,source)
            VALUES(?,?,?,?,?,1,100,2000,'takeout',DATE_ADD(CURDATE(), INTERVAL 30 DAY),'redeem')
            """, COUPON_ID, UID, "外卖立减 1 元", "1.00 元", "");
    }

    private static Map<String, Object> createOrder(Long couponId, String idemKey) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(UID);
        request.setShopId(SHOP_ID);
        CreateOrderRequest.Item item = new CreateOrderRequest.Item();
        item.setGoodsId(GOODS_ID);
        item.setCount(2);
        request.setItems(List.of(item));
        request.setAddress(ADDRESS);
        request.setContactName("林同学");
        request.setContactPhone("13800000000");
        request.setRemark("");
        request.setIdempotencyKey(idemKey);
        request.setCouponId(couponId);
        return db.transactionTemplate.execute(status -> biz.order(request));
    }

    private static Refund refund(Status status, String outRefundNo, String outTradeNo,
                                 String transactionId, int cents) {
        Refund refund = new Refund();
        refund.setOutRefundNo(outRefundNo);
        refund.setOutTradeNo(outTradeNo);
        refund.setTransactionId(transactionId);
        refund.setRefundId("R-" + outRefundNo);
        refund.setStatus(status);
        Amount amount = new Amount();
        amount.setRefund((long) cents);
        amount.setTotal((long) cents);
        refund.setAmount(amount);
        return refund;
    }

    private static int couponStatus() {
        return jdbc.queryForObject("SELECT status FROM coupon WHERE id=?", Integer.class, COUPON_ID);
    }

    private static String ledgerStatus(String bizNo) {
        return jdbc.queryForObject("SELECT status FROM earnings_ledger WHERE biz_no=?", String.class, bizNo);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    // ==================== 建表 ====================

    @SuppressWarnings("unchecked")
    private static final Class<?>[] MAPPERS = {
        ShopMapper.class, ShopGoodsMapper.class, UserOrderMapper.class, UserMapper.class,
        UserAddressMapper.class, RecommendMapper.class, CouponMapper.class, EarningsLedgerMapper.class,
        PaymentRecordMapper.class, PaymentExceptionMapper.class, RefundRecordMapper.class,
        MerchantMapper.class, DispatchMapper.class
    };

    private static final String[] TABLES = {
        """
        CREATE TABLE `user` (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          nickname VARCHAR(32) NOT NULL,
          openid VARCHAR(64) NULL,
          avatar VARCHAR(255) NULL,
          school VARCHAR(64) NULL,
          school_id BIGINT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          status TINYINT NOT NULL DEFAULT 1,
          deleted_at DATETIME NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE shop (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          name VARCHAR(64) NOT NULL,
          cover VARCHAR(255) NOT NULL,
          category VARCHAR(32) NOT NULL,
          rating DECIMAL(2,1) NOT NULL DEFAULT 5.0,
          monthly_sales INT NOT NULL DEFAULT 0,
          min_price DECIMAL(10,2) NOT NULL DEFAULT 0,
          delivery_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
          delivery_time INT NOT NULL DEFAULT 30,
          discount VARCHAR(32) NOT NULL DEFAULT '',
          notice VARCHAR(200) NOT NULL DEFAULT '',
          contact_phone VARCHAR(20) NULL,
          address VARCHAR(255) NULL,
          owner_name VARCHAR(32) NULL,
          intro VARCHAR(500) NULL,
          open_time VARCHAR(8) NOT NULL DEFAULT '00:00',
          close_time VARCHAR(8) NOT NULL DEFAULT '00:00',
          is_open TINYINT NOT NULL DEFAULT 1,
          school_id BIGINT NULL,
          discount_threshold DECIMAL(10,2) NOT NULL DEFAULT 0,
          discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
          status TINYINT NOT NULL DEFAULT 1,
          order_count INT NOT NULL DEFAULT 0,
          view_count INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE shop_goods (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          shop_id BIGINT NOT NULL,
          category VARCHAR(32) NOT NULL,
          name VARCHAR(64) NOT NULL,
          price DECIMAL(10,2) NOT NULL,
          monthly_sales INT NOT NULL DEFAULT 0,
          image VARCHAR(255) NOT NULL DEFAULT '',
          sort_num INT NOT NULL DEFAULT 0,
          status TINYINT NOT NULL DEFAULT 1
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_address (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          contact VARCHAR(32) NOT NULL,
          phone VARCHAR(20) NOT NULL DEFAULT '',
          detail VARCHAR(128) NOT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_order (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          shop_id BIGINT NOT NULL,
          items TEXT NOT NULL,
          address VARCHAR(128) NOT NULL,
          contact_name VARCHAR(32) NOT NULL DEFAULT '',
          contact_phone VARCHAR(20) NOT NULL DEFAULT '',
          remark VARCHAR(200) NOT NULL DEFAULT '',
          subtotal DECIMAL(10,2) NOT NULL,
          packing_fee DECIMAL(10,2) NOT NULL,
          delivery_fee DECIMAL(10,2) NOT NULL,
          discount_amount DECIMAL(10,2) NOT NULL,
          coupon_discount DECIMAL(10,2) NOT NULL DEFAULT 0,
          total DECIMAL(10,2) NOT NULL,
          coupon_id BIGINT NULL,
          pay_amount_cents INT NULL,
          out_trade_no VARCHAR(64) NULL,
          transaction_id VARCHAR(64) NULL,
          prepay_id VARCHAR(64) NULL,
          pay_status VARCHAR(24) NOT NULL DEFAULT 'unpaid',
          paid_at DATETIME NULL,
          cancelled_at DATETIME NULL,
          cancel_reason VARCHAR(255) NULL,
          status VARCHAR(16) NOT NULL DEFAULT 'created',
          rider_id BIGINT NULL,
          rider_name VARCHAR(32) NULL,
          accepted_at DATETIME NULL,
          picked_at DATETIME NULL,
          delivered_at DATETIME NULL,
          actual_rider_id BIGINT NULL,
          delivery_pay_cents INT NOT NULL DEFAULT 0,
          delivery_pay_counted TINYINT NOT NULL DEFAULT 1,
          delivery_pay_recorded TINYINT NOT NULL DEFAULT 0,
          idempotency_key VARCHAR(64) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_order_user_idem (user_id, idempotency_key),
          UNIQUE KEY uk_order_out_trade_no (out_trade_no),
          UNIQUE KEY uk_order_transaction_id (transaction_id),
          KEY idx_order_pay_status (pay_status, created_at)
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
        """,
        """
        CREATE TABLE earnings_ledger (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          biz_no VARCHAR(80) NOT NULL,
          biz_type VARCHAR(16) NOT NULL,
          ref_id BIGINT NOT NULL,
          user_id BIGINT DEFAULT NULL,
          entry_type VARCHAR(24) NOT NULL,
          amount_cents INT NOT NULL,
          status VARCHAR(16) NOT NULL DEFAULT 'recorded',
          note VARCHAR(200) NOT NULL DEFAULT '',
          operator_id BIGINT DEFAULT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_ledger_biz_no (biz_no),
          KEY idx_ledger_ref (biz_type, ref_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE payment_record (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          order_id BIGINT NULL,
          out_trade_no VARCHAR(64) NOT NULL,
          transaction_id VARCHAR(64) NOT NULL,
          amount_cents INT NOT NULL,
          trade_state VARCHAR(32) NOT NULL,
          raw_body TEXT NOT NULL,
          verified TINYINT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_payment_transaction (transaction_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}