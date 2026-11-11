package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.MarketTradeMapper;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 二手成交（F10 交易任务埋点）的真实 MySQL 集成测试。
 *
 * 覆盖设计稿第六节里可以自动化的用例：
 *   7 发起成交 / 8 卖家确认双方发分 / 9 自己不能买自己的 / 10 重复成交 /
 *   11 同一对用户 7 天只计一次分 / 12 非卖家不能确认 / 13 日限 2 笔 / 14 幂等与并发。
 * 另外补两条产品规则：同一买家重复点「我已买到」幂等、被取消后可以再次发起。
 *
 * 不碰 campus_life：{@link MysqlIntegrationSupport} 建临时 schema（campus_life_it_&lt;随机&gt;），
 * 只建本组用例需要的最小表，结束即 DROP。未设置 MYSQL_TEST_URL / MYSQL_TEST_USER 时整组跳过
 * （跳过即「未验证」，交付报告里必须写明）。
 *
 * 为什么必须真库：本域的正确性几乎全压在数据库约束上 —— uk_trade_goods 挡一件商品两笔成交、
 * 条件 UPDATE 挡并发双确认、point_log 唯一键 + task_log 日计数挡重复发分。
 * 这些用 Mockito 打桩测不出来。
 *
 * 服务是直接 new 出来的（没有 Spring 代理），所以 @Transactional 不生效、方法体里的每个 Mapper
 * 调用各自提交；AfterCommitPointsAwarder 在没有事务同步时立即执行，正好让断言同步可见。
 * 事务边界本身由 Spring 提供，不是这组用例的验证对象。
 *
 * 异常与 HTTP 状态码的对应关系不在本测试内（非 Spring 上下文），按 GlobalExceptionHandler 的约定：
 * IllegalArgumentException → 400、ForbiddenException → 403、IllegalStateException → 409，
 * 这里断言的是异常类型与文案。
 */
class MarketTradeServiceIntegrationTest {

    private static final long BUYER = 2001L;
    private static final long SELLER = 2002L;

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static MarketTradeService service;
    private static PointsService points;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        seedTask("market_trade", "market", "二手成交", 5, 12, 2);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("market-trade-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(MarketTradeMapper.class);
        configuration.addMapper(PointsMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        AfterCommitPointsAwarder awarder = new AfterCommitPointsAwarder(
            points, new DataSourceTransactionManager(db.dataSource));
        service = new MarketTradeService(template.getMapper(MarketTradeMapper.class), awarder);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM market_trade");
        jdbc.update("DELETE FROM goods");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        jdbc.update("DELETE FROM `user`");
        seedUser(BUYER, "买家同学");
        seedUser(SELLER, "卖家同学");
        points.evictCatalog();
    }

    // ==================== 用例 7：发起成交 ====================

    @Test
    void initiateCreatesPendingTradeVisibleToSeller() {
        goods(101L, SELLER);
        Map<String, Object> created = service.initiate(101L, BUYER);

        assertThat(created.get("status")).isEqualTo("pending");
        assertThat(created.get("alreadyPending")).isEqualTo(false);
        assertThat(number(created.get("tradeId"))).isPositive();
        assertThat(tradeStatus(101L)).isEqualTo("pending");

        // 卖家「我的发布」能看到这条待确认成交，并带上买家昵称（角标「待确认成交」的数据源）
        List<Map<String, Object>> mine = rows(service.mine(SELLER).get("list"));
        assertThat(mine).hasSize(1);
        Map<String, Object> row = mine.get(0);
        assertThat(number(row.get("goodsId"))).isEqualTo(101L);
        assertThat(row.get("tradeStatus")).isEqualTo("pending");
        assertThat(row.get("buyerNickname")).isEqualTo("买家同学");
        // 发起不会发分：pending 不是成交
        assertThat(balance(BUYER)).isZero();
        assertThat(balance(SELLER)).isZero();
    }

    @Test
    void sameBuyerRepeatingInitiateIsIdempotent() {
        goods(102L, SELLER);
        service.initiate(102L, BUYER);

        Map<String, Object> again = service.initiate(102L, BUYER);
        assertThat(again.get("alreadyPending")).isEqualTo(true);
        assertThat(again.get("status")).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade", Integer.class)).isEqualTo(1);
    }

    // ==================== 用例 8：卖家确认，双方各发一次 ====================

    @Test
    void sellerConfirmAwardsBothSidesAndMarksGoodsSold() {
        goods(103L, SELLER);
        Map<String, Object> created = service.initiate(103L, BUYER);

        Map<String, Object> confirmed = service.confirm(number(created.get("tradeId")), SELLER);

        assertThat(confirmed.get("status")).isEqualTo("confirmed");
        assertThat(confirmed.get("awarded")).isEqualTo(true);
        assertThat(confirmed.get("skipReason")).isNull();
        assertThat(confirmed.get("alreadyConfirmed")).isEqualTo(false);
        assertThat(confirmed.get("goodsStatus")).isEqualTo(2);

        // 5P/12G：买卖双方各一份
        assertThat(balance(BUYER)).isEqualTo(5);
        assertThat(growth(BUYER)).isEqualTo(12);
        assertThat(balance(SELLER)).isEqualTo(5);
        assertThat(growth(SELLER)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class)).isEqualTo(2);

        // 商品置已售：市场列表按 status=1 过滤，已售商品自动消失
        assertThat(goodsStatus(103L)).isEqualTo(2);

        // 幂等键按「成交 + 用户」一人一个
        long finalTradeId = number(created.get("tradeId"));
        List<String> keys = jdbc.queryForList("SELECT biz_key FROM point_log ORDER BY id", String.class);
        assertThat(keys).containsExactlyInAnyOrder(
            "market_trade:trade:" + finalTradeId + ":" + BUYER,
            "market_trade:trade:" + finalTradeId + ":" + SELLER);
    }

    @Test
    void confirmingTwiceAwardsOnlyOnce() {
        goods(104L, SELLER);
        long tradeId = number(service.initiate(104L, BUYER).get("tradeId"));
        service.confirm(tradeId, SELLER);

        Map<String, Object> again = service.confirm(tradeId, SELLER);
        assertThat(again.get("alreadyConfirmed")).isEqualTo(true);
        assertThat(again.get("awarded")).isEqualTo(false);

        assertThat(balance(BUYER)).isEqualTo(5);
        assertThat(balance(SELLER)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class)).isEqualTo(2);
    }

    // ==================== 用例 9：自己不能买自己的 ====================

    @Test
    void buyerCannotBuyOwnGoods() {
        goods(105L, SELLER);

        assertThatThrownBy(() -> service.initiate(105L, SELLER))
            .isInstanceOf(IllegalArgumentException.class)   // → HTTP 400
            .hasMessageContaining("不能购买自己发布的商品");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade", Integer.class)).isZero();
    }

    // ==================== 用例 10：重复成交 ====================

    @Test
    void secondInitiateAfterConfirmIsRejected() {
        goods(106L, SELLER);
        long tradeId = number(service.initiate(106L, BUYER).get("tradeId"));
        service.confirm(tradeId, SELLER);

        assertThatThrownBy(() -> service.initiate(106L, 2003L))
            .isInstanceOf(IllegalArgumentException.class)   // → HTTP 400
            .hasMessageContaining("该商品已有成交记录");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade WHERE goods_id=106", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void pendingTradeBlocksOtherBuyersButNotTheSameOne() {
        goods(107L, SELLER);
        service.initiate(107L, BUYER);

        assertThatThrownBy(() -> service.initiate(107L, 2003L))
            .isInstanceOf(IllegalStateException.class)      // → HTTP 409
            .hasMessageContaining("该商品已有待确认的成交");
    }

    // ==================== 用例 11：同一对用户 7 天只计一次分 ====================

    @Test
    void samePairWithinSevenDaysStillConfirmsButDoesNotAward() {
        goods(108L, SELLER);
        goods(109L, SELLER);
        service.confirm(number(service.initiate(108L, BUYER).get("tradeId")), SELLER);

        // 第一笔已发 5P/12G
        assertThat(balance(BUYER)).isEqualTo(5);

        Map<String, Object> second = service.confirm(number(service.initiate(109L, BUYER).get("tradeId")), SELLER);

        // 成交照样成立、商品照样置已售，只是不发分
        assertThat(second.get("status")).isEqualTo("confirmed");
        assertThat(second.get("awarded")).isEqualTo(false);
        assertThat(second.get("skipReason")).isEqualTo(MarketTradeService.SKIP_PAIR_7_DAYS);
        assertThat(goodsStatus(109L)).isEqualTo(2);
        assertThat(balance(BUYER)).isEqualTo(5);
        assertThat(balance(SELLER)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class)).isEqualTo(2);
    }

    // ==================== 用例 12：非卖家不能确认 ====================

    @Test
    void onlySellerCanConfirm() {
        goods(110L, SELLER);
        long tradeId = number(service.initiate(110L, BUYER).get("tradeId"));

        assertThatThrownBy(() -> service.confirm(tradeId, BUYER))
            .isInstanceOf(ForbiddenException.class)         // → HTTP 403
            .hasMessageContaining("只有卖家可以确认成交");
        assertThat(tradeStatus(110L)).isEqualTo("pending");
        assertThat(balance(BUYER)).isZero();
        assertThat(balance(SELLER)).isZero();
    }

    // ==================== 用例 13：二手日上限 2 笔 ====================

    @Test
    void dailyCapAwardsFirstTwoTradesOnly() {
        long buyer = 2101L;
        seedUser(buyer, "常买同学");
        for (long i = 1; i <= 3; i++) {
            goods(120L + i, 2200L + i);            // 三个不同卖家，避免 7 天同对用户规则干扰
            seedUser(2200L + i, "卖家" + i);
        }

        for (long i = 1; i <= 3; i++) {
            long tradeId = number(service.initiate(120L + i, buyer).get("tradeId"));
            Map<String, Object> view = service.confirm(tradeId, 2200L + i);
            // 成交本身永远成立
            assertThat(view.get("status")).isEqualTo("confirmed");
            if (i <= 2) {
                assertThat(view.get("skipReason")).isNull();
            } else {
                // 第 3 笔：确认人（卖家）自己没超限，但买家已满 2 笔 → 买家不发分
                assertThat(view.get("awarded")).isEqualTo(true);
            }
        }

        // 买家只拿到 2 笔的分；三个卖家各拿到自己那笔的 5P
        assertThat(balance(buyer)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log WHERE user_id=?", Integer.class, buyer))
            .isEqualTo(2);
        for (long i = 1; i <= 3; i++) {
            assertThat(balance(2200L + i)).isEqualTo(5);
        }
        // 日上限命中时 task_log 仍留痕（PointsService 的行为），所以它不能当「是否发分」的判据
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key='market_trade'",
            Integer.class, buyer)).isEqualTo(3);
    }

    @Test
    void sellerCapIsReportedBeforeConfirmingThoughTradeStillSucceeds() {
        long seller = 2300L;
        seedUser(seller, "勤快卖家");
        for (long i = 1; i <= 3; i++) {
            goods(130L + i, seller);
            seedUser(2400L + i, "买家" + i);
        }

        for (long i = 1; i <= 2; i++) {
            long tradeId = number(service.initiate(130L + i, 2400L + i).get("tradeId"));
            service.confirm(tradeId, seller);
        }
        long third = number(service.initiate(133L, 2400L + 3L).get("tradeId"));
        Map<String, Object> view = service.confirm(third, seller);

        assertThat(view.get("status")).isEqualTo("confirmed");
        assertThat(view.get("awarded")).isEqualTo(true);          // 仍会去尝试发分
        assertThat(view.get("skipReason")).isEqualTo(MarketTradeService.SKIP_DAILY_CAP);
        assertThat(balance(seller)).isEqualTo(10);                // 实际只到账两笔
    }

    // ==================== 用例 14：幂等与并发 ====================

    @Test
    void concurrentConfirmAwardsOnlyOnce() throws Exception {
        goods(140L, SELLER);
        long tradeId = number(service.initiate(140L, BUYER).get("tradeId"));

        List<Callable<Map<String, Object>>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> service.confirm(tradeId, SELLER));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Map<String, Object>>> futures = pool.invokeAll(jobs);
        pool.shutdown();

        int firstWins = 0;
        for (Future<Map<String, Object>> future : futures) {
            Map<String, Object> view = future.get();
            if (Boolean.FALSE.equals(view.get("alreadyConfirmed"))) firstWins++;
        }
        assertThat(firstWins).isEqualTo(1);      // 条件 UPDATE 只放行一个
        assertThat(tradeStatus(140L)).isEqualTo("confirmed");
        assertThat(balance(BUYER)).isEqualTo(5);
        assertThat(balance(SELLER)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentInitiateKeepsSingleTradeRow() throws Exception {
        goods(141L, SELLER);

        List<Callable<String>> jobs = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            final long bidder = 2500L + i;
            seedUser(bidder, "并发买家" + i);
            jobs.add(() -> {
                try {
                    service.initiate(141L, bidder);
                    return "ok";
                } catch (IllegalArgumentException e) {
                    return "400:" + e.getMessage();
                } catch (IllegalStateException e) {
                    return "409:" + e.getMessage();
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = pool.invokeAll(jobs);
        pool.shutdown();

        int ok = 0;
        for (Future<String> future : futures) {
            String result = future.get();
            if ("ok".equals(result)) ok++;
            else assertThat(result).matches("(400:该商品已有成交记录|409:该商品已有待确认的成交)");
        }
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade", Integer.class)).isEqualTo(1);
    }

    // ==================== 补充：取消后可以再次发起 ====================

    @Test
    void cancelledTradeCanBeRetriedByAnotherBuyer() {
        goods(150L, SELLER);
        long tradeId = number(service.initiate(150L, BUYER).get("tradeId"));

        Map<String, Object> cancelled = service.cancel(tradeId, SELLER);
        assertThat(cancelled.get("status")).isEqualTo("cancelled");
        // 同名商品仍在售，被拒之后可以再次发起（复用同一行，uk_trade_goods 只允许一行）
        assertThat(goodsStatus(150L)).isEqualTo(1);

        Map<String, Object> retried = service.initiate(150L, 2003L);
        assertThat(retried.get("status")).isEqualTo("pending");
        assertThat(number(retried.get("buyerId"))).isEqualTo(2003L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade", Integer.class)).isEqualTo(1);
    }

    // ==================== 工具 ====================

    private static void seedTask(String key, String module, String name, int p, int g, int cap) {
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            key, module, name, p, g, cap, 1, 60);
    }

    private static void seedUser(long id, String nickname) {
        jdbc.update("INSERT INTO `user`(id,nickname) VALUES(?,?)", id, nickname);
    }

    /** 在售商品（status=1，与 ModuleMapper.selectGood 的过滤条件一致）。 */
    private static void goods(long id, long sellerId) {
        jdbc.update("""
            INSERT INTO goods(id,user_id,mode,title,price,status)
            VALUES(?,?,1,'测试商品',9.90,1)
            """, id, sellerId);
    }

    private static int goodsStatus(long goodsId) {
        return jdbc.queryForObject("SELECT status FROM goods WHERE id=?", Integer.class, goodsId);
    }

    private static String tradeStatus(long goodsId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM market_trade WHERE goods_id=?", String.class, goodsId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int balance(long userId) {
        List<Integer> rows = jdbc.queryForList("SELECT balance FROM user_points WHERE user_id=?", Integer.class, userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static int growth(long userId) {
        List<Integer> rows = jdbc.queryForList("SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    // ==================== 建表（最小集） ====================

    private static final String[] TABLES = {
        """
        CREATE TABLE `user` (
          id BIGINT PRIMARY KEY,
          nickname VARCHAR(64) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE goods (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          mode TINYINT NOT NULL DEFAULT 1,
          title VARCHAR(64) NOT NULL,
          price DECIMAL(10,2) NOT NULL DEFAULT 0,
          image VARCHAR(255) NULL,
          status TINYINT NOT NULL DEFAULT 1,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_goods_user (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE market_trade (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          goods_id BIGINT NOT NULL,
          buyer_id BIGINT NOT NULL,
          seller_id BIGINT NOT NULL,
          status VARCHAR(16) NOT NULL DEFAULT 'pending',
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          confirmed_at DATETIME NULL,
          cancelled_at DATETIME NULL,
          UNIQUE KEY uk_trade_goods (goods_id),
          KEY idx_trade_pair (buyer_id, seller_id, status, confirmed_at),
          KEY idx_trade_seller (seller_id, status, created_at)
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
        CREATE TABLE point_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          amount INT NOT NULL,
          type VARCHAR(32) NOT NULL,
          ref_id BIGINT NULL,
          remark VARCHAR(100) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          module VARCHAR(16) NOT NULL DEFAULT 'market',
          biz_key VARCHAR(64) NULL,
          g_amount INT NOT NULL DEFAULT 0,
          balance_after INT NULL,
          KEY idx_user_time (user_id, created_at),
          UNIQUE KEY uk_user_biz (user_id, biz_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}