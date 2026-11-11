package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外卖订单。
 * task-12 支付字段：payAmountCents / outTradeNo / transactionId / prepayId / payStatus / paidAt / cancelledAt / cancelReason
 * task-14 履约联系：contactName / contactPhone
 * task-15 骑手字段：riderId / riderName / acceptedAt / pickedAt / deliveredAt
 */
@Data
@TableName("user_order")
public class UserOrder {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private Long shopId;
    private String items;
    private String address;
    // task-14: 下单时的收货人与电话快照（商家拒单、骑手配送都需要）
    private String contactName;
    private String contactPhone;
    private String remark;
    private BigDecimal subtotal;
    private BigDecimal packingFee;
    private BigDecimal deliveryFee;
    private BigDecimal discountAmount;
    private BigDecimal total;
    private String status;
    // 券 v3.1：本单用了哪张券、平台补贴了多少。
    // 商家收入不扣券钱，所以这笔钱在 earnings_ledger 里单独记一条 platform_subsidy
    // （见 PaymentLedgerService.applyCoupon）；退款时券作废不退。
    private Long couponId;
    private BigDecimal couponDiscount;
    // task-12: 支付相关
    private Integer payAmountCents;
    private String outTradeNo;
    private String transactionId;
    private String prepayId;
    private String payStatus;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    // task-15: 骑手配送相关
    private Long riderId;
    private String riderName;
    private LocalDateTime acceptedAt;
    private LocalDateTime pickedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
}
