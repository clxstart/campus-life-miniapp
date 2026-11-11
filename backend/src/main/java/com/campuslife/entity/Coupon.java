package com.campuslife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 优惠券。
 *
 * 字段来源是 coupon 表（见 db/module-reward-v31.sql）：金额一律用「分」存整数，
 * 不用 double/字符串比较，避免 0.1+0.2 那种精度问题在钱上出事。
 *
 * 生命周期（CouponService 里实现）：
 *   1 可用 → 2 下单锁定（locked_at / order_id）→ 3 支付核销（used_at）；取消关单回到 1；退款作废 0。
 *
 * 旧字段 amount / expireAt / status 仍在：amount 是「满减文案」，expireAt 是历史字符串展示字段，
 * 新的判定一律走 valueCents / thresholdCents / expireOn，不要再去解析 amount 字符串。
 */
@Data
@TableName("coupon")
public class Coupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 展示文案，如「满 20 减 5」；不参与任何计算 */
    private String amount;
    /** 历史字符串到期日，仅用于展示；判定用 expireOn */
    private String expireAt;
    /** 1 可用 / 2 已锁定 / 3 已核销 / 0 已失效 */
    private Integer status;

    // ===== 券 v3.1：金额与范围用结构化字段 =====
    /** 抵扣金额（分） */
    private Integer valueCents;
    /** 门槛金额（分），0 表示无门槛 */
    private Integer thresholdCents;
    /** 适用范围，如 takeout；空表示通用 */
    private String scope;
    /** 到期日（含当天）；为空表示长期有效 */
    private LocalDate expireOn;
    /** 来源，如 seed / reward / activity，用于运营区分券的出处 */
    private String source;

    // ===== 锁定与核销痕迹 =====
    /** 被哪笔订单锁定 */
    private Long orderId;
    /** 锁单时间 */
    private LocalDateTime lockedAt;
    /** 核销时间 */
    private LocalDateTime usedAt;
}