package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("favorite")
public class Favorite {
    private Long id;
    private Long postId;
    private Long userId;
    private LocalDateTime createdAt;
}
