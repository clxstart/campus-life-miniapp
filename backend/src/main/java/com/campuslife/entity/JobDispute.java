package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 履约争议。裁定结果只能是 settled / refunded，且必须写管理员审计。 */
@Data
@TableName("job_dispute")
public class JobDispute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long contractId;
    private Long raisedBy;
    private String reason;
    /** open | resolved */
    private String status;
    private Long decidedBy;
    /** settled | refunded */
    private String decision;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
