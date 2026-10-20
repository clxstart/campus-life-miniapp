package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("idol_contestant")
public class IdolContestant {
    private Long id;
    private Long userId;
    private String name;
    private Integer gender;
    private String college;
    private String photo;
    private String slogan;
    private Integer voteCount;
    private Integer status;
    @JsonFormat(pattern="MM-dd HH:mm")
    private LocalDateTime createdAt;
}
