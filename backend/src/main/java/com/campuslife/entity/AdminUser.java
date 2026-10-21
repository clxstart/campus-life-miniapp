package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;

// 管理员账号：独立于 user 表，避免普通用户提权。
// role=super 全平台；role=school 仅本校（schoolId 决定范围）。
@Data @TableName("admin_user")
public class AdminUser {
    private Long id;
    private String username;
    private String passwordHash;
    private String realName;
    private String role;
    private Long schoolId;
    private String phone;
    private String riderStatus;
    private Integer status;
    private Integer failCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
