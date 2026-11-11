package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 支付异常单（finding-04）：已在渠道扣款但无法正常入账的交易。
 * 唯一键 (transaction_id, kind) 保证同一笔交易的重复回调不会重复登记。
 */
@Mapper
public interface PaymentExceptionMapper {

    @Insert("""
        INSERT IGNORE INTO payment_exception(order_id, out_trade_no, transaction_id, amount_cents, kind, status, note)
        VALUES(#{orderId}, #{outTradeNo}, #{transactionId}, #{amountCents}, #{kind}, #{status}, #{note})
        """)
    int insertIgnore(@Param("orderId") Long orderId, @Param("outTradeNo") String outTradeNo,
                     @Param("transactionId") String transactionId, @Param("amountCents") int amountCents,
                     @Param("kind") String kind, @Param("status") String status, @Param("note") String note);

    /**
     * 退款通知到达时按退款单号找对应的「关单后到账」异常单，用于让回调路径与轮询路径一样
     * 把 payment_exception 收敛到最终状态（否则回调已成功、异常单要等下一轮轮询才 resolved）。
     */
    @Select("""
        SELECT id FROM payment_exception
        WHERE out_refund_no=#{outRefundNo} AND kind='PAID_AFTER_CLOSE'
        ORDER BY id DESC LIMIT 1
        """)
    Long findIdByOutRefundNo(@Param("outRefundNo") String outRefundNo);

    /** 待自动退款的「关单后到账」异常单，按 id 升序、有界批次 */
    @Select("""
        SELECT id, order_id AS orderId, out_trade_no AS outTradeNo, transaction_id AS transactionId,
               amount_cents AS amountCents, status, out_refund_no AS outRefundNo, attempts
        FROM payment_exception
        WHERE kind='PAID_AFTER_CLOSE' AND status IN ('open','refund_submitted')
        ORDER BY id ASC LIMIT #{limit}
        """)
    List<Map<String, Object>> selectCompensationBatch(@Param("limit") int limit);

    /** 领取一条待退款异常单：只有 status=open 时成功，多节点并发时只有一个能领到 */
    @Update("""
        UPDATE payment_exception SET status='refund_submitted', out_refund_no=#{outRefundNo}, attempts=attempts+1
        WHERE id=#{id} AND status='open'
        """)
    int claimForRefund(@Param("id") Long id, @Param("outRefundNo") String outRefundNo);

    /**
     * 处理中：只允许 open/refund_submitted 来源。
     *
     * 这是「租约重叠」的关键防护：旧节点在租约过期后恢复，如果它迟到的 PROCESSING 结果
     * 无条件覆盖状态，就会把新节点已经写好的 resolved 退回处理中。条件 UPDATE 让这类迟到结果
     * 0 行命中，状态只能单调前进。
     */
    @Update("""
        UPDATE payment_exception SET status='refund_submitted', note=#{note}, attempts=attempts+#{inc}
        WHERE id=#{id} AND status IN ('open','refund_submitted')
        """)
    int markRefundSubmitted(@Param("id") Long id, @Param("note") String note, @Param("inc") int inc);

    /** 已退款：resolved 是终点；manual（人工放弃后渠道又成功）允许收敛为 resolved。 */
    @Update("""
        UPDATE payment_exception SET status='resolved', note=#{note}, attempts=attempts+#{inc}
        WHERE id=#{id} AND status <> 'resolved'
        """)
    int markResolved(@Param("id") Long id, @Param("note") String note, @Param("inc") int inc);

    /** 转人工：只允许从 open/refund_submitted 进入；绝不把 resolved 改回 manual。 */
    @Update("""
        UPDATE payment_exception SET status='manual', note=#{note}, attempts=attempts+#{inc}
        WHERE id=#{id} AND status IN ('open','refund_submitted')
        """)
    int markManual(@Param("id") Long id, @Param("note") String note, @Param("inc") int inc);

    @Select("""
        <script>
        SELECT id, order_id AS orderId, out_trade_no AS outTradeNo, transaction_id AS transactionId,
               amount_cents AS amountCents, kind, status, out_refund_no AS outRefundNo, note, attempts,
               created_at AS createdAt, updated_at AS updatedAt
        FROM payment_exception
        <where><if test="status != null and status != ''">status=#{status}</if></where>
        ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> page(@Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM payment_exception
        <where><if test="status != null and status != ''">status=#{status}</if></where>
        </script>
        """)
    long count(@Param("status") String status);
}
