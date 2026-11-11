package com.campuslife.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端支付流水查询（finding-06/07/08）。
 *
 * - 一条联表 SQL 取出用户昵称、店铺名、最近一条流水的核验状态，SQL 条数与页大小无关（消除 N+1）；
 * - 关键词在数据库里与店铺/日期/状态条件一起过滤并分页，不再先把全部昵称/流水加载进内存；
 * - 商户订单号/交易号用前缀匹配（可走唯一索引），昵称用包含匹配但受店铺+日期范围约束、关键词长度受限。
 */
@Mapper
public interface AdminPaymentMapper {

    String FILTER = """
        FROM user_order o
        JOIN shop s ON s.id = o.shop_id
        LEFT JOIN `user` u ON u.id = o.user_id
        WHERE o.shop_id IN <foreach collection="shopIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
          <if test="payStatus != null and payStatus != ''">AND o.pay_status = #{payStatus}</if>
          <if test="from != null">AND o.paid_at &gt;= #{from}</if>
          <if test="to != null">AND o.paid_at &lt; #{to}</if>
          <if test="kw != null and kw != ''">
            AND (o.out_trade_no LIKE CONCAT(#{kw}, '%')
              OR o.transaction_id LIKE CONCAT(#{kw}, '%')
              OR EXISTS (SELECT 1 FROM payment_record pk WHERE pk.order_id = o.id AND pk.transaction_id LIKE CONCAT(#{kw}, '%'))
              <if test="nickname">OR u.nickname LIKE CONCAT('%', #{kw}, '%')</if>)
          </if>
        """;

    String COLUMNS = """
        SELECT o.id, o.out_trade_no AS outTradeNo, o.transaction_id AS transactionId, o.user_id AS userId,
               COALESCE(u.nickname, '') AS userName, o.shop_id AS shopId, s.name AS shopName,
               o.pay_amount_cents AS amountCents, o.pay_status AS payStatus, o.paid_at AS paidAt,
               COALESCE((SELECT p.verified FROM payment_record p WHERE p.order_id = o.id ORDER BY p.id DESC LIMIT 1), 1) AS verified
        """;

    @Select("<script>" + COLUMNS + FILTER + " ORDER BY o.paid_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> page(@Param("shopIds") List<Long> shopIds, @Param("payStatus") String payStatus,
                                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                   @Param("kw") String kw, @Param("nickname") boolean nickname,
                                   @Param("limit") int limit, @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long count(@Param("shopIds") List<Long> shopIds, @Param("payStatus") String payStatus,
               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
               @Param("kw") String kw, @Param("nickname") boolean nickname);

    /**
     * 导出用键集分页：按 id 倒序、以上一批最小 id 为游标，避免深 OFFSET；
     * 与列表使用相同筛选条件与权限范围。
     */
    @Select("<script>" + COLUMNS + FILTER + " <if test=\"beforeId != null\">AND o.id &lt; #{beforeId}</if> ORDER BY o.id DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> exportBatch(@Param("shopIds") List<Long> shopIds, @Param("payStatus") String payStatus,
                                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                          @Param("kw") String kw, @Param("nickname") boolean nickname,
                                          @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
