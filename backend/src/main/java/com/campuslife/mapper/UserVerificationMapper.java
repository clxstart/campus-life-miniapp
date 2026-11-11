package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.UserVerification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserVerificationMapper extends BaseMapper<UserVerification> {

    /**
     * 管理端列表：按用户所属学校过滤（校管只能看本校），状态命中实名或学生身份任一即可。
     *
     * 学校条件写在 SQL 里，避免先查出全平台再在内存里过滤。
     * 返回 Map 而不是实体：审核页要显示学校名，而 school.name 不在 user_verification 表里，
     * 用实体接就得再补一次 N+1 查询。
     *
     * @param scopeSchoolId 权限范围（校管=自己的学校，超管=null 不限）
     * @param schoolId      审核页上人工选的学校筛选（在校管范围内再收窄）
     * @param keyword       姓名 / 学号 模糊搜索
     */
    @Select("""
        <script>
        SELECT v.user_id AS userId, v.real_name_status AS realNameStatus, v.student_status AS studentStatus,
               v.provider, v.reject_reason AS rejectReason, v.reviewer_id AS reviewerId,
               v.verified_at AS verifiedAt, v.updated_at AS updatedAt,
               v.school_id AS schoolId, s.name AS schoolName,
               v.college, v.real_name AS realName, v.student_no AS studentNo, v.phone,
               v.submitted_at AS submittedAt, v.source
        FROM user_verification v
        JOIN `user` u ON u.id = v.user_id
        LEFT JOIN school s ON s.id = v.school_id
        <where>
          <if test="scopeSchoolId != null">u.school_id = #{scopeSchoolId}</if>
          <if test="schoolId != null and schoolId &gt; 0">AND v.school_id = #{schoolId}</if>
          <if test="status != null and status != ''">
            AND (v.real_name_status = #{status} OR v.student_status = #{status})
          </if>
          <if test="keyword != null and keyword != ''">
            AND (v.real_name LIKE CONCAT('%', #{keyword}, '%') OR v.student_no LIKE CONCAT('%', #{keyword}, '%'))
          </if>
        </where>
        ORDER BY (v.submitted_at IS NULL), v.submitted_at DESC, v.updated_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> adminList(@Param("scopeSchoolId") Long scopeSchoolId,
                                        @Param("schoolId") Long schoolId,
                                        @Param("status") String status,
                                        @Param("keyword") String keyword,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    /** 提交认证时校验学校真实存在且启用：不能让用户把 schoolId 填成任意数字。 */
    @Select("SELECT COUNT(*) FROM school WHERE id = #{schoolId} AND status = 1")
    int schoolExists(@Param("schoolId") Long schoolId);
}