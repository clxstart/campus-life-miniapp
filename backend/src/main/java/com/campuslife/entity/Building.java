package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 楼栋责任区：一栋楼配一个默认责任骑手，一名骑手可覆盖多栋。 */
@Data
@TableName("building")
public class Building {
    private Long id;
    private Long schoolId;
    private String name;
    /** 地址匹配关键词，逗号分隔，如 "6号楼,6栋,六号楼" */
    private String matchKeywords;
    private Long defaultRiderId;
    private Integer status;
    private LocalDateTime createdAt;
}
