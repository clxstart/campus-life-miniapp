package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 圈子相关数据访问；所有参数绑定，不拼接来自请求的 SQL。
@Mapper
public interface CircleMapper {
    @Select("""
        <script>
        SELECT c.id, c.name, c.slug, c.category, c.description, c.cover, c.theme,
               c.member_count AS memberCount, c.post_count AS postCount, c.hot, c.sort,
               c.last_message_at AS lastMessageAt, c.last_message AS lastMessage,
               c.school_id AS schoolId,
               (SELECT COUNT(*) FROM circle_member m WHERE m.circle_id=c.id AND m.user_id=#{userId})>0 AS joined,
               (SELECT COUNT(*) FROM circle_message cm
                WHERE cm.circle_id=c.id AND cm.id > COALESCE(
                  (SELECT cr.last_read_id FROM circle_read cr WHERE cr.circle_id=c.id AND cr.user_id=#{userId}), 0
               ) AND EXISTS (SELECT 1 FROM circle_member m WHERE m.circle_id=c.id AND m.user_id=#{userId})) AS unreadCount
        FROM circle c
        WHERE c.status=1
        <if test="category != null and category != ''">AND c.category=#{category}</if>
        <if test="hotOnly">AND c.hot=1</if>
        <!-- 按校隔离：每校有自己的圈子；schoolId 为 null 时看全部学校 -->
        <if test="schoolId != null">AND c.school_id=#{schoolId}</if>
        ORDER BY COALESCE(c.last_message_at, '1970-01-01') DESC, c.sort, c.id
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> circles(@Param("userId") Long userId, @Param("category") String category,
        @Param("hotOnly") boolean hotOnly, @Param("limit") int limit, @Param("offset") int offset,
        @Param("schoolId") Long schoolId);

    @Select("""
        <script>
        SELECT COUNT(*) FROM circle c WHERE c.status=1
        <if test="category != null and category != ''">AND c.category=#{category}</if>
        <if test="hotOnly">AND c.hot=1</if>
        <if test="schoolId != null">AND c.school_id=#{schoolId}</if>
        </script>
        """)
    long countCircles(@Param("category") String category, @Param("hotOnly") boolean hotOnly,
        @Param("schoolId") Long schoolId);

    @Select("""
        SELECT c.id, c.name, c.slug, c.category, c.description, c.cover, c.theme,
               c.member_count AS memberCount, c.post_count AS postCount, c.hot, c.sort,
               c.last_message_at AS lastMessageAt, c.last_message AS lastMessage,
               c.streak_days AS streakDays, c.last_active_date AS lastActiveDate, c.spark_on AS sparkOn,
               (SELECT COUNT(*) FROM circle_member m WHERE m.circle_id=c.id AND m.user_id=#{userId})>0 AS joined,
               (SELECT COUNT(*) FROM circle_message cm
                WHERE cm.circle_id=c.id AND cm.id > COALESCE(
                  (SELECT cr.last_read_id FROM circle_read cr WHERE cr.circle_id=c.id AND cr.user_id=#{userId}), 0
               ) AND EXISTS (SELECT 1 FROM circle_member m WHERE m.circle_id=c.id AND m.user_id=#{userId})) AS unreadCount
        FROM circle c WHERE c.id=#{id} AND c.status=1
        """)
    Map<String,Object> circle(@Param("id") Long id, @Param("userId") Long userId);

    // 圈内帖子流：字段与 ForumMapper 的 page() 保持一致，前端可复用同一套卡片
    @Select("""
        <script>
        SELECT p.id, p.user_id AS userId, p.title, p.content, p.image, p.tag, p.location,
               p.like_count AS likeCount, p.comment_count AS commentCount, p.view_count AS viewCount,
               p.circle_id AS circleId, p.created_at AS createdAt,
               u.nickname AS authorNickname, u.avatar AS authorAvatar,
               (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id AND l.user_id=#{userId})>0 AS liked,
               (SELECT COUNT(*) FROM follow f WHERE f.follower_id=#{userId} AND f.followee_id=p.user_id)>0 AS followed,
               (SELECT COUNT(*) FROM favorite v WHERE v.post_id=p.id AND v.user_id=#{userId})>0 AS favored
        FROM post p LEFT JOIN `user` u ON u.id=p.user_id
        WHERE p.status=1 AND p.circle_id=#{circleId}
        ORDER BY p.created_at DESC, p.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> circlePosts(@Param("circleId") Long circleId, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM post WHERE status=1 AND circle_id=#{circleId}")
    long countCirclePosts(@Param("circleId") Long circleId);

    @Select("SELECT COUNT(*) FROM circle_member WHERE circle_id=#{circleId} AND user_id=#{userId}")
    long isMember(@Param("circleId") Long circleId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM circle_member WHERE circle_id=#{circleId}")
    long memberCount(@Param("circleId") Long circleId);

    /** 用户自建圈子的名额判断：无论待审还是已通过都占名额。 */
    @Select("SELECT COUNT(*) FROM circle WHERE creator_id = #{creatorId}")
    long countByCreator(@Param("creatorId") Long creatorId);

    /** 用户建圈：status=0 待审，creator_id 记录创建者。 */
    @Insert("""
        INSERT INTO circle(name, slug, category, description, cover, theme, hot, sort,
                           school_id, status, creator_id, member_count, audit_status, audit_reason, audit_at)
        VALUES(#{name}, #{slug}, #{category}, #{description}, #{cover}, #{theme}, 0, 0,
               #{schoolId}, 0, #{creatorId}, 1, #{auditStatus}, #{auditReason}, #{auditAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertUserCircle(Map<String, Object> row);

    @Insert("INSERT IGNORE INTO circle_member(circle_id,user_id,role) VALUES(#{circleId},#{userId},'owner')")
    int addOwner(@Param("circleId") Long circleId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM circle WHERE slug = #{slug}")
    long slugTaken(@Param("slug") String slug);

    @Insert("INSERT IGNORE INTO circle_member(circle_id,user_id,role) VALUES(#{circleId},#{userId},'member')")
    int join(@Param("circleId") Long circleId, @Param("userId") Long userId);

    @Delete("DELETE FROM circle_member WHERE circle_id=#{circleId} AND user_id=#{userId}")
    int leave(@Param("circleId") Long circleId, @Param("userId") Long userId);

    @Update("UPDATE circle SET member_count=(SELECT COUNT(*) FROM circle_member m WHERE m.circle_id=#{circleId}) WHERE id=#{circleId}")
    int syncMemberCount(@Param("circleId") Long circleId);

    @Update("UPDATE circle SET post_count=(SELECT COUNT(*) FROM post p WHERE p.circle_id=#{circleId} AND p.status=1) WHERE id=#{circleId}")
    int syncPostCount(@Param("circleId") Long circleId);

    @Select("SELECT category, COUNT(*) AS total FROM circle WHERE status=1 GROUP BY category ORDER BY total DESC")
    List<Map<String,Object>> categories();

    @Select("SELECT id,name,slug,category,theme FROM circle WHERE id=#{id} AND status=1")
    Map<String,Object> brief(@Param("id") Long id);

    // 推荐流：兴趣匹配（用户已加入的圈子）加权 + 热度 + 时效衰减。
    // 分数在同一 SQL 内算出，避免把全量帖子拉回应用层排序。
    @Select("""
        <script>
        SELECT p.id, p.user_id AS authorId, p.title, p.content, p.image, p.tag, p.circle_id AS circleId,
               p.location, p.like_count AS likeCount, p.comment_count AS commentCount, p.view_count AS viewCount,
               p.created_at AS createdAt, u.nickname AS authorNickname, u.avatar AS authorAvatar,
               c.name AS circleName,
               (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id AND l.user_id=#{userId})>0 AS liked,
               (SELECT COUNT(*) FROM follow f WHERE f.follower_id=#{userId} AND f.followee_id=p.user_id)>0 AS followed,
               (SELECT COUNT(*) FROM favorite v WHERE v.post_id=p.id AND v.user_id=#{userId})>0 AS favored,
               ROUND(
                 (p.like_count*3 + p.comment_count*5 + p.view_count*1)
                 * (1 + 0.6 * (SELECT COUNT(*) FROM circle_member m WHERE m.circle_id=p.circle_id AND m.user_id=#{userId}))
                 * EXP(-0.02 * TIMESTAMPDIFF(HOUR, p.created_at, NOW()))
               , 4) AS score
        FROM post p
        LEFT JOIN `user` u ON u.id=p.user_id
        LEFT JOIN circle c ON c.id=p.circle_id
        WHERE p.status=1
        ORDER BY score DESC, p.created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> recommend(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM post WHERE status=1")
    long countAllPosts();
}
