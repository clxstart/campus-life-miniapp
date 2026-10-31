package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.JobContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

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
        LEFT JOIN `user` w ON w.id = c.worker_id
        LEFT JOIN `user` e ON e.id = c.employer_id
        WHERE c.id = #{id}
        """)
    java.util.Map<String, Object> detail(@Param("id") Long id);
}
