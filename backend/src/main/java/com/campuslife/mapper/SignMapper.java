package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 签到事实表（sign_roll）的数据访问。
 *
 * 一天一行，(user_id, biz_date) 是主键，也就是幂等键：双击只会插成功一次。
 * 连签不单独存累计值，靠「昨天那行是否存在」推算。
 */
@Mapper
public interface SignMapper {

    @Insert("INSERT INTO sign_roll(user_id, biz_date, streak_days) VALUES(#{userId}, #{bizDate}, 1)")
    int insert(@Param("userId") Long userId, @Param("bizDate") LocalDate bizDate);

    @Select("SELECT streak_days FROM sign_roll WHERE user_id = #{userId} AND biz_date = #{bizDate}")
    Integer streakOn(@Param("userId") Long userId, @Param("bizDate") LocalDate bizDate);

    /**
     * 加共享锁读取：专供「主键冲突后回显状态」使用。
     *
     * 普通 SELECT 在本事务已建立一致性读快照后看不到**别的**事务随后提交的行；
     * 并发双击的失败方恰好处在这个位置（它在插入前已经读过 task_catalog），
     * 用普通 SELECT 会把「今天已签」读成「今天没签」。
     *
     * 用共享锁而不是 FOR UPDATE：重复键插入失败的事务会在那一行上留下共享锁，
     * 多个失败方同时升级成排他锁会互相死锁；共享锁之间兼容，且读到的是最新已提交版本。
     * 这一行之后不会再被本事务修改，所以共享锁不碍事。
     */
    @Select("SELECT streak_days FROM sign_roll WHERE user_id = #{userId} AND biz_date = #{bizDate} LOCK IN SHARE MODE")
    Integer streakOnShared(@Param("userId") Long userId, @Param("bizDate") LocalDate bizDate);

    @Update("UPDATE sign_roll SET streak_days = #{streakDays} WHERE user_id = #{userId} AND biz_date = #{bizDate}")
    int updateStreak(@Param("userId") Long userId, @Param("bizDate") LocalDate bizDate,
                     @Param("streakDays") int streakDays);
}