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

    /** 加锁读：RR 隔离级别下普通 SELECT 读的是事务快照，可能拿到旧连续天数；FOR UPDATE 读最新已提交版本。 */
    @Select("SELECT id, streak_days AS streakDays, last_active_date AS lastActiveDate, spark_on AS sparkOn FROM circle WHERE id=#{circleId} FOR UPDATE")
    Map<String, Object> streak(@Param("circleId") Long circleId);

    /**
     * 锁住圈子行再读改写连续天数：跨午夜两个请求会各自 INSERT IGNORE 成功（日期不同），
     * 没有行锁时可能互相用旧值覆盖（后写者把连续天数写回 1）。事务内 FOR UPDATE 串行化。
     */
    @Select("SELECT id FROM circle WHERE id=#{circleId} FOR UPDATE")
    Long lockCircle(@Param("circleId") Long circleId);

    @Update("""
        UPDATE circle SET streak_days=#{streakDays}, last_active_date=#{lastActiveDate}, spark_on=#{sparkOn}
        WHERE id=#{circleId}
        """)
    int updateStreak(@Param("circleId") Long circleId,
                     @Param("streakDays") int streakDays,
                     @Param("lastActiveDate") LocalDate lastActiveDate,
                     @Param("sparkOn") int sparkOn);
}
