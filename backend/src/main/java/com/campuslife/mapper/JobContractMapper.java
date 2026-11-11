package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.JobContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.Map;

/**
 * 履约单读写。
 *
 * 每个状态迁移都写成「WHERE id=? AND status=当前状态」，靠影响行数判断成败：
 * 同一个人连点两次、双方同时确认、管理端与用户同时操作，都只会有一个请求真正改变状态。
 */
@Mapper
public interface JobContractMapper extends BaseMapper<JobContract> {

    @Update("""
        UPDATE job_contract SET status = 'working', started_at = NOW()
        WHERE id = #{id} AND status = 'created'
        """)
    int start(@Param("id") Long id);

    /** 承接方提交工时：首次提交顺带把状态推到 pending_confirm。 */
    @Update("""
        UPDATE job_contract
        SET worker_hours = #{hours}, submitted_at = NOW(),
            status = IF(status = 'working', 'pending_confirm', status)
        WHERE id = #{id} AND status IN ('working', 'pending_confirm')
        """)
    int setWorkerHours(@Param("id") Long id, @Param("hours") BigDecimal hours);

    /** 发布方确认工时（同一列组，只是语义不同，便于各自记录）。 */
    @Update("""
        UPDATE job_contract
        SET employer_hours = #{hours}, submitted_at = NOW(),
            status = IF(status = 'working', 'pending_confirm', status)
        WHERE id = #{id} AND status IN ('working', 'pending_confirm')
        """)
    int setEmployerHours(@Param("id") Long id, @Param("hours") BigDecimal hours);

    /**
     * 结算：只有 pending_confirm 能进 settled，且 agreed_hours 就是 worker_hours。
     * 影响 0 行说明已被结算/已进争议 → 调用方按幂等或冲突处理。
     */
    @Update("""
        UPDATE job_contract
        SET agreed_hours = #{hours}, status = 'settled',
            both_confirmed_at = NOW(), settled_at = NOW()
        WHERE id = #{id} AND status = 'pending_confirm'
        """)
    int settle(@Param("id") Long id, @Param("hours") BigDecimal hours);

    @Update("""
        UPDATE job_contract SET status = 'disputed'
        WHERE id = #{id} AND status IN ('working', 'pending_confirm')
        """)
    int dispute(@Param("id") Long id);

    /** 开工前取消：整单按未履约处理。 */
    @Update("""
        UPDATE job_contract SET status = 'cancelled', cancel_reason = #{reason}
        WHERE id = #{id} AND status = 'created'
        """)
    int cancelBeforeStart(@Param("id") Long id, @Param("reason") String reason);

    /** 开工后取消：只按双方确认的履约量结算，其余退回。 */
    @Update("""
        UPDATE job_contract SET status = 'cancelled', cancel_reason = #{reason}
        WHERE id = #{id} AND status IN ('working', 'pending_confirm')
        """)
    int cancelAfterStart(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
        UPDATE job_contract
        SET status = #{decision}, agreed_hours = #{hours},
            settled_at = IF(#{decision} = 'settled', NOW(), settled_at)
        WHERE id = #{id} AND status = 'disputed'
        """)
    int arbitrate(@Param("id") Long id, @Param("decision") String decision, @Param("hours") BigDecimal hours);

    @Select("SELECT * FROM job_contract WHERE job_id = #{jobId} AND worker_id = #{workerId}")
    JobContract findByJobAndWorker(@Param("jobId") Long jobId, @Param("workerId") Long workerId);

    /**
     * F10 交易埋点：这份合约结算**实际**发过多少积分给承接方（0 = 没发）。
     *
     * 读 point_log 而不是 task_log：日上限命中时 task_log 仍会插一行（p 仍是奖励值），
     * 但不产生流水；争议冻结 / 整单退回也从未发分。
     * 「结算完成，+5 积分已到账」只在有正数流水时显示，避免页面与积分明细对不上。
     */
    @Select("""
        SELECT COALESCE(MAX(amount), 0) FROM point_log
        WHERE user_id = #{workerId} AND biz_key = CONCAT('job_settle:contract:', #{contractId})
        """)
    int settledPoints(@Param("workerId") Long workerId, @Param("contractId") Long contractId);

    /** 岗位下全部履约单：只用于多名额岗位的聚合状态展示，不用于权限判定。 */
    @Select("SELECT * FROM job_contract WHERE job_id = #{jobId} ORDER BY id")
    List<JobContract> byJob(@Param("jobId") Long jobId);

    @Select("""
        SELECT c.id, c.job_id AS jobId, c.worker_id AS workerId, c.employer_id AS employerId,
               c.status, c.fee_mode_snapshot AS feeModeSnapshot, c.fee_value_snapshot AS feeValueSnapshot,
               c.pay_cents_snapshot AS payCentsSnapshot, c.pay_unit_snapshot AS payUnitSnapshot,
               c.worker_hours AS workerHours, c.employer_hours AS employerHours, c.agreed_hours AS agreedHours,
               c.cancel_reason AS cancelReason, c.started_at AS startedAt, c.submitted_at AS submittedAt,
               c.both_confirmed_at AS bothConfirmedAt, c.settled_at AS settledAt, c.created_at AS createdAt,
               j.title AS jobTitle, j.`scope` AS scope, j.workflow_status AS jobWorkflowStatus,
               w.nickname AS workerName, e.nickname AS employerName
        FROM job_contract c
        JOIN job j ON j.id = c.job_id
        LEFT JOIN school s ON s.id = j.school_id
        LEFT JOIN `user` w ON w.id = c.worker_id
        LEFT JOIN `user` e ON e.id = c.employer_id
        WHERE c.id = #{id}
        """)
    java.util.Map<String, Object> detail(@Param("id") Long id);

    /**
     * 管理端履约单列表（争议处理台）。
     *
     * 只读聚合：履约单 + 岗位 + 双方昵称 + 最近一条争议。
     * 金额字段全部取 job_contract 的**快照列**，不 join 回 job 的当前费率 ——
     * 管理端看到的必须是"这一单当时约定的价格"，否则改价后争议双方各说各话。
     */
    @Select("""
        <script>
        SELECT c.id, c.job_id AS jobId, j.title AS jobTitle, j.school_id AS schoolId, s.name AS schoolName,
               j.`scope` AS scope, c.worker_id AS workerId, w.nickname AS workerName,
               c.employer_id AS employerId, e.nickname AS employerName,
               c.status, c.pay_cents_snapshot AS payCentsSnapshot, c.pay_unit_snapshot AS payUnitSnapshot,
               c.fee_mode_snapshot AS feeModeSnapshot, c.fee_value_snapshot AS feeValueSnapshot,
               c.worker_hours AS workerHours, c.employer_hours AS employerHours,
               c.agreed_hours AS agreedHours, c.cancel_reason AS cancelReason,
               c.created_at AS createdAt, c.settled_at AS settledAt,
               d.id AS disputeId, d.status AS disputeStatus, d.reason AS disputeReason
        FROM job_contract c
        JOIN job j ON j.id = c.job_id
        LEFT JOIN school s ON s.id = j.school_id
        LEFT JOIN `user` w ON w.id = c.worker_id
        LEFT JOIN `user` e ON e.id = c.employer_id
        LEFT JOIN job_dispute d ON d.id = (SELECT MAX(x.id) FROM job_dispute x WHERE x.contract_id = c.id)
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="jobId != null">c.job_id = #{jobId} AND</if>
          <if test="status != null and status != ''">c.status = #{status} AND</if>
          1 = 1
        </where>
        ORDER BY c.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> adminList(@Param("schoolId") Long schoolId, @Param("jobId") Long jobId,
                                        @Param("status") String status,
                                        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM job_contract c
        JOIN job j ON j.id = c.job_id
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="jobId != null">c.job_id = #{jobId} AND</if>
          <if test="status != null and status != ''">c.status = #{status} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long adminCount(@Param("schoolId") Long schoolId, @Param("jobId") Long jobId,
                    @Param("status") String status);
}
