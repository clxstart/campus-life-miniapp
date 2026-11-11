package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campuslife.entity.Coupon;
import com.campuslife.mapper.CouponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 券的生命周期：可用 → 下单锁定 → 支付核销；取消释放；退款作废。
 *
 * 为什么必须锁定：
 *   只在支付时校验会出现两个订单同时用同一张券；只在支付时扣会让「下单不付款」永久占券。
 * 所以 createOrder 锁（status=2），支付成功核销（3），关单释放回 1，退款作废 0。
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final CouponMapper mapper;

    /** 下单前的可用性校验；不通过直接给出用户能看懂的原因。 */
    public Coupon usable(Long userId, Long couponId, BigDecimal subtotal) {
        if (couponId == null || couponId < 1) throw new IllegalArgumentException("优惠券无效");
        Coupon coupon = mapper.selectById(couponId);
        if (coupon == null || !userId.equals(coupon.getUserId())) throw new IllegalArgumentException("优惠券不存在");
        if (coupon.getStatus() == null || coupon.getStatus() != 1) throw new IllegalArgumentException("优惠券已使用或已失效");
        LocalDate today = LocalDate.now(SHANGHAI);
        if (coupon.getExpireOn() != null && coupon.getExpireOn().isBefore(today))
            throw new IllegalArgumentException("优惠券已过期");
        if (coupon.getScope() != null && !coupon.getScope().isBlank() && !"takeout".equals(coupon.getScope()))
            throw new IllegalArgumentException("该券不适用于外卖订单");
        int subtotalCents = subtotal.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.DOWN).intValue();
        int threshold = coupon.getThresholdCents() == null ? 0 : coupon.getThresholdCents();
        if (threshold > 0 && subtotalCents < threshold)
            throw new IllegalArgumentException("满 " + yuan(threshold) + " 元可用");
        if (coupon.getValueCents() == null || coupon.getValueCents() <= 0)
            throw new IllegalArgumentException("该券没有可抵扣金额");
        return coupon;
    }

    /** 下单锁定：只有「可用」才能锁，并发第二个订单会命中 0 行。 */
    public int lock(Long userId, Long couponId, Long orderId) {
        return mapper.update(null, new LambdaUpdateWrapper<Coupon>()
            .eq(Coupon::getId, couponId)
            .eq(Coupon::getUserId, userId)
            .eq(Coupon::getStatus, 1)
            .set(Coupon::getStatus, 2)
            .set(Coupon::getLockedAt, LocalDateTime.now())
            .set(Coupon::getOrderId, orderId));
    }

    /** 支付成功核销。 */
    public int useByOrder(Long orderId) {
        return mapper.update(null, new LambdaUpdateWrapper<Coupon>()
            .eq(Coupon::getOrderId, orderId)
            .eq(Coupon::getStatus, 2)
            .set(Coupon::getStatus, 3)
            .set(Coupon::getUsedAt, LocalDateTime.now()));
    }

    /** 取消/关单：锁定的券回到可用。 */
    public int releaseByOrder(Long orderId) {
        return mapper.update(null, new LambdaUpdateWrapper<Coupon>()
            .eq(Coupon::getOrderId, orderId)
            .eq(Coupon::getStatus, 2)
            .set(Coupon::getStatus, 1)
            .set(Coupon::getLockedAt, null)
            .set(Coupon::getOrderId, null));
    }

    /** 退款：券作废，不退回（平台补贴是真实支出，退券会造成循环套利）。 */
    public int voidByOrder(Long orderId) {
        return mapper.update(null, new LambdaUpdateWrapper<Coupon>()
            .eq(Coupon::getOrderId, orderId)
            .in(Coupon::getStatus, 2, 3)
            .set(Coupon::getStatus, 0));
    }

    /** 我的可用券：过滤过期与已用，个人页「N 张可用」与券列表共用同一口径。 */
    public List<Coupon> available(Long userId) {
        LocalDate today = LocalDate.now(SHANGHAI);
        return mapper.selectList(new LambdaQueryWrapper<Coupon>()
            .eq(Coupon::getUserId, userId)
            .eq(Coupon::getStatus, 1)
            .and(q -> q.isNull(Coupon::getExpireOn).or().ge(Coupon::getExpireOn, today))
            .orderByDesc(Coupon::getId));
    }

    private static String yuan(int cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN).toPlainString();
    }
}