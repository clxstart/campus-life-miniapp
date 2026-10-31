package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 兼职岗位（扩列后）。
 *
 * 两类岗位共用一张表，用 {@code scope} 区分：
 *   - campus  校内岗位：先到先接（taken_by 抢占列），不涉及资质核验；
 *   - offsite 校外岗位：只能报名 + 雇主筛选，默认被 job.offsite.enabled 闸门关闭。
 *
 * 金额字段一律是**人民币分**；费率（feeMode/feeValue）由管理端逐岗位配置，
 * 发布者传什么都不记 —— 见 JobService.publish。
 *
 * workflowStatus 与 status（中文展示态）并存：老字段继续给列表/管理端用，
 * 新流程只认 workflowStatus，两者由 JobService 同步维护。
 */
@Data
@TableName("job")
public class Job {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private Integer daySalary;
    private String settle;
    private String location;
    private String timeDesc;
    /** 中文展示态：招聘中/待审核/已下架 */
    private String status;
    private Long userId;
    private LocalDateTime createdAt;
    private String description;
    private Long schoolId;
    private String auditStatus;
    private String auditReason;
    private LocalDateTime auditAt;

    /** campus | offsite */
    private String scope;
    /** student | partner */
    private String employerKind;
    private Long partnerId;
    /** first_come | apply */
    private String takeMode;
    /** other | takeover_class | tutor | promotion | service | delivery */
    private String categoryKey;
    /** 报酬，人民币分 */
    private Integer payCents;
    /** hour | order | shift | day | custom */
    private String payUnit;
    /** none | amount | rate（仅管理端可配） */
    private String feeMode;
    /** amount=分/单位；rate=万分比（500 = 5%） */
    private Integer feeValue;
    private Integer headcount;
    private LocalDateTime deadlineAt;
    /** open|taken|recruiting|working|pending_confirm|settled|disputed|refunded|cancelled */
    private String workflowStatus;
    /** 校内先到先接的承接人（原子抢占列） */
    private Long takenBy;
    private Integer acceptedCount;
}
