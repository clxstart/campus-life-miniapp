package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("refund_record")
public class RefundRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long orderId;
    private String outRefundNo;
    private String refundId;
    private Integer amountCents;
    private String reason;
    private String status;
    private LocalDateTime createdAt;
}
