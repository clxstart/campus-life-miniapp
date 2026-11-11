package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 二手成交（F10）的全部 SQL。
 *
 * 为什么直接读写 goods：成交流程要读「商品是否在售 / 卖家是谁」、确认后要把商品置为已售；
 * 这几条是成交状态机的一部分，放进这里比拆到 ModuleMapper 更能保证「改成交状态 ⇄ 改商品状态」在一处对照。
 * 只读 goods 的 5 个字段，不改动二手列表/发布的任何既有查询。
 */
@Mapper
public interface MarketTradeMapper {

    // ==================== market_trade ====================

    @Select("""
        SELECT t.id, t.goods_id AS goodsId, t.buyer_id AS buyerId, t.seller_id AS sellerId,
               t.status, t.created_at AS createdAt, t.confirmed_at AS confirmedAt, t.cancelled_at AS cancelledAt
        FROM market_trade t WHERE t.id = #{id}
        """)
    Map<String, Object> findById(@Param("id") Long id);

    /** 一件商品最多一行（uk_trade_goods），所以按 goods_id 取到的就是唯一那笔。 */
    @Select("""
        SELECT t.id, t.goods_id AS goodsId, t.buyer_id AS buyerId, t.seller_id AS sellerId,
               t.status, t.created_at AS createdAt, t.confirmed_at AS confirmedAt, t.cancelled_at AS cancelledAt
        FROM market_trade t WHERE t.goods_id = #{goodsId}
        """)
    Map<String, Object> findByGoodsId(@Param("goodsId") Long goodsId);

    /**
     * 买家视角：**我这个买家**在这件商品上的那笔成交。
     *
     * 必须同时带 goods_id 与 buyer_id：只按商品查会把这个商品的成交回给卖家和其它买家，
     * 只按买家查会带出他在别的商品上的成交。没有记录返回 null（Service 转成空 Map）。
     */
    @Select("""
        SELECT t.id, t.buyer_id AS buyerId, t.seller_id AS sellerId, t.status,
               t.created_at AS createdAt, t.confirmed_at AS confirmedAt
        FROM market_trade t
        WHERE t.goods_id = #{goodsId} AND t.buyer_id = #{buyerId}
        ORDER BY t.id DESC LIMIT 1
        """)
    Map<String, Object> findMineByGoodsAndBuyer(@Param("goodsId") Long goodsId,
                                               @Param("buyerId") Long buyerId);

    @Insert("""
        INSERT INTO market_trade(goods_id, buyer_id, seller_id, status)
        VALUES(#{goodsId}, #{buyerId}, #{sellerId}, 'pending')
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Map<String, Object> row);

    /**
     * 被取消过的商品再次发起成交：**复用同一行**（uk_trade_goods 只允许一条）。
     * 带 status='cancelled' 条件，两个买家同时点只会有一个人命中。
     */
    @Update("""
        UPDATE market_trade
        SET buyer_id = #{buyerId}, seller_id = #{sellerId}, status = 'pending',
            created_at = NOW(), confirmed_at = NULL, cancelled_at = NULL
        WHERE goods_id = #{goodsId} AND status = 'cancelled'
        """)
    int reuseCancelled(@Param("goodsId") Long goodsId, @Param("buyerId") Long buyerId,
                       @Param("sellerId") Long sellerId);

    /** 确认成交：并发下只有 seller + pending 的那一次能命中 1 行，其余 0 行。 */
    @Update("""
        UPDATE market_trade
        SET status = 'confirmed', confirmed_at = NOW()
        WHERE id = #{id} AND seller_id = #{sellerId} AND status = 'pending'
        """)
    int confirm(@Param("id") Long id, @Param("sellerId") Long sellerId);

    /** 取消：买卖任一方可取消，且只对 pending 生效；confirmed 不可取消。 */
    @Update("""
        UPDATE market_trade
        SET status = 'cancelled', cancelled_at = NOW()
        WHERE id = #{id} AND status = 'pending' AND (buyer_id = #{userId} OR seller_id = #{userId})
        """)
    int cancel(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 7 天互刷判定：同一对用户（买卖方对调也算同一对）最近 7 天内是否已经确认过别的成交。
     * 判定只影响「发不发分」，不影响成交本身 —— 所以必须先查再确认，本笔自身用 id 排除。
     */
    @Select("""
        SELECT COUNT(*) FROM market_trade
        WHERE status = 'confirmed'
          AND confirmed_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
          AND id <> #{tradeId}
          AND ((buyer_id = #{buyerId} AND seller_id = #{sellerId})
            OR (buyer_id = #{sellerId} AND seller_id = #{buyerId}))
        """)
    int countRecentPair(@Param("tradeId") Long tradeId, @Param("buyerId") Long buyerId,
                        @Param("sellerId") Long sellerId);

    /**
     * 卖家「我的发布」：我发布的二手商品 + 最近一笔成交（供小程序「待确认成交」角标/按钮）。
     * 排序：有待确认成交的排最前，其余按发布时间倒序。
     */
    @Select("""
        SELECT g.id AS goodsId, g.title, g.price, g.image AS cover, g.status AS goodsStatus,
               t.id AS tradeId, t.status AS tradeStatus, t.buyer_id AS buyerId,
               bu.nickname AS buyerNickname, t.created_at AS createdAt
        FROM goods g
        LEFT JOIN market_trade t ON t.goods_id = g.id
        LEFT JOIN `user` bu ON bu.id = t.buyer_id
        WHERE g.user_id = #{sellerId}
        ORDER BY (t.status = 'pending') DESC, t.created_at DESC, g.id DESC
        LIMIT 100
        """)
    List<Map<String, Object>> sellerTrades(@Param("sellerId") Long sellerId);

    // ==================== goods（成交状态机相关的最小读写） ====================

    @Select("""
        SELECT g.id, g.user_id AS sellerId, g.title, g.price, g.image, g.status
        FROM goods g WHERE g.id = #{id}
        """)
    Map<String, Object> findGoods(@Param("id") Long id);

    /** 确认成交后置为已售（status=2）。商品此前可能已被卖家下架（0），成交仍然成立，所以不限制原值。 */
    @Update("UPDATE goods SET status = 2 WHERE id = #{id}")
    int markGoodsSold(@Param("id") Long id);

    // ==================== 发分前置（只读，不写账本） ====================

    /**
     * task_catalog 里二手成交任务的日上限（行缺失返回 null，调用方按「不限」处理）。
     * 只读：上限的唯一权威仍是 PointsService，这里仅用于给前端一句准话。
     */
    @Select("SELECT COALESCE(daily_cap, 0) FROM task_catalog WHERE task_key = 'market_trade'")
    Integer marketTaskDailyCap();

    /**
     * 今天这个用户已经**实际到账**几次二手成交积分。
     *
     * 读 point_log 而不是 task_log：日上限命中时 task_log 仍会插一行但不发分，
     * 用 task_log 计数会把「被拒的那次」也算进去，提示会提前变成「已满」。
     */
    @Select("""
        SELECT COUNT(*) FROM point_log
        WHERE user_id = #{userId} AND biz_key LIKE 'market_trade:trade:%'
          AND created_at >= CURDATE()
        """)
    int countTodayTradeAwards(@Param("userId") Long userId);
}