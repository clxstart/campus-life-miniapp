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
    private String auditStatus;
    private String auditReason;
    private LocalDateTime auditAt;
    /** 点赞数（评论也支持点赞，与帖子同一套交互） */
    private Integer likeCount;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;
}
