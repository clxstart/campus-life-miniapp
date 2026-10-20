package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("post_view")
public class PostView {
    private Long id;
    private Long postId;
    private Long userId;
    private LocalDateTime createdAt;
}
