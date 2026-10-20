package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("idol_vote")
public class IdolVote {
    private Long id;
    private Long contestantId;
    private Long userId;
    private LocalDateTime createdAt;
}
