package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("feedback")
public class Feedback {
    private Long id;
    private Long userId;
    private String content;
    @JsonFormat(pattern="MM-dd HH:mm")
    private LocalDateTime createdAt;
}
