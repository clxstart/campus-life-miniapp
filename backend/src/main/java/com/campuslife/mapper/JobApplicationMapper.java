package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.JobApplication;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface JobApplicationMapper extends BaseMapper<JobApplication> {

    /**
     * 报名：INSERT IGNORE + UNIQUE(job_id, applicant_id)。
     * 重复报名影响 0 行 → Service 直接返回已有记录，天然幂等，不需要先查再判断。
     */
    @Insert("""
        INSERT IGNORE INTO job_application(job_id, applicant_id, status, message)
        VALUES(#{jobId}, #{applicantId}, 'pending', #{message})
        """)
    int apply(@Param("jobId") Long jobId, @Param("applicantId") Long applicantId,
              @Param("message") String message);

    @Select("SELECT * FROM job_application WHERE job_id = #{jobId} AND applicant_id = #{applicantId}")
    JobApplication findOne(@Param("jobId") Long jobId, @Param("applicantId") Long applicantId);

    @Select("SELECT * FROM job_application WHERE job_id = #{jobId} ORDER BY id DESC LIMIT 200")
    List<JobApplication> byJob(@Param("jobId") Long jobId);

    /**
     * 处理报名：录用或拒绝。
     * 条件里带 status='pending'，所以同一条报名被重复处理（双击、重试、并发）时
     * 只有第一次能影响 1 行，其余返回 0 —— 调用方据此判断"已处理过"。
     */
    @Update("""
        UPDATE job_application SET status = #{status}, decided_at = NOW(), decided_by = #{decidedBy}
        WHERE id = #{id} AND status = 'pending'
        """)
    int decide(@Param("id") Long id, @Param("status") String status, @Param("decidedBy") Long decidedBy);
}
