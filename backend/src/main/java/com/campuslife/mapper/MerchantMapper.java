package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface MerchantMapper {
    @Insert("""
        INSERT INTO merchant_apply(shop_name, category, contact_name, contact_phone, address, school_id,
          license_no, license_image, shop_image, intro)
        VALUES(#{shopName}, #{category}, #{contactName}, #{contactPhone}, #{address}, #{schoolId},
          #{licenseNo}, #{licenseImage}, #{shopImage}, #{intro})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertApply(Map<String,Object> row);

    @Select("SELECT COUNT(*) FROM school WHERE id=#{id} AND status=1")
    int activeSchool(@Param("id") Long id);

    @Select("SELECT id, name FROM school WHERE id=#{id}")
    Map<String,Object> school(@Param("id") Long id);

    @Select("""
        <script>
        SELECT a.id, a.shop_name AS shopName, a.category, a.contact_name AS contactName,
          a.contact_phone AS contactPhone, a.address, a.school_id AS schoolId, sc.name AS schoolName,
          a.license_no AS licenseNo, a.license_image AS licenseImage, a.shop_image AS shopImage,
          a.intro, a.status, a.reject_reason AS rejectReason, a.audit_by AS auditBy,
          a.audit_at AS auditAt, a.created_shop_id AS createdShopId, a.created_at AS createdAt
        FROM merchant_apply a JOIN school sc ON sc.id=a.school_id
        WHERE (#{schoolId} IS NULL OR a.school_id=#{schoolId})
          <if test="status != null and status != ''">AND a.status=#{status}</if>
        ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> applications(@Param("schoolId") Long schoolId, @Param("status") String status,
                                          @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        SELECT a.id, a.shop_name AS shopName, a.category, a.contact_name AS contactName,
          a.contact_phone AS contactPhone, a.address, a.school_id AS schoolId, sc.name AS schoolName,
          a.license_no AS licenseNo, a.license_image AS licenseImage, a.shop_image AS shopImage,
          a.intro, a.status, a.reject_reason AS rejectReason, a.audit_by AS auditBy,
          a.audit_at AS auditAt, a.created_shop_id AS createdShopId, a.created_at AS createdAt
        FROM merchant_apply a JOIN school sc ON sc.id=a.school_id WHERE a.id=#{id}
        """)
    Map<String,Object> application(@Param("id") Long id);

    @Select("SELECT id, shop_name AS shopName, category, contact_name AS contactName, contact_phone AS contactPhone, address, school_id AS schoolId, license_no AS licenseNo, license_image AS licenseImage, shop_image AS shopImage, intro, status FROM merchant_apply WHERE id=#{id} FOR UPDATE")
    Map<String,Object> applicationForUpdate(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM merchant_apply WHERE school_id=#{schoolId} AND status=#{status}")
    long countApplications(@Param("schoolId") Long schoolId, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM merchant_apply WHERE (#{schoolId} IS NULL OR school_id=#{schoolId})")
    long countApplicationsAll(@Param("schoolId") Long schoolId);

    @Update("UPDATE merchant_apply SET status='approved', audit_by=#{auditBy}, audit_at=NOW(), created_shop_id=#{shopId} WHERE id=#{id} AND status='pending'")
    int approveApply(@Param("id") Long id, @Param("auditBy") Long auditBy, @Param("shopId") Long shopId);

    @Update("UPDATE merchant_apply SET status='rejected', reject_reason=#{reason}, audit_by=#{auditBy}, audit_at=NOW() WHERE id=#{id} AND status='pending'")
    int rejectApply(@Param("id") Long id, @Param("auditBy") Long auditBy, @Param("reason") String reason);

    @Insert("""
        INSERT INTO shop(name, cover, category, rating, monthly_sales, min_price, delivery_fee, delivery_time,
          discount, notice, discount_threshold, discount_amount, status, school_id, contact_phone, address,
          owner_name, intro, open_time, close_time, is_open)
        VALUES(#{name}, #{cover}, #{category}, 5.0, 0, 0, 0, 30, '', '', 0, 0, 0, #{schoolId},
          #{contactPhone}, #{address}, #{ownerName}, #{intro}, '09:00', '21:00', 1)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertMerchantShop(Map<String,Object> row);

    @Select("SELECT COUNT(*) FROM admin_user WHERE username=#{username}")
    int usernameExists(@Param("username") String username);

    @Insert("INSERT INTO admin_user(username,password_hash,real_name,role,shop_id) VALUES(#{username},#{passwordHash},#{realName},'merchant',#{shopId})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertMerchantUser(Map<String,Object> row);

    @Select("""
        SELECT id, name, cover, category, rating, monthly_sales AS monthlySales, min_price AS minPrice,
          delivery_fee AS deliveryFee, delivery_time AS deliveryTime, discount, notice,
          discount_threshold AS discountThreshold, discount_amount AS discountAmount, status,
          school_id AS schoolId, contact_phone AS contactPhone, address, owner_name AS ownerName,
          intro, open_time AS openTime, close_time AS closeTime, is_open AS isOpen
        FROM shop WHERE id=#{shopId}
        """)
    Map<String,Object> shop(@Param("shopId") Long shopId);

    @Select("SELECT id, shop_id AS shopId, category, name, price, monthly_sales AS monthlySales, image, sort_num AS sortNum, status FROM shop_goods WHERE id=#{id} AND shop_id=#{shopId}")
    Map<String,Object> goods(@Param("id") Long id, @Param("shopId") Long shopId);

    @Select("SELECT id, shop_id AS shopId, category, name, price, monthly_sales AS monthlySales, image, sort_num AS sortNum, status FROM shop_goods WHERE shop_id=#{shopId} ORDER BY sort_num,id")
    List<Map<String,Object>> goodsList(@Param("shopId") Long shopId);

    @Insert("INSERT INTO shop_goods(shop_id,category,name,price,image,sort_num,status) VALUES(#{shopId},#{category},#{name},#{price},#{image},#{sortNum},#{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertGoods(Map<String,Object> row);

    @Update("UPDATE shop_goods SET category=#{category},name=#{name},price=#{price},image=#{image},sort_num=#{sortNum} WHERE id=#{id} AND shop_id=#{shopId}")
    int updateGoods(Map<String,Object> row);

    @Update("UPDATE shop_goods SET status=#{status} WHERE id=#{id} AND shop_id=#{shopId}")
    int updateGoodsStatus(@Param("id") Long id, @Param("shopId") Long shopId, @Param("status") int status);

    @Delete("DELETE FROM shop_goods WHERE id=#{id} AND shop_id=#{shopId}")
    int deleteGoods(@Param("id") Long id, @Param("shopId") Long shopId);

    @Update("""
        <script>
        UPDATE shop_goods SET status=#{status} WHERE shop_id=#{shopId} AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int updateGoodsStatusBatch(@Param("shopId") Long shopId, @Param("ids") List<Long> ids, @Param("status") int status);

    @Update("""
        UPDATE shop SET name=#{name}, cover=#{cover}, category=#{category}, contact_phone=#{contactPhone},
          address=#{address}, intro=#{intro}, min_price=#{minPrice}, delivery_fee=#{deliveryFee},
          delivery_time=#{deliveryTime}, notice=#{notice}, discount=#{discount},
          discount_threshold=#{discountThreshold}, discount_amount=#{discountAmount},
          open_time=#{openTime}, close_time=#{closeTime}, is_open=#{isOpen}
        WHERE id=#{shopId}
        """)
    int updateShop(Map<String,Object> row);

    @Select("SELECT COUNT(*) FROM user_order WHERE shop_id=#{shopId} AND created_at>=CURDATE() AND created_at<DATE_ADD(CURDATE(), INTERVAL 1 DAY) AND status NOT IN ('created','cancelled')")
    long todayOrders(@Param("shopId") Long shopId);

    @Select("SELECT COALESCE(SUM(total),0) FROM user_order WHERE shop_id=#{shopId} AND created_at>=CURDATE() AND created_at<DATE_ADD(CURDATE(), INTERVAL 1 DAY) AND status IN ('accepted','completed')")
    java.math.BigDecimal todayRevenue(@Param("shopId") Long shopId);

    @Select("SELECT COUNT(*) FROM user_order WHERE shop_id=#{shopId} AND status='paid'")
    long pendingOrders(@Param("shopId") Long shopId);

    @Select("""
        <script>
        SELECT o.id, o.user_id AS userId, u.nickname AS userName, o.shop_id AS shopId,
          s.name AS shopName, o.items, o.address, o.remark, o.subtotal, o.packing_fee AS packingFee,
          o.delivery_fee AS deliveryFee, o.discount_amount AS discountAmount, o.total, o.status,
          o.rider_id AS riderId, o.rider_name AS riderName,
          o.created_at AS createdAt
        FROM user_order o JOIN `user` u ON u.id=o.user_id JOIN shop s ON s.id=o.shop_id
        WHERE o.shop_id=#{shopId} AND o.status != 'created'<if test="status != null and status != ''"> AND o.status=#{status}</if>
        ORDER BY o.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> orders(@Param("shopId") Long shopId, @Param("status") String status,
                                   @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM user_order WHERE shop_id=#{shopId} AND status <> 'created' AND (#{status} IS NULL OR #{status}='' OR status=#{status})")
    long countOrders(@Param("shopId") Long shopId, @Param("status") String status);

    @Select("SELECT id, user_id AS userId, items, total, status, pay_status AS payStatus, pay_amount_cents AS payAmountCents, out_trade_no AS outTradeNo, transaction_id AS transactionId, rider_id AS riderId, rider_name AS riderName FROM user_order WHERE id=#{id} AND shop_id=#{shopId}")
    Map<String,Object> order(@Param("id") Long id, @Param("shopId") Long shopId);

    @Update("UPDATE user_order SET status=#{nextStatus} WHERE id=#{id} AND shop_id=#{shopId} AND status='paid'")
    int acceptOrder(@Param("id") Long id, @Param("shopId") Long shopId, @Param("nextStatus") String nextStatus);

    @Update("UPDATE user_order SET status='cancelled' WHERE id=#{id} AND shop_id=#{shopId} AND status='paid'")
    int cancelOrder(@Param("id") Long id, @Param("shopId") Long shopId);

    @Update("UPDATE user_order SET status='completed' WHERE id=#{id} AND shop_id=#{shopId} AND status='accepted'")
    int completeOrder(@Param("id") Long id, @Param("shopId") Long shopId);

    @Insert("INSERT IGNORE INTO refund_record(order_id,out_refund_no,refund_id,amount_cents,reason,status) VALUES(#{orderId},#{outRefundNo},'',#{amountCents},#{reason},'pending')")
    int insertRefund(@Param("orderId") Long orderId, @Param("outRefundNo") String outRefundNo,
                     @Param("amountCents") Integer amountCents, @Param("reason") String reason);

    @Select("SELECT r.order_id AS orderId, r.out_refund_no AS outRefundNo, r.refund_id AS refundId, r.amount_cents AS amountCents, r.status AS refundStatus, o.user_id AS userId, o.pay_status AS payStatus, o.pay_amount_cents AS payAmountCents, o.out_trade_no AS outTradeNo, o.transaction_id AS transactionId FROM refund_record r JOIN user_order o ON o.id=r.order_id WHERE r.order_id=#{orderId} ORDER BY r.id DESC LIMIT 1")
    Map<String,Object> refundContext(@Param("orderId") Long orderId);

    @Select("SELECT r.order_id AS orderId, r.out_refund_no AS outRefundNo, r.amount_cents AS amountCents, r.status AS refundStatus, o.user_id AS userId, o.out_trade_no AS outTradeNo, o.transaction_id AS transactionId FROM refund_record r JOIN user_order o ON o.id=r.order_id WHERE r.out_refund_no=#{outRefundNo} LIMIT 1 FOR UPDATE")
    Map<String,Object> refundContextByNumber(@Param("outRefundNo") String outRefundNo);

    @Update("UPDATE refund_record SET refund_id=COALESCE(NULLIF(#{refundId},''),refund_id), status=CASE WHEN status='success' THEN status WHEN #{status}='success' THEN 'success' WHEN status='failed' AND #{status}='pending' THEN status ELSE #{status} END, reason=CASE WHEN #{reason} IS NULL OR #{reason}='' THEN reason ELSE #{reason} END WHERE out_refund_no=#{outRefundNo}")
    int updateRefund(@Param("outRefundNo") String outRefundNo, @Param("refundId") String refundId,
                     @Param("status") String status, @Param("reason") String reason);

    @Update("UPDATE user_order SET pay_status=CASE WHEN pay_status='refunded' THEN pay_status ELSE #{status} END WHERE id=#{orderId}")
    int updatePayStatus(@Param("orderId") Long orderId, @Param("status") String status);

    @Insert("INSERT INTO notification(user_id,title,content,category,tone) VALUES(#{userId},#{title},#{content},'food','blue')")
    int notifyUser(@Param("userId") Long userId, @Param("title") String title, @Param("content") String content);

    @Insert("INSERT IGNORE INTO notification(user_id,title,content,category,tone,source_key) VALUES(#{userId},#{title},#{content},'food','blue',#{sourceKey})")
    int notifyRefund(@Param("userId") Long userId, @Param("title") String title,
                     @Param("content") String content, @Param("sourceKey") String sourceKey);

    @Select("""
        SELECT o.id, o.shop_id AS shopId, s.name AS shopName, o.items, o.total, o.status, o.created_at AS createdAt
        FROM user_order o JOIN shop s ON s.id=o.shop_id WHERE o.user_id=#{userId}
        ORDER BY o.id DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> userOrders(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM user_order WHERE user_id=#{userId}")
    long countUserOrders(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_order WHERE id=#{id} AND user_id=#{userId}")
    int userOwnsOrder(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT o.id, o.user_id AS userId, o.shop_id AS shopId, s.name AS shopName, o.items, o.address, o.remark, o.subtotal, o.packing_fee AS packingFee, o.delivery_fee AS deliveryFee, o.discount_amount AS discountAmount, o.total, o.status, o.created_at AS createdAt FROM user_order o JOIN shop s ON s.id=o.shop_id WHERE o.id=#{id} AND o.user_id=#{userId}")
    Map<String,Object> userOrder(@Param("id") Long id, @Param("userId") Long userId);
}
