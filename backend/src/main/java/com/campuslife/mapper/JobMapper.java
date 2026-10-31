package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.Job;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 岗位读写。
 *
 * 状态流转**全部走条件 UPDATE**：把「当前状态」写进 WHERE，靠影响行数判断是否成功。
 * 这样并发下只有一个请求能改成功，不需要先查后写、也不需要在 Java 里加锁
 * （单机锁挡不住多实例，先查后写本身就有 TOCTOU 窗口）。
 */
@Mapper
public interface JobMapper extends BaseMapper<Job> {

    /**
     * §11.1 核心并发点：校内岗位先到先接。
     *
     * 只有「无人接 + 仍 open + 是校内岗位」这一次 UPDATE 命中的请求才算接单成功；
     * 影响行数 = 1 → 成功；= 0 → 已被别人接走。**不要**改成先 SELECT 再 UPDATE，
     * 两步之间就是别人插进来的窗口。
     */
    @Update("""
        UPDATE job SET taken_by = #{uid}, workflow_status = 'taken'
        WHERE id = #{jobId}
          AND taken_by IS NULL
          AND workflow_status = 'open'
          AND `scope` = 'campus'
        """)
    int takeCampus(@Param("jobId") Long jobId, @Param("uid") Long uid);

    /**
     * 校外岗位录用时占一个名额（headcount 上限靠条件 UPDATE 保证，不是先查再算）。
     *
     * 只有「已过审 + 仍在招 + 还有名额」时才能占用：影响行数 1 = 占到位；
     * 0 = 名额已满或已停止招募。同一报名重复录用也会因为 application 的状态条件被拦住。
     */
    @Update("""
        UPDATE job SET accepted_count = accepted_count + 1
        WHERE id = #{jobId}
          AND audit_status = 'passed'
          AND accepted_count < headcount
          AND workflow_status IN ('open', 'recruiting')
        """)
    int occupySeat(@Param("jobId") Long jobId);

    /** 通用状态流转（白名单校验在 Service，数据库这一层只保证「从 from 到 to」原子成立）。 */
    @Update("UPDATE job SET workflow_status = #{to} WHERE id = #{id} AND workflow_status = #{from}")
    int setWorkflow(@Param("id") Long id, @Param("to") String to, @Param("from") String from);

    /** 管理端配置平台费率：只改这两列，不动报酬与状态。 */
    @Update("""
        UPDATE job SET fee_mode = #{feeMode}, fee_value = #{feeValue}
        WHERE id = #{id}
        """)
    int setFee(@Param("id") Long id, @Param("feeMode") String feeMode, @Param("feeValue") int feeValue);

    /** 审核通过：恢复上架（并允许把之前被下架的岗位重新打开）。 */
    @Update("""
        UPDATE job SET audit_status = 'passed', audit_reason = #{reason}, audit_at = NOW(),
               status = '招聘中',
               workflow_status = IF(workflow_status = 'cancelled', 'open', workflow_status)
        WHERE id = #{id}
        """)
    int approve(@Param("id") Long id, @Param("reason") String reason);

    /** 下架/驳回：已结算的岗位不允许再改状态，否则历史账单的上下文就被抹掉了。 */
    @Update("""
        UPDATE job SET audit_status = 'rejected', audit_reason = #{reason}, audit_at = NOW(),
               status = '已下架', workflow_status = 'cancelled'
        WHERE id = #{id} AND workflow_status NOT IN ('settled', 'refunded')
        """)
    int reject(@Param("id") Long id, @Param("reason") String reason);

    // ---------- 用户端列表 ----------

    @Select("""
        <script>
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location,
               j.time_desc AS timeDesc, j.status, j.description, j.user_id AS userId,
               j.created_at AS createdAt, j.school_id AS schoolId,
               j.`scope` AS scope, j.employer_kind AS employerKind, j.partner_id AS partnerId,
               j.take_mode AS takeMode, j.category_key AS categoryKey,
               j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.fee_mode AS feeMode, j.fee_value AS feeValue, j.headcount,
               j.deadline_at AS deadlineAt, j.workflow_status AS workflowStatus,
               j.taken_by AS takenBy, j.accepted_count AS acceptedCount,
               j.audit_status AS auditStatus, u.nickname AS publisherName
        FROM job j LEFT JOIN `user` u ON u.id = j.user_id
        <where>
          j.audit_status = 'passed' AND j.workflow_status IN ('open', 'recruiting') AND
          <if test="settle != null and settle != ''">j.settle = #{settle} AND</if>
          <if test="keyword != null and keyword != ''">
            (j.title LIKE #{keyword} OR j.location LIKE #{keyword} OR j.description LIKE #{keyword}) AND
          </if>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="scope != null and scope != ''">j.`scope` = #{scope} AND</if>
          1 = 1
        </where>
        ORDER BY j.created_at DESC, j.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> list(@Param("settle") String settle, @Param("keyword") String keyword,
                                   @Param("schoolId") Long schoolId, @Param("scope") String scope,
                                   @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM job j
        <where>
          j.audit_status = 'passed' AND j.workflow_status IN ('open', 'recruiting') AND
          <if test="settle != null and settle != ''">j.settle = #{settle} AND</if>
          <if test="keyword != null and keyword != ''">
            (j.title LIKE #{keyword} OR j.location LIKE #{keyword} OR j.description LIKE #{keyword}) AND
          </if>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="scope != null and scope != ''">j.`scope` = #{scope} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long count(@Param("settle") String settle, @Param("keyword") String keyword,
               @Param("schoolId") Long schoolId, @Param("scope") String scope);

    // ---------- 详情 / 我的 ----------

    @Select("""
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location,
               j.time_desc AS timeDesc, j.status, j.description, j.user_id AS userId,
               j.created_at AS createdAt, j.school_id AS schoolId,
               j.`scope` AS scope, j.employer_kind AS employerKind, j.partner_id AS partnerId,
               j.take_mode AS takeMode, j.category_key AS categoryKey,
               j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.fee_mode AS feeMode, j.fee_value AS feeValue, j.headcount,
               j.deadline_at AS deadlineAt, j.workflow_status AS workflowStatus,
               j.taken_by AS takenBy, j.accepted_count AS acceptedCount,
               j.audit_status AS auditStatus, j.audit_reason AS auditReason, j.audit_at AS auditAt,
               u.nickname AS publisherName, s.name AS schoolName
        FROM job j
        LEFT JOIN `user` u ON u.id = j.user_id
        LEFT JOIN school s ON s.id = j.school_id
        WHERE j.id = #{id}
        """)
    Map<String, Object> detail(@Param("id") Long id);

    /** 我发布的（所有状态都看得到，包括待审核/已下架）。 */
    @Select("""
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location,
               j.time_desc AS timeDesc, j.status, j.description,
               j.created_at AS createdAt, j.school_id AS schoolId,
               j.`scope` AS scope, j.take_mode AS takeMode, j.category_key AS categoryKey,
               j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.headcount, j.deadline_at AS deadlineAt, j.workflow_status AS workflowStatus,
               j.taken_by AS takenBy, j.audit_status AS auditStatus, j.audit_reason AS auditReason
        FROM job j
        WHERE j.user_id = #{userId}
        ORDER BY j.id DESC LIMIT 200
        """)
    List<Map<String, Object>> minePublished(@Param("userId") Long userId);

    /** 我承接的（有履约单的岗位）。 */
    @Select("""
        SELECT j.id AS jobId, j.title, j.location, j.time_desc AS timeDesc, j.`scope` AS scope,
               j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.workflow_status AS workflowStatus, j.status,
               c.id AS contractId, c.status AS contractStatus,
               c.worker_hours AS workerHours, c.employer_hours AS employerHours,
               c.agreed_hours AS agreedHours, c.pay_cents_snapshot AS payCentsSnapshot,
               c.pay_unit_snapshot AS payUnitSnapshot,
               c.created_at AS contractCreatedAt
        FROM job_contract c JOIN job j ON j.id = c.job_id
        WHERE c.worker_id = #{userId}
        ORDER BY c.id DESC LIMIT 200
        """)
    List<Map<String, Object>> mineWorking(@Param("userId") Long userId);

    /**
     * 我作为**发布方**的履约单。
     *
     * 为什么必须有：`confirm-hours` 需要发布方也确认一次工时才能结算，
     * 如果发布方录用完就再也找不到这张单子，履约流程就断在半路。
     */
    @Select("""
        SELECT j.id AS jobId, j.title, j.location, j.time_desc AS timeDesc, j.`scope` AS scope,
               j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.workflow_status AS workflowStatus,
               c.id AS contractId, c.status AS contractStatus, c.worker_id AS workerId,
               u.nickname AS workerName,
               c.worker_hours AS workerHours, c.employer_hours AS employerHours,
               c.agreed_hours AS agreedHours, c.pay_cents_snapshot AS payCentsSnapshot,
               c.pay_unit_snapshot AS payUnitSnapshot,
               c.created_at AS contractCreatedAt
        FROM job_contract c
        JOIN job j ON j.id = c.job_id
        LEFT JOIN `user` u ON u.id = c.worker_id
        WHERE c.employer_id = #{userId}
        ORDER BY c.id DESC LIMIT 200
        """)
    List<Map<String, Object>> mineEmployer(@Param("userId") Long userId);

    // ---------- 管理端列表（含待审核） ----------

    @Select("""
        <script>
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location,
               j.time_desc AS timeDesc, j.status, j.description, j.user_id AS userId,
               j.created_at AS createdAt, j.school_id AS schoolId,
               j.`scope` AS scope, j.employer_kind AS employerKind, j.take_mode AS takeMode,
               j.category_key AS categoryKey, j.pay_cents AS payCents, j.pay_unit AS payUnit,
               j.fee_mode AS feeMode, j.fee_value AS feeValue, j.headcount,
               j.workflow_status AS workflowStatus, j.taken_by AS takenBy,
               j.audit_status AS auditStatus, j.audit_reason AS auditReason, j.audit_at AS auditAt,
               u.nickname AS publisherName, s.name AS schoolName
        FROM job j
        LEFT JOIN `user` u ON u.id = j.user_id
        LEFT JOIN school s ON s.id = j.school_id
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">j.title LIKE #{keyword} AND</if>
          <if test="pendingOnly">j.audit_status = 'pending' AND</if>
          1 = 1
        </where>
        ORDER BY j.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> adminList(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
                                        @Param("pendingOnly") boolean pendingOnly,
                                        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM job j
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">j.title LIKE #{keyword} AND</if>
          <if test="pendingOnly">j.audit_status = 'pending' AND</if>
          1 = 1
        </where>
        </script>
        """)
    long adminCount(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
                    @Param("pendingOnly") boolean pendingOnly);
}
