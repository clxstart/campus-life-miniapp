package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("coupon")
public class Coupon {
    private Long id;
    private Long userId;
    private String title;
    private String amount;
    private String expireAt;
    private Integer status;
}
