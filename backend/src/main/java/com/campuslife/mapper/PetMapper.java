package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Map;

@Mapper public interface PetMapper {
    @Select("SELECT p.id, p.user_id AS userId, p.name, p.level, p.exp, p.hunger, p.mood, " +
            "p.last_decay_at AS lastDecayAt, p.last_feed_at AS lastFeedAt, p.last_touch_at AS lastTouchAt " +
            "FROM user_pet p WHERE p.user_id = #{userId}")
    Map<String, Object> findPet(@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO user_pet (user_id) VALUES (#{userId})")
    void createPet(@Param("userId") Long userId);

    @Update("UPDATE user_pet SET hunger = #{hunger}, mood = #{mood}, last_decay_at = #{now} WHERE user_id = #{userId}")
    void updateVitals(@Param("userId") Long userId, @Param("hunger") int hunger,
                      @Param("mood") int mood, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM pet_interaction_log WHERE user_id = #{userId} " +
            "AND action = #{action} AND created_at >= CURDATE()")
    int countToday(@Param("userId") Long userId, @Param("action") String action);

    // 积分加减分已全部迁到 PointsMapper / PointsService（唯一写入口）。
    // 这里只保留建行与读取：ensurePoints 是 INSERT IGNORE，不构成第二套账。

    @Insert("INSERT IGNORE INTO user_points (user_id, balance, total_earned) VALUES (#{userId}, 0, 0)")
    void ensurePoints(@Param("userId") Long userId);

    @Insert("INSERT INTO pet_interaction_log (user_id, action, exp_gain, cost_points) " +
            "VALUES (#{userId}, #{action}, #{expGain}, #{cost})")
    void logInteract(@Param("userId") Long userId, @Param("action") String action,
                     @Param("expGain") int expGain, @Param("cost") int cost);

    @Select("SELECT COALESCE(balance, 0) FROM user_points WHERE user_id = #{userId}")
    Integer pointsOf(@Param("userId") Long userId);

    @Update("UPDATE user_pet SET hunger = #{hunger}, mood = #{mood}, level = #{level}, exp = #{exp}, " +
            "last_feed_at = IF(#{action} = 'feed', NOW(), last_feed_at), " +
            "last_touch_at = IF(#{action} = 'touch', NOW(), last_touch_at), " +
            "last_decay_at = NOW() " +
            "WHERE user_id = #{userId}")
    void applyInteract(@Param("userId") Long userId, @Param("hunger") int hunger,
                       @Param("mood") int mood, @Param("level") int level, @Param("exp") int exp,
                       @Param("action") String action, @Param("expGain") int expGain, @Param("cost") int cost);

    @Update("UPDATE user_pet SET name = #{name} WHERE user_id = #{userId}")
    void rename(@Param("userId") Long userId, @Param("name") String name);
}
