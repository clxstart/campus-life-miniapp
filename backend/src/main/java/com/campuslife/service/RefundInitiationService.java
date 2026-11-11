package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款申请的事务内部分（finding-followup：并发退款互斥）。
 *
 * 只做三件事，且都在一个短事务里完成：
 *  1. 用 FOR UPDATE 当前读锁定订单行，拿到最新已提交状态；
 *  2. 条件 UPDATE 把订单从可退状态原子迁到 refunding；
 *  3. 按「每订单唯一」写退款账本，退款单号确定性生成（RF + orderId）。
 *
 * 这里绝不调用微信接口：渠道提交由 {@link UserRefundService} 在事务提交后执行。
 * 并发输家不会看到「refunding 但还没有退款记录」的中间态，因为状态迁移和账本写入在同一个事务里提交。
 */
@Service
@RequiredArgsConstructor
public class RefundInitiationService {

    /** 允许进入退款的订单状态；必须与 {@link OrderStatus#canTransit} 指向 REFUNDING 的集合一致。 */
    static final java.util.Set<String> REFUNDABLE = java.util.Set.of(
        OrderStatus.PAID.value(), OrderStatus.ACCEPTED.value(), OrderStatus.DELIVERING.value(),
        OrderStatus.PICKED.value(), OrderStatus.COMPLETED.value());

    public record Initiated(Long orderId, String outRefundNo, String refundStatus, String refundId,
                            String orderStatus, String payStatus, boolean created) { }

    private final UserOrderMapper orderMapper;
    private final RefundRecordMapper refundRecordMapper;

    /**
     * @param admin 管理端发起时跳过下单人归属校验（权限已在 AdminInterceptor + assertAccessible 完成）
     */
    @Transactional
    public Initiated initiate(Long orderId, Long userId, boolean admin, String reason) {
        if (orderId == null || orderId < 1) throw new IllegalArgumentException("订单参数无效");
        UserOrder order = orderMapper.findByIdForUpdate(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!admin) {
            if (userId == null || userId < 1) throw new UnauthorizedException("请先登录后操作订单");
            if (!userId.equals(order.getUserId())) throw new ForbiddenException("无权操作该订单");
        }

        String status = order.getStatus() == null ? "" : order.getStatus();
        // 已在退款流程：稳定返回既有退款单，客户端超时重试不会生成新单号
        if (OrderStatus.REFUNDING.value().equals(status) || OrderStatus.REFUNDED.value().equals(status)) {
            return existing(orderId, order);
        }
        if (!OrderStatus.canTransit(status, OrderStatus.REFUNDING.value())) {
            throw new IllegalStateException("当前订单状态（" + status + "）不能退款");
        }
        if (orderMapper.claimRefund(orderId) != 1) {
            // 行锁下仍可能因管理员改状态等并发操作导致条件不成立：当前读回最新状态再判定
            UserOrder current = orderMapper.findByIdForUpdate(orderId);
            String now = current == null ? "" : current.getStatus();
            if (OrderStatus.REFUNDING.value().equals(now) || OrderStatus.REFUNDED.value().equals(now)) {
                return existing(orderId, current);
            }
            throw new IllegalStateException("订单状态已变化，请刷新后重试");
        }

        String outRefundNo = "RF" + orderId;
        String text = reason == null || reason.isBlank() ? "用户申请退款" : reason.trim();
        if (text.length() > 200) text = text.substring(0, 200);
        Integer amount = order.getPayAmountCents() == null ? 0 : order.getPayAmountCents();
        refundRecordMapper.insertRefundIfAbsent(orderId, outRefundNo, amount, text);

        // 唯一键兜底：即使有其他代码路径先写入，也复用那一条，绝不生成第二个可提交渠道的单号
        RefundRecord record = refundRecordMapper.findByOrderId(orderId);
        if (record == null) throw new IllegalStateException("退款记录写入失败，请稍后重试");
        return new Initiated(orderId, record.getOutRefundNo(), record.getStatus(), record.getRefundId(),
            OrderStatus.REFUNDING.value(), "refunding", true);
    }

    private Initiated existing(Long orderId, UserOrder order) {
        RefundRecord record = refundRecordMapper.findByOrderId(orderId);
        if (record == null) {
            throw new IllegalStateException("订单处于退款流程但缺少退款记录，请联系客服核实");
        }
        return new Initiated(orderId, record.getOutRefundNo(), record.getStatus(), record.getRefundId(),
            order.getStatus(), order.getPayStatus(), false);
    }
}
