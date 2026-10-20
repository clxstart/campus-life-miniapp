package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 全站搜索：帖子 / 圈子 / 二手商品 / 活动 / 兼职 / 外卖店铺 / 外卖商品。
// 所有关键词均为参数绑定，不做字符串拼接。
@Mapper
public interface SearchMapper {

    // ---- 分类计数：一次查出每类命中多少，供结果页 tab 显示数字 ----
    // food = 外卖店铺 + 店内商品（去重后取并集数量，用 EXISTS 避免重复计数）
    @Select("""
        SELECT
          (SELECT COUNT(*) FROM post p WHERE p.status=1 AND (p.title LIKE #{like} OR p.content LIKE #{like})) AS post,
          (SELECT COUNT(*) FROM circle c WHERE c.status=1 AND (c.name LIKE #{like} OR c.description LIKE #{like} OR c.category LIKE #{like})) AS circle,
          (SELECT COUNT(*) FROM goods g WHERE g.status=1 AND (g.title LIKE #{like} OR g.description LIKE #{like} OR g.category LIKE #{like})) AS goods,
          (SELECT COUNT(*) FROM campus_activity a WHERE a.status=1 AND (a.title LIKE #{like} OR a.location LIKE #{like})) AS activity,
          (SELECT COUNT(*) FROM job j WHERE j.title LIKE #{like} OR j.location LIKE #{like} OR j.status LIKE #{like}) AS job,
          (SELECT (SELECT COUNT(*) FROM shop s WHERE s.name LIKE #{like} OR s.category LIKE #{like} OR s.notice LIKE #{like})
                + (SELECT COUNT(*) FROM shop_goods g WHERE g.status=1 AND (g.name LIKE #{like} OR g.category LIKE #{like}))) AS food
        """)
    Map<String,Object> counts(@Param("like") String like);

    @Select("""
        <script>
        SELECT p.id, p.user_id AS authorId, p.title, p.content, p.image, p.tag, p.circle_id AS circleId,
               p.location, p.like_count AS likeCount, p.comment_count AS commentCount,
               p.created_at AS createdAt, u.nickname AS authorNickname, u.avatar AS authorAvatar,
               (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id AND l.user_id=#{userId})>0 AS liked
        FROM post p LEFT JOIN `user` u ON u.id=p.user_id
        WHERE p.status=1 AND (p.title LIKE #{like} OR p.content LIKE #{like})
        ORDER BY p.created_at DESC, p.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> posts(@Param("like") String like, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT c.id, c.name, c.slug, c.category, c.description, c.cover, c.theme,
               c.member_count AS memberCount, c.post_count AS postCount,
               (SELECT COUNT(*) FROM circle_member m WHERE m.circle_id=c.id AND m.user_id=#{userId})>0 AS joined
        FROM circle c
        WHERE c.status=1 AND (c.name LIKE #{like} OR c.description LIKE #{like} OR c.category LIKE #{like})
        ORDER BY c.hot DESC, c.member_count DESC, c.sort
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> circles(@Param("like") String like, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT g.id, g.user_id AS userId, g.mode, g.title, g.price, g.condition_level AS conditionLevel,
               g.category, g.image, g.description, g.created_at AS createdAt,
               u.nickname AS sellerName
        FROM goods g LEFT JOIN `user` u ON u.id=g.user_id
        WHERE g.status=1 AND (g.title LIKE #{like} OR g.description LIKE #{like} OR g.category LIKE #{like})
        ORDER BY g.created_at DESC, g.id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> goods(@Param("like") String like,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT a.id, a.title, a.poster, a.time_desc AS timeDesc, a.location, a.enrolled,
               (SELECT COUNT(*) FROM activity_enroll e WHERE e.activity_id=a.id AND e.user_id=#{userId})>0 AS enrolledByMe
        FROM campus_activity a
        WHERE a.status=1 AND (a.title LIKE #{like} OR a.location LIKE #{like})
        ORDER BY a.id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> activities(@Param("like") String like, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT id, title, day_salary AS daySalary, settle, location, time_desc AS timeDesc, status
        FROM job
        WHERE title LIKE #{like} OR location LIKE #{like} OR status LIKE #{like}
        ORDER BY id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> jobs(@Param("like") String like,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT s.id, s.name, s.cover, s.category, s.rating, s.monthly_sales AS monthlySales,
               s.min_price AS minPrice, s.delivery_fee AS deliveryFee, s.delivery_time AS deliveryTime, s.discount
        FROM shop s
        WHERE s.name LIKE #{like} OR s.category LIKE #{like} OR s.notice LIKE #{like}
        ORDER BY s.monthly_sales DESC, s.id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> shops(@Param("like") String like,
        @Param("limit") int limit, @Param("offset") int offset);

    // 外卖商品：菜名命中时返回所属店铺，便于直接跳点餐页
    @Select("""
        SELECT g.id, g.name, g.price, g.image, g.category, g.monthly_sales AS monthlySales,
               g.shop_id AS shopId, s.name AS shopName
        FROM shop_goods g JOIN shop s ON s.id=g.shop_id
        WHERE g.status=1 AND (g.name LIKE #{like} OR g.category LIKE #{like})
        ORDER BY g.monthly_sales DESC, g.id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> shopGoods(@Param("like") String like,
        @Param("limit") int limit, @Param("offset") int offset);
}
