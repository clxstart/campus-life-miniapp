package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_order")
public class UserOrder {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private Long shopId;
    private String items;
    private String address;
    private String contactName;
    private String contactPhone;
    private String remark;
    private BigDecimal subtotal;
    private BigDecimal packingFee;
    private BigDecimal deliveryFee;
    private BigDecimal discountAmount;
    private BigDecimal total;
    private String status;
    private Long riderId;
    private String riderName;
    private LocalDateTime acceptedAt;
    private LocalDateTime pickedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
}
