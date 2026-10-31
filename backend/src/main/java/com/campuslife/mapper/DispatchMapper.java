package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 配送派单相关 SQL。
 *
 * 全部写操作都用「条件 UPDATE」表达前置状态，靠 MySQL 的行锁 + 影响行数保证原子性，
 * 不依赖"先查后写"这种有竞态的做法。
 */
@Mapper
public interface DispatchMapper {

    @Select("""
        SELECT o.id, o.status, o.rider_id AS riderId, o.address,
               o.delivery_fee AS deliveryFee, o.delivery_pay_cents AS deliveryPayCents,
               s.school_id AS schoolId, s.name AS shopName
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        WHERE o.id=#{orderId}
        """)
    Map<String, Object> orderForDispatch(@Param("orderId") Long orderId);

    @Select("""
        SELECT id, real_name AS realName, role, status, rider_status AS riderStatus, school_id AS schoolId
        FROM admin_user WHERE id=#{riderId}
        """)
    Map<String, Object> riderInfo(@Param("riderId") Long riderId);

    /**
     * 自动派单：把订单原子地派给默认骑手。
     * 前置条件写在 WHERE 里 —— o.rider_id IS NULL AND o.status='accepted'，
     * 所以即使商家重复接单或并发触发，也只会成功一次。
     * 同时把下单时的配送费快照成「分」，后续改价不影响已派出的订单。
     */
    @Update("""
        UPDATE user_order o
        SET o.building_id=#{buildingId}, o.rider_id=#{riderId}, o.rider_name=#{riderName},
            o.actual_rider_id=#{riderId}, o.dispatch_status='auto', o.dispatch_note='',
            o.delivery_pay_cents=GREATEST(0, ROUND(o.delivery_fee*100)),
            o.status='delivering', o.accepted_at=NOW()
        WHERE o.id=#{orderId} AND o.rider_id IS NULL AND o.status='accepted'
        """)
    int assignAuto(@Param("orderId") Long orderId, @Param("riderId") Long riderId,
                   @Param("riderName") String riderName, @Param("buildingId") Long buildingId);

    /** 进入异常待处理队列：保留 rider_id 为空，让负责人看得见。 */
    @Update("""
        UPDATE user_order
        SET dispatch_status='queued', dispatch_note=#{note}
        WHERE id=#{orderId} AND rider_id IS NULL AND status IN ('accepted')
        """)
    int markQueued(@Param("orderId") Long orderId, @Param("note") String note);

    /**
     * 负责人改派 / 接管。
     * 只允许在未完成的订单上操作；accepted 状态会被顺带推进到 delivering。
     * 改派后 user_order.rider_id 变成新责任人，旧骑手的 pick/deliver 会命中 0 行 —— 立即失去推进权限。
     */
    @Update("""
        UPDATE user_order
        SET rider_id=#{riderId}, rider_name=#{riderName}, actual_rider_id=#{riderId},
            dispatch_status=#{action}, dispatch_note=#{note}, delivery_pay_counted=#{countPay},
            delivery_pay_cents=GREATEST(0, ROUND(delivery_fee*100)),
            accepted_at=IF(status='accepted' AND accepted_at IS NULL, NOW(), accepted_at),
            status=IF(status='accepted', 'delivering', status)
        WHERE id=#{orderId} AND status IN ('accepted','delivering','picked')
        """)
    int reassign(@Param("orderId") Long orderId, @Param("riderId") Long riderId,
                 @Param("riderName") String riderName, @Param("action") String action,
                 @Param("note") String note, @Param("countPay") int countPay);

    /** 清理骑手忙碌标记用：还有进行中订单就是 busy，否则 idle。 */
    @Update("""
        UPDATE admin_user SET rider_status =
          IF((SELECT COUNT(*) FROM user_order WHERE rider_id=#{riderId} AND status IN ('delivering','picked')) > 0, 'busy', 'idle')
        WHERE id=#{riderId} AND role='rider'
        """)
    int refreshRiderStatus(@Param("riderId") Long riderId);

    @Insert("""
        INSERT INTO order_dispatch_log(order_id, action, from_rider_id, to_rider_id, actor_kind, actor_id, reason)
        VALUES(#{orderId}, #{action}, #{fromRiderId}, #{toRiderId}, #{actorKind}, #{actorId}, #{reason})
        """)
    int logDispatch(@Param("orderId") Long orderId, @Param("action") String action,
                    @Param("fromRiderId") Long fromRiderId, @Param("toRiderId") Long toRiderId,
                    @Param("actorKind") String actorKind, @Param("actorId") Long actorId,
                    @Param("reason") String reason);

    @Select("""
        SELECT id, action, from_rider_id AS fromRiderId, to_rider_id AS toRiderId,
               actor_kind AS actorKind, actor_id AS actorId, reason, created_at AS createdAt
        FROM order_dispatch_log WHERE order_id=#{orderId} ORDER BY id
        """)
    List<Map<String, Object>> dispatchLog(@Param("orderId") Long orderId);

    /** 异常待处理队列：已接单但没有责任骑手，或已标记 queued。 */
    @Select("""
        <script>
        SELECT o.id, o.address, o.status, o.dispatch_status AS dispatchStatus,
               o.dispatch_note AS note, o.created_at AS createdAt,
               s.name AS shopName, s.school_id AS schoolId
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        WHERE o.status IN ('accepted','delivering','picked')
          AND (o.dispatch_status='queued' OR (o.dispatch_status='none' AND o.rider_id IS NULL))
        <if test="schoolId != null">AND s.school_id=#{schoolId}</if>
        ORDER BY o.created_at ASC, o.id ASC
        LIMIT 100
        </script>
        """)
    List<Map<String, Object>> dispatchQueue(@Param("schoolId") Long schoolId);

    @Select("SELECT id FROM user_order WHERE status='accepted' AND rider_id IS NULL ORDER BY id LIMIT 200")
    List<Long> awaitingDispatchIds();

    /**
     * 标记「本单配送报酬已记账」。影响行数 1 才表示这次是第一次，
     * 重复送达/重复回调只会拿到 0，天然防重复结算。
     */
    @Update("""
        UPDATE user_order SET delivery_pay_recorded=1
        WHERE id=#{orderId} AND delivery_pay_recorded=0 AND status='completed'
        """)
    int markDeliveryPayRecorded(@Param("orderId") Long orderId);

    @Select("SELECT id, delivery_pay_cents AS payCents, delivery_pay_counted AS payCounted, rider_id AS riderId, actual_rider_id AS actualRiderId FROM user_order WHERE id=#{orderId}")
    Map<String, Object> payInfo(@Param("orderId") Long orderId);
}
