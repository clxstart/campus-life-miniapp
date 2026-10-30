package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;

/** 评论点赞明细：一条 (comment_id,user_id) 一行，唯一键保证不重复点赞。 */
@Data @TableName("comment_like")
public class CommentLike {
    private Long id;
    private Long commentId;
    private Long userId;
    private LocalDateTime createdAt;
}
