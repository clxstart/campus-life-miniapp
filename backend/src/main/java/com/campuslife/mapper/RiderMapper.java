package com.campuslife.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface RiderMapper {
    @Select("""
        SELECT o.id, o.shop_id AS shopId, s.name AS shopName,
               COALESCE(NULLIF(s.address,''),s.notice,'') AS shopAddress,
               o.address, o.contact_name AS contactName, o.contact_phone AS contactPhone,
               o.contact_phone AS contactPhoneFull,
               o.items, o.delivery_fee AS deliveryFee, o.created_at AS createdAt
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        WHERE o.status='accepted' AND o.rider_id IS NULL AND s.school_id=#{schoolId}
        ORDER BY o.created_at ASC, o.id ASC
        """)
    List<Map<String,Object>> pool(@Param("schoolId") Long schoolId);

    @Select("""
        SELECT
          (SELECT COUNT(*) FROM user_order o JOIN shop s ON s.id=o.shop_id
            WHERE o.status='accepted' AND o.rider_id IS NULL AND s.school_id=#{schoolId}) AS pendingCount,
          (SELECT COUNT(*) FROM user_order WHERE rider_id=#{riderId} AND status IN ('delivering','picked')) AS activeCount,
          (SELECT COUNT(*) FROM user_order WHERE rider_id=#{riderId} AND status='completed'
            AND delivered_at >= CURDATE() AND delivered_at < CURDATE() + INTERVAL 1 DAY) AS completedToday,
          (SELECT rider_status FROM admin_user WHERE id=#{riderId}) AS riderStatus
        """)
    Map<String,Object> stats(@Param("schoolId") Long schoolId, @Param("riderId") Long riderId);

    @Select("""
        SELECT o.id, o.shop_id AS shopId, s.name AS shopName,
               COALESCE(NULLIF(s.address,''),s.notice,'') AS shopAddress,
               s.contact_phone AS shopPhone, o.address, o.contact_name AS contactName,
               o.contact_phone AS contactPhone, o.contact_phone AS contactPhoneFull,
               o.items, o.delivery_fee AS deliveryFee,
               o.created_at AS createdAt, o.picked_at AS pickedAt, o.status
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        WHERE o.rider_id=#{riderId} AND o.status IN ('delivering','picked')
        ORDER BY FIELD(o.status,'delivering','picked'), o.accepted_at ASC, o.id ASC
        """)
    List<Map<String,Object>> mine(@Param("riderId") Long riderId);

    @Update("""
        UPDATE user_order o
        JOIN shop s ON s.id=o.shop_id
        JOIN admin_user a ON a.id=#{riderId} AND a.role='rider' AND a.status=1 AND a.school_id=s.school_id
        SET o.rider_id=#{riderId}, o.rider_name=#{riderName}, o.status='delivering', o.accepted_at=NOW(),
            a.rider_status='busy'
        WHERE o.id=#{orderId} AND o.status='accepted' AND o.rider_id IS NULL
        """)
    int grab(@Param("orderId") Long orderId, @Param("riderId") Long riderId, @Param("riderName") String riderName);

    @Update("UPDATE user_order SET status='picked', picked_at=NOW() WHERE id=#{orderId} AND rider_id=#{riderId} AND status='delivering'")
    int pick(@Param("orderId") Long orderId, @Param("riderId") Long riderId);

    @Update("UPDATE user_order SET status='completed', delivered_at=NOW() WHERE id=#{orderId} AND rider_id=#{riderId} AND status='picked'")
    int deliver(@Param("orderId") Long orderId, @Param("riderId") Long riderId);

    @Update("""
        UPDATE admin_user SET rider_status=CASE WHEN EXISTS (
          SELECT 1 FROM user_order WHERE rider_id=#{riderId} AND status IN ('delivering','picked')
        ) THEN 'busy' ELSE 'idle' END WHERE id=#{riderId} AND role='rider'
        """)
    int refreshRiderStatus(@Param("riderId") Long riderId);

    @Select("""
        <script>
        SELECT o.id, o.shop_id AS shopId, s.name AS shopName, o.address, o.items,
               o.delivery_fee AS deliveryFee, o.delivered_at AS deliveredAt, o.created_at AS createdAt
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        WHERE o.rider_id=#{riderId} AND o.status='completed'
        <if test="startDate != null and startDate != ''">AND o.delivered_at &gt;= CONCAT(#{startDate},' 00:00:00')</if>
        <if test="endDate != null and endDate != ''">AND o.delivered_at &lt; DATE_ADD(#{endDate}, INTERVAL 1 DAY)</if>
        ORDER BY o.delivered_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> history(@Param("riderId") Long riderId, @Param("startDate") String startDate,
                                    @Param("endDate") String endDate, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM user_order o WHERE o.rider_id=#{riderId} AND o.status='completed'
        <if test="startDate != null and startDate != ''">AND o.delivered_at &gt;= CONCAT(#{startDate},' 00:00:00')</if>
        <if test="endDate != null and endDate != ''">AND o.delivered_at &lt; DATE_ADD(#{endDate}, INTERVAL 1 DAY)</if>
        </script>
        """)
    long countHistory(@Param("riderId") Long riderId, @Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("""
        SELECT COUNT(*) AS monthCount, COALESCE(SUM(delivery_fee),0) AS monthFee
        FROM user_order WHERE rider_id=#{riderId} AND status='completed'
          AND delivered_at >= DATE_FORMAT(CURDATE(),'%Y-%m-01')
          AND delivered_at < CURDATE() + INTERVAL 1 DAY
        """)
    Map<String,Object> monthlySummary(@Param("riderId") Long riderId);

    @Select("""
        SELECT o.id, o.status, o.address, o.contact_name AS contactName,
               o.contact_phone AS contactPhone, o.items, o.delivery_fee AS deliveryFee,
               o.created_at AS createdAt, o.accepted_at AS acceptedAt, o.picked_at AS pickedAt,
               o.delivered_at AS deliveredAt, s.name AS shopName,
               COALESCE(NULLIF(s.address,''),s.notice,'') AS shopAddress,
               o.rider_name AS riderName, a.phone AS riderPhone
        FROM user_order o JOIN shop s ON s.id=o.shop_id
        LEFT JOIN admin_user a ON a.id=o.rider_id
        WHERE o.id=#{orderId} AND o.user_id=#{userId}
        """)
    Map<String,Object> studentOrder(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
