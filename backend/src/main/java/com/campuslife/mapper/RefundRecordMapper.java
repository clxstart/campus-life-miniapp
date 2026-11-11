package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.RefundRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {

    /**
     * 按订单取最新的退款单。
     *
     * 一条订单只允许一张退款单（refund_record 上有 uk_refund_order），退款流程里
     * 「已有退款单」意味着这是重试而不是新申请，所以必须稳定读回那一条，
     * 不能生成第二个 out_refund_no —— 换了单号在渠道侧就是另一笔退款。
     */
    @Select("""
        SELECT id, order_id AS orderId, out_refund_no AS outRefundNo, refund_id AS refundId,
               amount_cents AS amountCents, reason, status, created_at AS createdAt
        FROM refund_record WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1
        """)
    RefundRecord findByOrderId(@Param("orderId") Long orderId);

    /**
     * 幂等写入退款单：uk_refund_order 让重复调用只留一条，INSERT IGNORE 不抛异常。
     * 返回 0 表示「已存在」，调用方随后用 findByOrderId 复用那一条。
     */
    @Insert("""
        INSERT IGNORE INTO refund_record(order_id, out_refund_no, amount_cents, reason, status)
        VALUES(#{orderId}, #{outRefundNo}, #{amountCents}, #{reason}, 'pending')
        """)
    int insertRefundIfAbsent(@Param("orderId") Long orderId,
                             @Param("outRefundNo") String outRefundNo,
                             @Param("amountCents") Integer amountCents,
                             @Param("reason") String reason);
}