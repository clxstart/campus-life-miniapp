package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface ClientErrorMapper {

    @Insert("""
        INSERT INTO client_error(client, page, message, stack, user_agent, user_id)
        VALUES(#{client}, #{page}, #{message}, #{stack}, #{userAgent}, #{userId})
        """)
    int insert(Map<String, Object> row);

    @Select("""
        <script>
        SELECT id, client, page, message, stack, user_agent AS userAgent, user_id AS userId,
               created_at AS createdAt
        FROM client_error
        <where>
          <if test="client != null and client != ''">AND client = #{client}</if>
          <if test="keyword != null and keyword != ''">
            AND (message LIKE #{keyword} OR page LIKE #{keyword})
          </if>
        </where>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> list(@Param("client") String client, @Param("keyword") String keyword,
                                   @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM client_error
        <where>
          <if test="client != null and client != ''">AND client = #{client}</if>
          <if test="keyword != null and keyword != ''">
            AND (message LIKE #{keyword} OR page LIKE #{keyword})
          </if>
        </where>
        </script>
        """)
    long count(@Param("client") String client, @Param("keyword") String keyword);

    /** 今日错误数（系统状态页展示用）。 */
    @Select("SELECT COUNT(*) FROM client_error WHERE created_at >= CURDATE()")
    long countToday();

    /** 清理 30 天前的记录，避免无限增长。 */
    @Delete("DELETE FROM client_error WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int purgeOlderThan(@Param("days") int days);
}
