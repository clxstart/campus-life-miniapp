package com.campuslife.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModuleMapper {
    @Select("""
        <script>
        SELECT g.id, g.mode, g.title, g.price, g.condition_level AS conditionLevel,
               g.category, g.image, g.images, g.description, g.created_at AS createdAt,
               g.school_id AS schoolId,
               u.id AS sellerId, u.nickname AS sellerNickname
        FROM goods g LEFT JOIN `user` u ON u.id = g.user_id
        WHERE g.status = 1 AND g.audit_status = 'passed' AND g.mode = #{mode}
        <if test="category != null and category != ''">AND g.category = #{category}</if>
        <!-- 按校隔离：schoolId 为 null 表示看全部学校（异地无法面交，默认只看本校） -->
        <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
        ORDER BY g.created_at DESC, g.id DESC
        </script>
        """)
    List<Map<String, Object>> selectGoods(@Param("mode") Integer mode, @Param("category") String category,
        @Param("schoolId") Long schoolId);

    @Select("""
        SELECT g.id, g.mode, g.title, g.price, g.condition_level AS conditionLevel,
               g.category, g.image, g.images, g.description, g.created_at AS createdAt,
               g.school_id AS schoolId,
               u.id AS sellerId, u.nickname AS sellerNickname
        FROM goods g LEFT JOIN `user` u ON u.id = g.user_id
        WHERE g.id = #{id} AND g.status = 1 AND g.audit_status = 'passed'
        """)
    Map<String, Object> selectGood(@Param("id") Long id);

    // 发布商品：school_id 由发布者的学校决定，避免新商品变成无主数据
    @Insert("""
        INSERT INTO goods(user_id, mode, title, price, condition_level, category, image, images, description, school_id, status, audit_status, audit_reason, audit_at)
        VALUES(#{userId}, #{mode}, #{title}, #{price}, #{conditionLevel}, #{category}, #{image}, #{images}, #{description}, #{schoolId}, #{status}, #{auditStatus}, #{auditReason}, #{auditAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertGood(Map<String, Object> params);

    // 只能删除自己发布的商品
    @Delete("DELETE FROM goods WHERE id=#{id} AND user_id=#{userId}")
    int deleteGood(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
        <script>
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location,
               j.time_desc AS timeDesc, j.status, j.description, j.user_id AS userId,
               j.created_at AS createdAt, j.school_id AS schoolId, u.nickname AS publisherName
        FROM job j LEFT JOIN `user` u ON u.id = j.user_id
        <where>
          j.audit_status = 'passed' AND j.status = '招聘中' AND
          <if test="settle != null and settle != ''">j.settle = #{settle} AND</if>
          <if test="keyword != null and keyword != ''">
            (j.title LIKE #{keyword} OR j.location LIKE #{keyword} OR j.description LIKE #{keyword}) AND
          </if>
          <!-- 按校隔离：校内兼职，schoolId 为 null 时看全部学校 -->
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          1 = 1
        </where>
        ORDER BY j.created_at DESC, j.id DESC
        </script>
        """)
    List<Map<String, Object>> selectJobs(@Param("settle") String settle, @Param("keyword") String keyword,
        @Param("schoolId") Long schoolId);

    // 发布兼职：与 goods 同一套归属模型，发布者即当前用户；school_id 必须写入
    @Insert("""
        INSERT INTO job(title, day_salary, settle, location, time_desc, status, user_id, description, school_id, audit_status, audit_reason, audit_at)
        VALUES(#{title}, #{daySalary}, #{settle}, #{location}, #{timeDesc}, #{status}, #{userId}, #{description}, #{schoolId}, #{auditStatus}, #{auditReason}, #{auditAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertJob(Map<String, Object> params);

    @Select("""
        <script>
        SELECT a.id, a.title, a.poster, a.time_desc AS timeDesc, a.location, a.enrolled,
               a.description, a.user_id AS userId, a.created_at AS createdAt, a.school_id AS schoolId,
        u.nickname AS publisherName,
               CASE WHEN e.id IS NULL THEN 0 ELSE 1 END AS enrolledByMe
        FROM campus_activity a
        LEFT JOIN activity_enroll e ON e.activity_id = a.id AND e.user_id = #{userId}
        LEFT JOIN `user` u ON u.id = a.user_id
        WHERE a.status = 1
        <!-- 按校隔离：校园活动是各校自己的；schoolId 为 null 时看全部学校 -->
        <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        ORDER BY a.created_at DESC, a.id DESC
        </script>
        """)
    List<Map<String, Object>> selectActivities(@Param("userId") Long userId, @Param("schoolId") Long schoolId);

    // 发布活动：报名数从 0 起算，由 activity_enroll 累加；school_id 必须写入
    @Insert("""
        INSERT INTO campus_activity(title, poster, time_desc, location, enrolled, status, user_id, description, school_id)
        VALUES(#{title}, #{poster}, #{timeDesc}, #{location}, 0, 1, #{userId}, #{description}, #{schoolId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertActivity(Map<String, Object> params);

    // 我发布的内容：按类型统计，供个人中心「我的发布」使用
    @Select("""
        <script>
        SELECT
          (SELECT COUNT(*) FROM post WHERE user_id=#{userId} AND status=1) AS post,
          (SELECT COUNT(*) FROM goods WHERE user_id=#{userId} AND status=1) AS goods,
          (SELECT COUNT(*) FROM job WHERE user_id=#{userId}) AS job,
          (SELECT COUNT(*) FROM campus_activity WHERE user_id=#{userId} AND status=1) AS activity
        </script>
        """)
    Map<String, Object> countMyPublish(@Param("userId") Long userId);

    @Select("SELECT id, status FROM campus_activity WHERE id = #{id}")
    Map<String, Object> selectActivity(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM activity_enroll WHERE activity_id = #{activityId} AND user_id = #{userId}")
    int countEnrollment(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Insert("INSERT INTO activity_enroll(activity_id, user_id) VALUES(#{activityId}, #{userId})")
    int insertEnrollment(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Update("UPDATE campus_activity SET enrolled = enrolled + 1 WHERE id = #{id} AND status = 1")
    int incrementEnrollment(@Param("id") Long id);
}
