package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("post")
public class Post {
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String image;
    private String tag;
    private String learnCategory;
    private Long circleId;
    // 多学校：NULL = 跨校可见（进全校广场），非空 = 仅该校可见
    private Long schoolId;
    private String location;
    private Integer likeCount;
    private Integer commentCount;
    private Integer viewCount;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
