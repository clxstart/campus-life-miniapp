package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface ContentReportMapper {

    /** 同一个人对同一条内容只能举报一次（唯一键冲突时由服务层翻译成友好提示）。 */
    @Insert("""
        INSERT INTO content_report(target_type, target_id, reporter_id, reason, detail)
        VALUES(#{targetType}, #{targetId}, #{reporterId}, #{reason}, #{detail})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Map<String, Object> row);

    @Select("""
        SELECT id FROM content_report
        WHERE target_type = #{targetType} AND target_id = #{targetId} AND reporter_id = #{reporterId}
        LIMIT 1
        """)
    Long findByTargetAndReporter(@Param("targetType") String targetType,
                                 @Param("targetId") Long targetId,
                                 @Param("reporterId") Long reporterId);

    /** 管理端列表：带上举报人昵称与被举报内容的标题，省得再点一次。 */
    @Select("""
        <script>
        SELECT r.id, r.target_type AS targetType, r.target_id AS targetId, r.reason, r.detail,
               r.status, r.handled_by AS handledBy, r.handled_at AS handledAt, r.created_at AS createdAt,
               u.nickname AS reporterName,
               CASE r.target_type
                 WHEN 'post' THEN (SELECT p.title FROM post p WHERE p.id = r.target_id)
                 WHEN 'goods' THEN (SELECT g.title FROM goods g WHERE g.id = r.target_id)
                 WHEN 'comment' THEN (SELECT LEFT(c.content, 40) FROM `comment` c WHERE c.id = r.target_id)
                 WHEN 'user' THEN (SELECT us.nickname FROM `user` us WHERE us.id = r.target_id)
                 ELSE NULL
               END AS targetTitle
        FROM content_report r
        LEFT JOIN `user` u ON u.id = r.reporter_id
        <where>
          <if test="status != null and status != ''">AND r.status = #{status}</if>
          <if test="targetType != null and targetType != ''">AND r.target_type = #{targetType}</if>
        </where>
        ORDER BY r.created_at DESC, r.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> list(@Param("status") String status, @Param("targetType") String targetType,
                                   @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM content_report r
        <where>
          <if test="status != null and status != ''">AND r.status = #{status}</if>
          <if test="targetType != null and targetType != ''">AND r.target_type = #{targetType}</if>
        </where>
        </script>
        """)
    long count(@Param("status") String status, @Param("targetType") String targetType);

    @Select("SELECT id, status FROM content_report WHERE id = #{id}")
    Map<String, Object> brief(@Param("id") Long id);

    @Update("""
        UPDATE content_report SET status = #{status}, handled_by = #{operator}, handled_at = NOW()
        WHERE id = #{id} AND status = 'pending'
        """)
    int handle(@Param("id") Long id, @Param("status") String status, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM content_report WHERE status = 'pending'")
    long countPending();
}
