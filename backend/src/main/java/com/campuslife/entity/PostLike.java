package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("post_like")
public class PostLike {
    private Long id;
    private Long postId;
    private Long userId;
    private LocalDateTime createdAt;
}
