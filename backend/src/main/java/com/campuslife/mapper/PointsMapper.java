package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 积分账本的唯一写入口。
 *
 * 纪律：全仓库只允许本 Mapper 对 `user_points` / `user_growth` / `point_log` 做写操作，
 * 业务侧一律通过 {@link com.campuslife.service.PointsService} 调用。
 * 宠物模块原有的 addPoints / deductPoints / logPoints 已经改走 PointsService，
 * 再出现第二处 UPDATE user_points 就是账目对不上的开始。
 */
@Mapper
public interface PointsMapper {

    // ==================== 任务目录 ====================

    @Select("""
        SELECT task_key AS taskKey, module, name, p_reward AS pReward, g_reward AS gReward,
               daily_cap AS dailyCap, enabled, sort
        FROM task_catalog ORDER BY sort, task_key
        """)
    List<Map<String, Object>> taskCatalog();

    @Select("""
        SELECT task_key AS taskKey, module, name, p_reward AS pReward, g_reward AS gReward,
               daily_cap AS dailyCap, enabled, sort
        FROM task_catalog WHERE task_key = #{taskKey}
        """)
    Map<String, Object> task(@Param("taskKey") String taskKey);

    @Update("""
        UPDATE task_catalog SET p_reward = #{p}, g_reward = #{g}, daily_cap = #{dailyCap}, enabled = #{enabled}
        WHERE task_key = #{taskKey}
        """)
    int updateTask(@Param("taskKey") String taskKey, @Param("p") int p, @Param("g") int g,
                   @Param("dailyCap") int dailyCap, @Param("enabled") int enabled);

    // ==================== 任务事实（幂等 + 日上限）====================

    @Insert("""
        INSERT INTO task_log(user_id, task_key, biz_date, biz_key, p, g)
        VALUES(#{userId}, #{taskKey}, #{bizDate}, #{bizKey}, #{p}, #{g})
        """)
    int insertTaskLog(@Param("userId") Long userId, @Param("taskKey") String taskKey,
                      @Param("bizDate") LocalDate bizDate, @Param("bizKey") String bizKey,
                      @Param("p") int p, @Param("g") int g);

    @Select("""
        SELECT COUNT(*) FROM task_log
        WHERE user_id = #{userId} AND task_key = #{taskKey} AND biz_date = #{bizDate}
        """)
    int countTaskToday(@Param("userId") Long userId, @Param("taskKey") String taskKey,
                       @Param("bizDate") LocalDate bizDate);

    /** 今日各任务的完成次数，一次查完，避免任务列表 N+1。 */
    @Select("""
        SELECT task_key AS taskKey, COUNT(*) AS done
        FROM task_log
        WHERE user_id = #{userId} AND biz_date = #{bizDate}
        GROUP BY task_key
        """)
    List<Map<String, Object>> taskDoneToday(@Param("userId") Long userId, @Param("bizDate") LocalDate bizDate);

    // ==================== 余额 ====================

    @Insert("INSERT INTO user_points(user_id, balance, total_earned) VALUES(#{userId}, 0, 0) " +
            "ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensurePointsRow(@Param("userId") Long userId);

    @Update("""
        UPDATE user_points
        SET balance = balance + #{delta},
            total_earned = total_earned + GREATEST(#{earned}, 0)
        WHERE user_id = #{userId}
        """)
    int addPoints(@Param("userId") Long userId, @Param("delta") int delta, @Param("earned") int earned);

    /** 条件扣减：余额不够时影响行数为 0，绝不扣成负数。 */
    @Update("UPDATE user_points SET balance = balance - #{cost} WHERE user_id = #{userId} AND balance >= #{cost}")
    int deductPoints(@Param("userId") Long userId, @Param("cost") int cost);

    @Select("SELECT COALESCE(balance, 0) FROM user_points WHERE user_id = #{userId}")
    int balance(@Param("userId") Long userId);

    @Select("SELECT COALESCE(total_earned, 0) FROM user_points WHERE user_id = #{userId}")
    int totalEarned(@Param("userId") Long userId);

    // ==================== 成长值 ====================

    @Insert("INSERT INTO user_growth(user_id, g_total) VALUES(#{userId}, 0) ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensureGrowthRow(@Param("userId") Long userId);

    @Update("UPDATE user_growth SET g_total = g_total + #{delta} WHERE user_id = #{userId}")
    int addGrowth(@Param("userId") Long userId, @Param("delta") int delta);

    @Select("SELECT COALESCE(g_total, 0) FROM user_growth WHERE user_id = #{userId}")
    int growth(@Param("userId") Long userId);

    // ==================== 流水 ====================

    @Insert("""
        INSERT INTO point_log(user_id, amount, type, ref_id, remark, module, biz_key, g_amount, balance_after)
        VALUES(#{userId}, #{amount}, #{type}, #{refId}, #{remark}, #{module}, #{bizKey}, #{gAmount}, #{balanceAfter})
        """)
    int insertLog(@Param("userId") Long userId, @Param("amount") int amount, @Param("type") String type,
                  @Param("refId") Long refId, @Param("remark") String remark, @Param("module") String module,
                  @Param("bizKey") String bizKey, @Param("gAmount") int gAmount,
                  @Param("balanceAfter") Integer balanceAfter);

    @Select("""
        SELECT id, amount, type, remark, module, g_amount AS gAmount,
               balance_after AS balanceAfter, created_at AS createdAt
        FROM point_log WHERE user_id = #{userId}
        ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String, Object>> logs(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM point_log WHERE user_id = #{userId}")
    long countLogs(@Param("userId") Long userId);
}