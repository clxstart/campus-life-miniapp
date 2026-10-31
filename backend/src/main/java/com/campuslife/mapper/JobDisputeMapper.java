package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.JobDispute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JobDisputeMapper extends BaseMapper<JobDispute> {

    @Select("""
        SELECT * FROM job_dispute WHERE contract_id = #{contractId}
        ORDER BY id DESC LIMIT 1
        """)
    JobDispute latest(@Param("contractId") Long contractId);

    @Update("""
        UPDATE job_dispute
        SET status = 'resolved', decision = #{decision}, decided_by = #{adminId}, decided_at = NOW()
        WHERE id = #{id} AND status = 'open'
        """)
    int resolve(@Param("id") Long id, @Param("decision") String decision, @Param("adminId") Long adminId);
}
