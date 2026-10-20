package com.campuslife.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;
@Mapper public interface UserMapper extends BaseMapper<User> {
    // 个人主页帖子流：服务端按作者分页，替代原先「取前 50 条再本地筛」的做法。
    @Select("""
        <script>
        SELECT p.id, p.user_id AS authorId, p.title, p.content, p.image, p.tag, p.circle_id AS circleId,
               p.location, p.like_count AS likeCount, p.comment_count AS commentCount, p.view_count AS viewCount,
               p.created_at AS createdAt, u.nickname AS authorNickname, u.avatar AS authorAvatar,
               (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id AND l.user_id=#{viewerId})>0 AS liked,
               (SELECT COUNT(*) FROM follow f WHERE f.follower_id=#{viewerId} AND f.followee_id=p.user_id)>0 AS followed
        FROM post p LEFT JOIN `user` u ON u.id=p.user_id
        WHERE p.status=1 AND p.user_id=#{userId}
        ORDER BY p.created_at DESC, p.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> postsByAuthor(@Param("userId") Long userId, @Param("viewerId") Long viewerId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM post WHERE status=1 AND user_id=#{userId}")
    long countPostsByAuthor(@Param("userId") Long userId);
}
