package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("user_address")
public class UserAddress {
    private Long id;
    private Long userId;
    private String contact;
    private String phone;
    private String detail;
    private LocalDateTime createdAt;
}
