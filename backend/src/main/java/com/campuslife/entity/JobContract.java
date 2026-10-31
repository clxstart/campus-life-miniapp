package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 岗位履约单。接单那一刻把 job 的报酬与费率**快照**下来：
 * 之后管理端调整岗位费率，已经接过的单仍按旧费率结算 —— 否则改一次价，
 * 历史账单全部要重算，对不上账也没法解释。
 *
 * 工时不存 double：DECIMAL(8,2) + BigDecimal，结算时统一 HALF_UP 到分。
 */
@Data
@TableName("job_contract")
public class JobContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long workerId;
    private Long employerId;
    /** created|working|pending_confirm|settled|disputed|cancelled|refunded */
    private String status;

    private String feeModeSnapshot;
    private Integer feeValueSnapshot;
    private Integer payCentsSnapshot;
    private String payUnitSnapshot;

    private BigDecimal workerHours;
    private BigDecimal employerHours;
    private BigDecimal agreedHours;

    private String cancelReason;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime bothConfirmedAt;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
