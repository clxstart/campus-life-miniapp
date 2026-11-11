package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 段位与限时特权的数据访问。
 *
 * 段位不落库：读时由 user_growth.g_total 推导（见 TierService），
 * 这里只负责读配置、读成长值、读/写限时特权。
 */
@Mapper
public interface TierMapper {

    @Select("""
        SELECT tier_code AS tierCode, name, g_threshold AS gThreshold,
               s_threshold AS sThreshold, honor_label AS honorLabel, enabled, sort
        FROM tier_config ORDER BY g_threshold, sort
        """)
    List<Map<String, Object>> tiers();

    @Select("SELECT COALESCE(g_total, 0) FROM user_growth WHERE user_id = #{userId}")
    Integer growth(@Param("userId") Long userId);

    /**
     * 未过期的限时特权。
     *
     * 到期比较用调用方传入的 {@code at}，不再写死 SQL 的 NOW()：
     * 这样「生效段位」「授体验」「配置缓存 TTL」共用同一个 Clock，
     * 测试才能用固定时钟验证「授体验当天有效 / 第 8 天失效」。
     * 生产环境 TierService 传 LocalDateTime.now(Clock.systemDefaultZone())，与 NOW() 等价。
     */
    @Select("""
        SELECT priv_key AS privKey, expires_at AS expiresAt
        FROM user_privilege
        WHERE user_id = #{userId} AND active = 1
          AND (expires_at IS NULL OR expires_at > #{at})
        """)
    List<Map<String, Object>> activePrivileges(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    /** 发放限时特权；同一 key 重复发放以最后一次到期时间为准。 */
    @Insert("""
        INSERT INTO user_privilege(user_id, priv_key, active, expires_at)
        VALUES(#{userId}, #{privKey}, 1, #{expiresAt})
        ON DUPLICATE KEY UPDATE active = 1, activated_at = NOW(), expires_at = VALUES(expires_at)
        """)
    int grant(@Param("userId") Long userId, @Param("privKey") String privKey,
              @Param("expiresAt") LocalDateTime expiresAt);
}