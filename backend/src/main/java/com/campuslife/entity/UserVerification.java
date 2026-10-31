package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生/实名核验状态。
 *
 * 只保存核验**结论**与审计字段，不保存证件号、证件影像等原始材料——
 * 个人信息按最小必要原则留存。
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
    /** 核验来源，当前仅 manual_admin（管理端人工核验） */
    private String provider;
    private String rejectReason;
    private Long reviewerId;
    private LocalDateTime verifiedAt;
    private LocalDateTime updatedAt;
}
