package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("follow")
public class Follow {
    private Long id;
    private Long followerId;
    private Long followeeId;
    private LocalDateTime createdAt;
}
