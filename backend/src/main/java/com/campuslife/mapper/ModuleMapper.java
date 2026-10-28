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

    /** 分页版商品列表（与 selectGoods 同一套过滤条件，多 LIMIT/OFFSET）。 */
    @Select("""
        <script>
        SELECT g.id, g.mode, g.title, g.price, g.condition_level AS conditionLevel,
               g.category, g.image, g.images, g.description, g.created_at AS createdAt,
               g.school_id AS schoolId,
               u.id AS sellerId, u.nickname AS sellerNickname
        FROM goods g LEFT JOIN `user` u ON u.id = g.user_id
        WHERE g.status = 1 AND g.audit_status = 'passed' AND g.mode = #{mode}
        <if test="category != null and category != ''">AND g.category = #{category}</if>
        <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
        ORDER BY g.created_at DESC, g.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> selectGoodsPaged(@Param("mode") Integer mode, @Param("category") String category,
        @Param("schoolId") Long schoolId, @Param("limit") int limit, @Param("offset") int offset);

    /** 分页用的总数（过滤条件必须与 selectGoodsPaged 完全一致，否则分页会错位）。 */
    @Select("""
        <script>
        SELECT COUNT(*) FROM goods g
        WHERE g.status = 1 AND g.audit_status = 'passed' AND g.mode = #{mode}
        <if test="category != null and category != ''">AND g.category = #{category}</if>
        <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
        </script>
        """)
    long countGoods(@Param("mode") Integer mode, @Param("category") String category, @Param("schoolId") Long schoolId);

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
        <!-- 上限保护：不分页时也要有硬上限，否则兼职涨起来会把整表塞进响应体 -->
        LIMIT 200
        </script>
        """)
    List<Map<String, Object>> selectJobs(@Param("settle") String settle, @Param("keyword") String keyword,
        @Param("schoolId") Long schoolId);

    /** 分页版兼职列表（过滤条件与 selectJobs 一致）。 */
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
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          1 = 1
        </where>
        ORDER BY j.created_at DESC, j.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> selectJobsPaged(@Param("settle") String settle, @Param("keyword") String keyword,
        @Param("schoolId") Long schoolId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM job j
        <where>
          j.audit_status = 'passed' AND j.status = '招聘中' AND
          <if test="settle != null and settle != ''">j.settle = #{settle} AND</if>
          <if test="keyword != null and keyword != ''">
            (j.title LIKE #{keyword} OR j.location LIKE #{keyword} OR j.description LIKE #{keyword}) AND
          </if>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countJobs(@Param("settle") String settle, @Param("keyword") String keyword, @Param("schoolId") Long schoolId);

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
               a.enroll_mode AS enrollMode, a.enroll_hint AS enrollHint, a.enroll_max_files AS enrollMaxFiles,
        u.nickname AS publisherName,
               CASE WHEN e.id IS NULL THEN 0 ELSE 1 END AS enrolledByMe,
               e.status AS myEnrollStatus
        FROM campus_activity a
        LEFT JOIN activity_enroll e ON e.activity_id = a.id AND e.user_id = #{userId}
        LEFT JOIN `user` u ON u.id = a.user_id
        WHERE a.status = 1
        <!-- 按校隔离：校园活动是各校自己的；schoolId 为 null 时看全部学校 -->
        <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        ORDER BY a.created_at DESC, a.id DESC
        <!-- 上限保护：活动不分页时也要有硬上限，避免首屏返回全量 -->
        LIMIT 200
        </script>
        """)
    List<Map<String, Object>> selectActivities(@Param("userId") Long userId, @Param("schoolId") Long schoolId);

    /** 分页版活动列表（过滤条件与 selectActivities 一致）。 */
    @Select("""
        <script>
        SELECT a.id, a.title, a.poster, a.time_desc AS timeDesc, a.location, a.enrolled,
               a.description, a.user_id AS userId, a.created_at AS createdAt, a.school_id AS schoolId,
               a.enroll_mode AS enrollMode, a.enroll_hint AS enrollHint, a.enroll_max_files AS enrollMaxFiles,
               u.nickname AS publisherName,
               CASE WHEN e.id IS NULL THEN 0 ELSE 1 END AS enrolledByMe,
               e.status AS myEnrollStatus
        FROM campus_activity a
        LEFT JOIN activity_enroll e ON e.activity_id = a.id AND e.user_id = #{userId}
        LEFT JOIN `user` u ON u.id = a.user_id
        WHERE a.status = 1
        <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> selectActivitiesPaged(@Param("userId") Long userId, @Param("schoolId") Long schoolId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM campus_activity a
        WHERE a.status = 1
        <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        </script>
        """)
    long countActivities(@Param("schoolId") Long schoolId);

    // 发布活动：报名数从 0 起算，由 activity_enroll 累加；school_id 必须写入
    // enroll_mode：simple=一键报名（默认），material=填表+材料+审核
    @Insert("""
        INSERT INTO campus_activity(title, poster, time_desc, location, enrolled, status, user_id, description, school_id,
                                    enroll_mode, enroll_hint, enroll_max_files)
        VALUES(#{title}, #{poster}, #{timeDesc}, #{location}, 0, 1, #{userId}, #{description}, #{schoolId},
               #{enrollMode}, #{enrollHint}, #{enrollMaxFiles})
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

    @Select("SELECT id, status, enroll_mode AS enrollMode, enroll_hint AS enrollHint, enroll_max_files AS enrollMaxFiles, title FROM campus_activity WHERE id = #{id}")
    Map<String, Object> selectActivity(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM activity_enroll WHERE activity_id = #{activityId} AND user_id = #{userId}")
    int countEnrollment(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Select("SELECT id, status FROM activity_enroll WHERE activity_id = #{activityId} AND user_id = #{userId} LIMIT 1")
    Map<String, Object> myEnrollment(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Insert("INSERT INTO activity_enroll(activity_id, user_id) VALUES(#{activityId}, #{userId})")
    int insertEnrollment(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /** 材料模式报名：带表单字段与审核状态（pending 待审核） */
    @Insert("""
        INSERT INTO activity_enroll(activity_id, user_id, status, real_name, student_no, phone, remark)
        VALUES(#{activityId}, #{userId}, #{status}, #{realName}, #{studentNo}, #{phone}, #{remark})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertEnrollmentWithForm(Map<String, Object> row);

    @Insert("INSERT INTO activity_enroll_material(enroll_id, url, file_name) VALUES(#{enrollId}, #{url}, #{fileName})")
    int insertEnrollMaterial(@Param("enrollId") Long enrollId, @Param("url") String url, @Param("fileName") String fileName);

    @Select("SELECT id, url, file_name AS fileName FROM activity_enroll_material WHERE enroll_id = #{enrollId} ORDER BY id")
    List<Map<String, Object>> enrollMaterials(@Param("enrollId") Long enrollId);

    /** 审核列表：按活动 + 状态筛选（status 为空则全部） */
    @Select("""
        <script>
        SELECT e.id, e.activity_id AS activityId, e.user_id AS userId, e.status, e.real_name AS realName,
               e.student_no AS studentNo, e.phone, e.remark, e.audit_reason AS auditReason,
               e.created_at AS createdAt, e.audited_at AS auditedAt,
               a.title AS activityTitle, u.nickname AS nickname, u.school AS school
        FROM activity_enroll e
        JOIN campus_activity a ON a.id = e.activity_id
        LEFT JOIN `user` u ON u.id = e.user_id
        <where>
          <if test="activityId != null">AND e.activity_id = #{activityId}</if>
          <if test="status != null and status != ''">AND e.status = #{status}</if>
          <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        </where>
        ORDER BY e.created_at DESC, e.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> enrollments(@Param("activityId") Long activityId, @Param("status") String status,
                                          @Param("schoolId") Long schoolId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM activity_enroll e JOIN campus_activity a ON a.id = e.activity_id
        <where>
          <if test="activityId != null">AND e.activity_id = #{activityId}</if>
          <if test="status != null and status != ''">AND e.status = #{status}</if>
          <if test="schoolId != null">AND a.school_id = #{schoolId}</if>
        </where>
        </script>
        """)
    long countEnrollments(@Param("activityId") Long activityId, @Param("status") String status, @Param("schoolId") Long schoolId);

    @Select("SELECT id, activity_id AS activityId, user_id AS userId, status FROM activity_enroll WHERE id = #{id}")
    Map<String, Object> enrollBrief(@Param("id") Long id);

    @Update("""
        UPDATE activity_enroll SET status = #{status}, audit_reason = #{reason},
               audited_at = NOW(), audited_by = #{adminId}
        WHERE id = #{id} AND status = 'pending'
        """)
    int decideEnrollment(@Param("id") Long id, @Param("status") String status,
                         @Param("reason") String reason, @Param("adminId") Long adminId);

    @Update("UPDATE campus_activity SET enrolled = enrolled + 1 WHERE id = #{id} AND status = 1")
    int incrementEnrollment(@Param("id") Long id);

    /** 活动编辑：报名模式配置（管理端） */
    @Update("""
        UPDATE campus_activity SET enroll_mode = #{enrollMode}, enroll_hint = #{enrollHint},
               enroll_max_files = #{enrollMaxFiles}
        WHERE id = #{id}
        """)
    int updateActivityEnrollConfig(@Param("id") Long id, @Param("enrollMode") String enrollMode,
                                   @Param("enrollHint") String enrollHint, @Param("enrollMaxFiles") int enrollMaxFiles);
}

