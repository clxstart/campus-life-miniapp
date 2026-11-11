package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 兑换商品池与兑换记录。
 *
 * 并发控制有两条独立的数据库级约束，缺一不可：
 *   * 库存：UPDATE ... WHERE stock > 0（条件更新，卖完即 0 行）
 *   * 限购：redeem_record 按 (user_id,item_key) 计数（同一用户第 N+1 次拒绝）
 * 扣积分另有 PointsService.spend 的条件扣减，三者任一失败都回滚整笔兑换。
 */
@Mapper
public interface RewardMapper {

    @Select("""
        SELECT item_key AS itemKey, name, zone, price_p AS priceP, cash_cents AS cashCents,
               stock, per_user_limit AS perUserLimit, coupon_title AS couponTitle,
               coupon_amount AS couponAmount, coupon_expire AS couponExpire,
               threshold_cents AS thresholdCents, expire_days AS expireDays, enabled, sort
        FROM reward_item ORDER BY sort, item_key
        """)
    List<Map<String, Object>> all();

    @Select("""
        SELECT item_key AS itemKey, name, zone, price_p AS priceP, cash_cents AS cashCents,
               stock, per_user_limit AS perUserLimit, coupon_title AS couponTitle,
               coupon_amount AS couponAmount, coupon_expire AS couponExpire,
               threshold_cents AS thresholdCents, expire_days AS expireDays, enabled, sort
        FROM reward_item WHERE item_key = #{itemKey}
        """)
    Map<String, Object> item(@Param("itemKey") String itemKey);

    @Update("UPDATE reward_item SET stock = stock - 1 WHERE item_key = #{itemKey} AND stock > 0 AND enabled = 1")
    int decreaseStock(@Param("itemKey") String itemKey);

    @Select("SELECT COUNT(*) FROM redeem_record WHERE user_id = #{userId} AND item_key = #{itemKey}")
    int countRedeem(@Param("userId") Long userId, @Param("itemKey") String itemKey);

    @Insert("""
        INSERT INTO redeem_record(user_id, item_key, price_p, coupon_id)
        VALUES(#{userId}, #{itemKey}, #{priceP}, #{couponId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRedeemRecord(Map<String, Object> row);

    @Update("""
        UPDATE reward_item SET price_p = #{priceP}, stock = #{stock}, per_user_limit = #{perUserLimit},
               coupon_title = #{couponTitle}, coupon_amount = #{couponAmount}, coupon_expire = #{couponExpire},
               threshold_cents = #{thresholdCents}, expire_days = #{expireDays},
               enabled = #{enabled}, sort = #{sort}
        WHERE item_key = #{itemKey}
        """)
    int updateItem(Map<String, Object> row);
}