package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.UserOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserOrderMapper extends BaseMapper<UserOrder> {

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
