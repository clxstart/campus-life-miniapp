package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.Map;

@Mapper
public interface CircleStreakMapper {

    /**
     * 记录某个圈子在某一自然日的活跃。
     * INSERT IGNORE + (circle_id, active_date) 唯一键：同一天无论来多少条消息、
     * 并发多高，都只有第一行能插进去，返回 1；其余返回 0。
     */
    @Insert("""
        INSERT IGNORE INTO circle_activity_day(circle_id, active_date, first_message_id, actor_id)
        VALUES(#{circleId}, #{activeDate}, #{messageId}, #{actorId})
        """)
    int markDay(@Param("circleId") Long circleId,
                @Param("activeDate") LocalDate activeDate,
                @Param("messageId") Long messageId,
                @Param("actorId") Long actorId);

    @Select("SELECT id, streak_days AS streakDays, last_active_date AS lastActiveDate, spark_on AS sparkOn FROM circle WHERE id=#{circleId}")
    Map<String, Object> streak(@Param("circleId") Long circleId);

    @Update("""
        UPDATE circle SET streak_days=#{streakDays}, last_active_date=#{lastActiveDate}, spark_on=#{sparkOn}
        WHERE id=#{circleId}
        """)
    int updateStreak(@Param("circleId") Long circleId,
                     @Param("streakDays") int streakDays,
                     @Param("lastActiveDate") LocalDate lastActiveDate,
                     @Param("sparkOn") int sparkOn);
}
