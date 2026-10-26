package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 校园搭子：外观 / 衣柜 / 派遣 */
@Mapper
public interface PetCompanionMapper {

    // ==================== 外观 ====================

    @Update("UPDATE user_pet SET species=#{species},tint=#{tint},accessory=#{accessory},mood_tag=#{moodTag} WHERE user_id=#{userId}")
    int saveAppearance(@Param("userId") Long userId,
                       @Param("species") String species,
                       @Param("tint") String tint,
                       @Param("accessory") String accessory,
                       @Param("moodTag") String moodTag);

    @Select("SELECT species,tint,accessory,mood_tag FROM user_pet WHERE user_id=#{userId}")
    Map<String, Object> appearance(@Param("userId") Long userId);

    // ==================== 衣柜 ====================

    /** 物品列表 + 当前用户是否拥有 / 装备 */
    @Select("""
        SELECT i.id, i.code, i.name, i.category, i.part_id AS partId, i.price,
               i.description, i.sort_num AS sortNum,
               CASE WHEN o.id IS NULL THEN 0 ELSE 1 END AS owned,
               CASE WHEN o.equipped = 1 THEN 1 ELSE 0 END AS equipped
        FROM pet_item i
        LEFT JOIN pet_item_owned o ON o.item_id = i.id AND o.user_id = #{userId}
        WHERE i.status = 1
        ORDER BY i.sort_num ASC, i.id ASC
        """)
    List<Map<String, Object>> items(@Param("userId") Long userId);

    @Select("SELECT id, code, name, category, part_id AS partId, price FROM pet_item WHERE code=#{code} AND status=1")
    Map<String, Object> itemByCode(@Param("code") String code);

    @Select("SELECT * FROM pet_item_owned WHERE user_id=#{userId} AND item_id=#{itemId}")
    Map<String, Object> owned(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Insert("INSERT INTO pet_item_owned(user_id,item_id,equipped) VALUES(#{userId},#{itemId},0)")
    int addOwned(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Update("UPDATE pet_item_owned SET equipped=0 WHERE user_id=#{userId}")
    int unequipAll(@Param("userId") Long userId);

    @Update("UPDATE pet_item_owned SET equipped=1 WHERE user_id=#{userId} AND item_id=#{itemId}")
    int equip(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Select("SELECT i.part_id AS partId FROM pet_item_owned o JOIN pet_item i ON i.id=o.item_id WHERE o.user_id=#{userId} AND o.equipped=1 LIMIT 1")
    String equippedPart(@Param("userId") Long userId);

    // ==================== 积分 ====================

    @Select("SELECT balance FROM user_points WHERE user_id=#{userId}")
    Integer balance(@Param("userId") Long userId);

    /** 扣积分：带余额校验，防止扣成负数 */
    @Update("UPDATE user_points SET balance = balance - #{cost} WHERE user_id=#{userId} AND balance >= #{cost}")
    int deductPoints(@Param("userId") Long userId, @Param("cost") int cost);

    /** 积分流水：point_log 的实际字段是 amount / type / remark */
    @Insert("INSERT INTO point_log(user_id, amount, type, remark, created_at) VALUES(#{userId}, #{change}, #{type}, #{reason}, NOW())")
    int logPoints(@Param("userId") Long userId, @Param("change") int change,
                  @Param("type") String type, @Param("reason") String reason);

    @Insert("INSERT INTO user_points(user_id, balance, total_earned) VALUES(#{userId}, 0, 0) ON DUPLICATE KEY UPDATE user_id=user_id")
    int ensurePointsRow(@Param("userId") Long userId);

    // ==================== 派遣 ====================

    @Select("""
        SELECT id, code, title, description,
               duration_min AS durationMin, reward_min AS rewardMin, reward_max AS rewardMax
        FROM pet_task WHERE status=1 ORDER BY sort_num ASC, id ASC
        """)
    List<Map<String, Object>> tasks();

    @Select("SELECT id, code, title, duration_min AS durationMin, reward_min AS rewardMin, reward_max AS rewardMax FROM pet_task WHERE code=#{code} AND status=1")
    Map<String, Object> taskByCode(@Param("code") String code);

    /** 同一时刻只允许一个进行中的派遣 */
    @Select("SELECT COUNT(*) FROM pet_dispatch WHERE user_id=#{userId} AND status IN ('running','claimable')")
    int activeCount(@Param("userId") Long userId);

    @Insert("INSERT INTO pet_dispatch(user_id,task_id,start_at,end_at,status) VALUES(#{userId},#{taskId},#{startAt},#{endAt},'running')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int startDispatch(Map<String, Object> row);

    @Select("""
        SELECT d.id, d.status, d.start_at AS startAt, d.end_at AS endAt, d.reward, d.claimed_at AS claimedAt,
               t.code AS taskCode, t.title, t.description,
               t.duration_min AS durationMin, t.reward_min AS rewardMin, t.reward_max AS rewardMax,
               TIMESTAMPDIFF(SECOND, NOW(), d.end_at) AS remainSeconds
        FROM pet_dispatch d JOIN pet_task t ON t.id = d.task_id
        WHERE d.user_id=#{userId} ORDER BY d.id DESC LIMIT 1
        """)
    Map<String, Object> latestDispatch(@Param("userId") Long userId);

    /**
     * 到期仍未标记为可领的派遣，由服务端在查询时刷新状态。
     * 只改 running → claimable，不动已领取的。
     */
    @Update("UPDATE pet_dispatch SET status='claimable' WHERE status='running' AND end_at <= NOW()")
    int markDue();

    @Select("SELECT * FROM pet_dispatch WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> dispatch(@Param("userId") Long userId, @Param("id") Long id);

    /** 领取：只能领一次的原子更新 */
    @Update("UPDATE pet_dispatch SET status='claimed', reward=#{reward}, claimed_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND status='claimable'")
    int claim(@Param("userId") Long userId, @Param("id") Long id, @Param("reward") int reward);

    @Update("UPDATE user_points SET balance = balance + #{amount}, total_earned = total_earned + #{amount} WHERE user_id=#{userId}")
    int addPoints(@Param("userId") Long userId, @Param("amount") int amount);

    @Select("SELECT COUNT(*) FROM pet_dispatch WHERE user_id=#{userId} AND status='claimed'")
    int claimedCount(@Param("userId") Long userId);
}
