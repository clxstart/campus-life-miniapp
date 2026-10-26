package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("banner")
public class Banner {
    private Long id;
    private String slot;
    private String eyebrow;
    private String title;
    private String subtitle;
    private String badge;
    private String theme;
    private String linkType;
    private String linkValue;
    private Integer sort;
    private Integer status;
}
