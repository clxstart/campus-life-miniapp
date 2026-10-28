package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserBlockMapper {

    @Insert("INSERT IGNORE INTO user_block(blocker_id, blocked_id) VALUES(#{blockerId}, #{blockedId})")
    int insert(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);

    @Delete("DELETE FROM user_block WHERE blocker_id = #{blockerId} AND blocked_id = #{blockedId}")
    int delete(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);

    @Select("SELECT COUNT(*) FROM user_block WHERE blocker_id = #{blockerId} AND blocked_id = #{blockedId}")
    int exists(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);

    /**
     * 「与我互相不可见」的用户 id 集合：
     * 我拉黑的人 + 拉黑我的人。双向语义，避免被拉黑方继续单向骚扰。
     */
    @Select("""
        SELECT blocked_id AS id FROM user_block WHERE blocker_id = #{userId}
        UNION
        SELECT blocker_id AS id FROM user_block WHERE blocked_id = #{userId}
        """)
    List<Long> hiddenUserIds(@Param("userId") Long userId);

    /** 我拉黑的人（用于设置页展示与解除）。 */
    @Select("""
        SELECT b.blocked_id AS userId, u.nickname, u.avatar, b.created_at AS createdAt
        FROM user_block b LEFT JOIN `user` u ON u.id = b.blocked_id
        WHERE b.blocker_id = #{blockerId}
        ORDER BY b.created_at DESC
        """)
    List<Map<String, Object>> myBlocks(@Param("blockerId") Long blockerId);
}
