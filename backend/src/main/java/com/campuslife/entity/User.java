package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("user")
public class User {
    private Long id;
    private String nickname;
    private String openid;
    private String avatar;
    private String school;
    // 多学校：归属学校，以 school_id 为准（school 字符串保留做兼容）
    private Long schoolId;
    private LocalDateTime createdAt;
}
