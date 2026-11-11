package com.campuslife.service;

import com.campuslife.mapper.MarketTradeMapper;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 二手成交「买家刷新恢复」查询（F10）的真实 MySQL 集成测试。
 *
 * 覆盖新增接口 `GET /api/market/{goodsId}/trade` 背后的
 * {@link MarketTradeService#findMine(Long, Long)}：
 *   1. 自己发起的 pending 能查到（字段为约定的 6 个，没有成交时返回空 Map 而不是 404）；
 *   2. 另一个买家查同一商品返回空（互相不可见）；
 *   3. 卖家查自己发布的商品也返回空（不能借这个接口看买家成交）；
 *   4. 取消后返回 `cancelled`（前端据此回到正常售卖态）。
 *
 * 只读查询本身没有发分/事务逻辑，所以不动 points 表；但 initiate/cancel 走真实 SQL，
 * 仍然需要真库：本组验证的正是 `WHERE goods_id=? AND buyer_id=?` 这个不可见边界。
 *
 * 不碰 campus_life：{@link MysqlIntegrationSupport} 建临时 schema（campus_life_it_&lt;随机&gt;），
 * 结束即 DROP。未设置 MYSQL_TEST_URL / MYSQL_TEST_USER / MYSQL_TEST_PASSWORD 时整组跳过
 * （跳过即「未验证」，交付报告必须写明）。
 */
class MarketTradeQueryIntegrationTest {

    private static final long BUYER = 3001L;
    private static final long OTHER_BUYER = 3002L;
    private static final long SELLER = 3003L;
    private static final long GOODS = 9101L;

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static MarketTradeService service;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
            "market-trade-query-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(MarketTradeMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        // 查询链路不用 awarder：只有 initiate/cancel 会走成交状态机，它们不发分。
        service = new MarketTradeService(template.getMapper(MarketTradeMapper.class), null);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM market_trade");
        jdbc.update("DELETE FROM goods");
        jdbc.update("""
            INSERT INTO goods(id,user_id,mode,title,price,status)
            VALUES(?,?,1,'测试商品',9.90,1)
            """, GOODS, SELLER);
    }

    // ==================== 1. 自己发起的 pending 能查到 ====================

    @Test
    void buyerCanSeeOwnPendingTrade() {
        // 没有成交时返回空 Map（接口 data={}），不是 404、也不是 null。
        assertThat(service.findMine(GOODS, BUYER)).isEmpty();

        Map<String, Object> created = service.initiate(GOODS, BUYER);
        assertThat(created.get("status")).isEqualTo("pending");

        Map<String, Object> mine = service.findMine(GOODS, BUYER);
        assertThat(mine).isNotEmpty();
        assertThat(number(mine.get("id"))).isEqualTo(number(created.get("tradeId")));
        assertThat(number(mine.get("buyerId"))).isEqualTo(BUYER);
        assertThat(number(mine.get("sellerId"))).isEqualTo(SELLER);
        assertThat(mine.get("status")).isEqualTo("pending");
        assertThat(mine.get("createdAt")).isNotNull();
        assertThat(mine.get("confirmedAt")).isNull();
        // 返回字段就是约定的 6 个，没有把 goods_id 等内部字段漏出去
        assertThat(mine.keySet())
            .containsExactlyInAnyOrder("id", "status", "createdAt", "confirmedAt", "sellerId", "buyerId");
    }

    // ==================== 2. 另一个买家看不到 ====================

    @Test
    void otherBuyerCannotSeeTrade() {
        service.initiate(GOODS, BUYER);

        assertThat(service.findMine(GOODS, OTHER_BUYER)).isEmpty();
    }

    // ==================== 3. 卖家视角为空 ====================

    @Test
    void sellerCannotSeeBuyersTradeThroughBuyerQuery() {
        service.initiate(GOODS, BUYER);

        // 商品是卖家自己的，但成交属于买家；这个接口只回「我自己发起的」成交。
        assertThat(service.findMine(GOODS, SELLER)).isEmpty();
    }

    // ==================== 4. 取消后返回 cancelled ====================

    @Test
    void cancelledTradeIsReturnedAsCancelled() {
        long tradeId = number(service.initiate(GOODS, BUYER).get("tradeId"));
        service.cancel(tradeId, BUYER);

        Map<String, Object> mine = service.findMine(GOODS, BUYER);
        assertThat(number(mine.get("id"))).isEqualTo(tradeId);
        assertThat(mine.get("status")).isEqualTo("cancelled");
        // 取消不删行：买家刷新时仍能拿到这笔的最终状态，前端据此回到正常售卖态。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_trade", Integer.class)).isEqualTo(1);
    }

    // ==================== 工具 ====================

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    // ==================== 建表（最小集） ====================

    private static final String[] TABLES = {
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
        """
    };
}