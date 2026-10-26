package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_record")
public class PaymentRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long orderId;
    private String outTradeNo;
    private String transactionId;
    private Integer amountCents;
    private String tradeState;
    private String rawBody;
    private Integer verified;
    private LocalDateTime createdAt;
}
