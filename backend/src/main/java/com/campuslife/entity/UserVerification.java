package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学生/实名核验状态。
 *
 * 只保存核验**结论**与审核需要的材料（学校/学院/姓名/学号/手机号），
 * 不保存证件号、证件影像等原始材料——个人信息按最小必要原则留存。
 *
 * 字段来源分两类：
 *   结论列  realNameStatus / studentStatus / provider / rejectReason / reviewerId / verifiedAt
 *   材料列  schoolId / college / realName / studentNo / phone / submittedAt / source（V3.1 学生认证新增）
 *   限频列  submitDate / submitCount（「每人每天最多 3 次」的依据；submitted_at 会被覆盖，数不了）
 *
 * 手机号以原文落库（审核要能联系到人），但任何接口下发前都脱敏（138****1234）。
 */
@Data
@TableName("user_verification")
public class UserVerification {
    private Long id;
    private Long userId;
    /** none | pending | verified | rejected */
    private String realNameStatus;
    /** none | pending | verified | rejected */
    private String studentStatus;
    /** 核验来源，manual_admin=管理端人工核验，auto_rule=正则命中自动通过 */
    private String provider;
    private String rejectReason;
    private Long reviewerId;
    private LocalDateTime verifiedAt;
    private LocalDateTime updatedAt;

    // ---- V3.1 学生认证：审核材料（纯文本，不收证件影像）----
    private Long schoolId;
    private String college;
    private String realName;
    private String studentNo;
    /** 核对用手机号（11 位）；接口下发时脱敏为 138****1234 */
    private String phone;
    private LocalDateTime submittedAt;
    /** self=用户自己提交，admin=管理员直接核验 */
    private String source;

    // ---- 限频计数 ----
    private LocalDate submitDate;
    private Integer submitCount;
}