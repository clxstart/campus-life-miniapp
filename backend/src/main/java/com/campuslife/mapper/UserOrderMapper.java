package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.UserOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserOrderMapper extends BaseMapper<UserOrder> {

    /**
     * 原子入账（finding-04）：只有仍处于 created/unpaid 的订单才能被标记已付。
     * 与 {@link #closeIfUnpaid} 互斥——同一订单关单与回调并发时，只有一个 UPDATE 能命中，
     * 输家再按回读状态走「重复回调」或「关单后到账」补偿路径。
     */
    @Update("""
        UPDATE user_order SET status='paid', pay_status='paid', transaction_id=#{transactionId}, paid_at=NOW()
        WHERE id=#{orderId} AND status='created' AND pay_status='unpaid' AND pay_amount_cents=#{amountCents}
        """)
    int markPaidIfUnpaid(@Param("orderId") Long orderId, @Param("transactionId") String transactionId,
                         @Param("amountCents") int amountCents);

    /** 原子关单：只关闭仍未支付的订单；status 取 closed（超时）或 cancelled（用户取消）。 */
    @Update("""
        UPDATE user_order SET status=#{status}, pay_status='closed', cancelled_at=NOW(), cancel_reason=#{reason}
        WHERE id=#{orderId} AND status='created' AND pay_status='unpaid'
        """)
    int closeIfUnpaid(@Param("orderId") Long orderId, @Param("status") String status, @Param("reason") String reason);

    /**
     * 退款前的当前读（finding-followup）。
     *
     * MySQL 默认 REPEATABLE READ：事务里的普通 SELECT 读的是快照，并发等待后回读仍可能看到旧状态。
     * 退款申请先用 FOR UPDATE 拿当前读并锁住订单行，后续的状态判断和条件迁移都基于最新已提交数据，
     * 不会因为快照过期而误判「仍是 paid」再发一次退款。
     */
    @Select("SELECT * FROM user_order WHERE id=#{orderId} FOR UPDATE")
    UserOrder findByIdForUpdate(@Param("orderId") Long orderId);

    /**
     * 原子领取退款（finding-followup）：只有「已支付 + 仍可退」的订单能进入 refunding。
     *
     * 并发退款请求会在同一行上排队：第一个事务把 status/pay_status 改成 refunding 并提交，
     * 第二个事务的 WHERE 条件在锁释放后重新求值必然为 0，于是回读既有退款记录返回幂等结果。
     * 允许状态必须与 {@link com.campuslife.service.OrderStatus#canTransit} 指向 REFUNDING 的集合保持一致
     * （RefundInitiationService 有对照测试）。
     */
    @Update("""
        UPDATE user_order SET status='refunding', pay_status='refunding'
        WHERE id=#{orderId}
          AND (pay_status='paid' OR pay_status IS NULL)
          AND status IN ('paid','accepted','delivering','picked','completed')
        """)
    int claimRefund(@Param("orderId") Long orderId);
    // 补偿成功后的订单收敛见 MerchantMapper#updateOrderStatusForCompensation（与退款账本同一事务）

    /**
     * 对账批次（finding-11）：按 id 升序取一批超过 cutoff 的待支付单，且跳过上一轮刚核验过的（退避）。
     * 依赖 idx_order_pay_status(pay_status, created_at)；LIMIT 保证单轮工作量有界。
     */
    @Select("""
        SELECT id, out_trade_no AS outTradeNo, pay_amount_cents AS payAmountCents, created_at AS createdAt,
               pay_check_attempts AS payCheckAttempts
        FROM user_order
        WHERE pay_status='unpaid' AND status='created' AND created_at < #{cutoff}
          AND (pay_check_at IS NULL OR pay_check_at < #{recheckBefore})
        ORDER BY id ASC LIMIT #{limit}
        """)
    List<Map<String, Object>> selectReconcileBatch(@Param("cutoff") LocalDateTime cutoff,
                                                   @Param("recheckBefore") LocalDateTime recheckBefore,
                                                   @Param("limit") int limit);

    /** 记录一次对账结果（成功清空错误；失败保留错误文本，供排查与告警） */
    @Update("""
        UPDATE user_order SET pay_check_at=NOW(), pay_check_attempts=pay_check_attempts+1, pay_check_error=#{error}
        WHERE id=#{orderId}
        """)
    int recordPayCheck(@Param("orderId") Long orderId, @Param("error") String error);

    /**
     * 按幂等键找订单（finding-05）。locking=true 时用当前读（FOR UPDATE），
     * 并发插入撞唯一键后能读到对方刚提交的订单，不受可重复读快照影响。
     */
    @Select("""
        <script>
        SELECT id FROM user_order WHERE user_id=#{userId} AND idempotency_key=#{key} LIMIT 1
        <if test="locking">FOR UPDATE</if>
        </script>
        """)
    Long findIdByIdempotencyKey(@Param("userId") Long userId, @Param("key") String key, @Param("locking") boolean locking);

    /** 当前未支付积压量（指标 campus.pay.reconcile.backlog 用） */
    @Select("SELECT COUNT(*) FROM user_order WHERE pay_status='unpaid' AND status='created' AND created_at < #{cutoff}")
    long countReconcileBacklog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 支付统计：金额与笔数在数据库里聚合。
     *
     * 之前是先把符合条件的所有订单读进内存再 for 循环累加（AdminPaymentService.stats），
     * 订单涨到几万条时既慢又吃内存。这里改成一条 SQL 出结果。
     */
    @Select("""
        <script>
        SELECT IFNULL(SUM(o.pay_amount_cents), 0) AS amount, COUNT(*) AS cnt
        FROM user_order o
        WHERE o.pay_status = 'paid'
          <if test="shopIds != null and shopIds.size() > 0">
            AND o.shop_id IN <foreach collection="shopIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
          </if>
          <if test="from != null">AND o.paid_at &gt;= #{from}</if>
          <if test="to != null">AND o.paid_at &lt; #{to}</if>
        </script>
        """)
    Map<String, Object> sumPaid(@Param("shopIds") List<Long> shopIds,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    /**
     * 未核验的支付记录笔数。
     *
     * 原实现是读出全部 payment_record，再在 stream 里逐条 selectById 查订单判断店铺归属（N+1）。
     * 改成一次 JOIN 聚合，避免 1 万条记录打出 1 万次查询。
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM payment_record p
        JOIN user_order o ON o.id = p.order_id
        WHERE p.verified = 0
          <if test="shopIds != null and shopIds.size() > 0">
            AND o.shop_id IN <foreach collection="shopIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
          </if>
          <if test="from != null">AND p.created_at &gt;= #{from}</if>
          <if test="to != null">AND p.created_at &lt; #{to}</if>
        </script>
        """)
    long countUnverifiedPayments(@Param("shopIds") List<Long> shopIds,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /** 退款成功总额，同样用 JOIN 聚合替代 N+1。 */
    @Select("""
        <script>
        SELECT IFNULL(SUM(r.amount_cents), 0) FROM refund_record r
        JOIN user_order o ON o.id = r.order_id
        WHERE r.status = 'success'
          <if test="shopIds != null and shopIds.size() > 0">
            AND o.shop_id IN <foreach collection="shopIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
          </if>
          <if test="from != null">AND r.created_at &gt;= #{from}</if>
          <if test="to != null">AND r.created_at &lt; #{to}</if>
        </script>
        """)
    long sumRefunded(@Param("shopIds") List<Long> shopIds,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);
}
