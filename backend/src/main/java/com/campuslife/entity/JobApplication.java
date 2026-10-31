package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校外岗位报名。UNIQUE(job_id, applicant_id) 既是业务约束也是幂等兜底：
 * 重复报名命中唯一键，接口按「已有报名」返回，不产生第二条记录。
 */
@Data
@TableName("job_application")
public class JobApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long applicantId;
    /** pending | accepted | rejected | withdrawn */
    private String status;
    private String message;
    private LocalDateTime appliedAt;
    private LocalDateTime decidedAt;
    private Long decidedBy;
}
