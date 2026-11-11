package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 首页「今日推荐」：按真实用户行为热度排序，支持轮换（offset）。
// 热度来源：二手=浏览/收藏/私聊；外卖=真实订单/浏览。
@Mapper
public interface RecommendMapper {

    // 二手商品热度榜：浏览×3 + 收藏×5 + 咨询×8；同分按时间倒序
    //
    // 注意：这里刻意**不**带 "我收藏过没有"。那个标记是按人算的，放进这条 SQL 会
    // 让整份结果无法按 (batch) 缓存，而且每行一次相关子查询。改由 favoriteGoodsIds()
    // 在取回结果后补一次，见 RecommendService.home()。
    @Select("""
        <script>
        SELECT g.id, g.title, g.price, g.image, g.category, g.condition_level AS conditionLevel,
               g.description, g.mode, g.view_count AS viewCount, g.favorite_count AS favoriteCount,
               g.contact_count AS contactCount, g.created_at AS createdAt,
               (g.view_count*3 + g.favorite_count*5 + g.contact_count*8) AS heat
        FROM goods g
        WHERE g.status=1
        ORDER BY heat DESC, g.created_at DESC, g.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> hotGoods(@Param("limit") int limit, @Param("offset") int offset);

    // 当前用户在这批商品里收藏了哪几个（一次 IN 查询，替掉原来的逐行相关子查询）
    @Select("""
        <script>
        SELECT goods_id FROM goods_favorite
        WHERE user_id = #{userId} AND goods_id IN
        <foreach collection="goodsIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Long> favoriteGoodsIds(@Param("userId") Long userId, @Param("goodsIds") List<Long> goodsIds);

    @Select("SELECT COUNT(*) FROM goods WHERE status=1")
    long countGoods();

    // 外卖店铺热度榜：真实订单×5 + 浏览×1；同分按评分
    @Select("""
        <script>
        SELECT s.id, s.name, s.cover, s.category, s.rating, s.monthly_sales AS monthlySales,
               s.min_price AS minPrice, s.delivery_fee AS deliveryFee, s.delivery_time AS deliveryTime,
               s.discount, s.notice, s.order_count AS orderCount, s.view_count AS shopViewCount,
               (s.order_count*5 + s.view_count*1) AS heat
        FROM shop s
        WHERE s.status=1
        ORDER BY heat DESC, s.rating DESC, s.id
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> hotShops(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM shop WHERE status=1")
    long countShops();

    // ---- 埋点：真实行为累加 ----
    @Update("UPDATE goods SET view_count = view_count + 1 WHERE id=#{id}")
    int viewGoods(@Param("id") Long id);

    @Insert("INSERT IGNORE INTO goods_favorite(goods_id,user_id) VALUES(#{goodsId},#{userId})")
    int addGoodsFavorite(@Param("goodsId") Long goodsId, @Param("userId") Long userId);

    @Delete("DELETE FROM goods_favorite WHERE goods_id=#{goodsId} AND user_id=#{userId}")
    int removeGoodsFavorite(@Param("goodsId") Long goodsId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM goods_favorite WHERE goods_id=#{goodsId} AND user_id=#{userId}")
    long hasGoodsFavorite(@Param("goodsId") Long goodsId, @Param("userId") Long userId);

    // 收藏数按实际表重算，避免并发下计数漂移
    @Update("UPDATE goods SET favorite_count=(SELECT COUNT(*) FROM goods_favorite f WHERE f.goods_id=#{id}) WHERE id=#{id}")
    int syncGoodsFavorite(@Param("id") Long id);

    @Update("UPDATE goods SET contact_count = contact_count + 1 WHERE id=#{id}")
    int contactGoods(@Param("id") Long id);

    @Update("UPDATE shop SET view_count = view_count + 1 WHERE id=#{id}")
    int viewShop(@Param("id") Long id);

    // 下单成功后累加店铺真实订单数
    @Update("UPDATE shop SET order_count = order_count + 1 WHERE id=#{id}")
    int increaseShopOrder(@Param("id") Long id);
}
