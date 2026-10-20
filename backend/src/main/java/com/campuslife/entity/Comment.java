package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("comment")
public class Comment {
    private Long id;
    private Long postId;
    private Long userId;
    private String content;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;
}
