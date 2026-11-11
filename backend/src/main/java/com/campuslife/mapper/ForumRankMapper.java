package com.campuslife.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 论坛榜单（日榜 / 周榜）的读写口。
 *
 * 纪律：forum_rank 只允许本 Mapper 写；发分的唯一出口仍然是 PointsService，
 * 这里不碰 user_points / point_log。
 *
 * 算分口径（设计稿「四、数据表与字段」原文）：
 *   发帖 3 分（status=1，未过审/已下架不计）
 *   评论 2 分（TRIM 后字符数 ≥5，防「顶」「1」刷分）
 *   帖子被赞 1 分、评论被赞 1 分（被赞权重压到 1，避免互赞群主导榜单）
 * 并列名次按 user_id 升序，保证同一份数据每次算出的名次完全一致（可复核）。
 */
@Mapper
public interface ForumRankMapper {

    /**
     * 单周期算分并取前 N 名。
     *
     * 时间窗由服务层按 Asia/Shanghai 算好再传进来（[start, end)），
     * 不在这里用 MySQL 的 CURDATE()/WEEK() —— 数据库时区一旦不是 +08:00，日界会整体错位一天。
     */
    @Select("""
        SELECT uid, SUM(pts) AS score FROM (
          SELECT user_id AS uid, 3 AS pts FROM post
           WHERE status = 1 AND created_at >= #{start} AND created_at < #{end}
          UNION ALL
          SELECT user_id, 2 FROM comment
           WHERE CHAR_LENGTH(TRIM(content)) >= 5 AND created_at >= #{start} AND created_at < #{end}
          UNION ALL
          SELECT p.user_id, 1 FROM post_like l JOIN post p ON p.id = l.post_id
           WHERE l.created_at >= #{start} AND l.created_at < #{end}
          UNION ALL
          SELECT c.user_id, 1 FROM comment_like l JOIN comment c ON c.id = l.comment_id
           WHERE l.created_at >= #{start} AND l.created_at < #{end}
        ) t GROUP BY uid ORDER BY score DESC, uid ASC LIMIT #{limit}
        """)
    List<Map<String, Object>> scoreBoard(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("limit") int limit);

    /**
     * 重结算前清掉该周期「还没发奖」的旧快照。
     *
     * 为什么要删：重结算可能算出更少的上榜者（帖子被删/下架后分数下降），
     * 只 upsert 会让上一轮的人一直挂在榜上。awarded=1 的行永远保留 —— 那是已经发过奖的事实。
     */
    @Delete("DELETE FROM forum_rank WHERE board = #{board} AND period_key = #{periodKey} AND awarded = 0")
    int deleteUnawarded(@Param("board") String board, @Param("periodKey") String periodKey);

    /** 写快照：重复结算只覆盖分数与名次，不动 awarded（已发放的不重复发）。 */
    @Insert("""
        INSERT INTO forum_rank(board, period_key, user_id, score, rank_no, awarded)
        VALUES(#{board}, #{periodKey}, #{userId}, #{score}, #{rankNo}, 0)
        ON DUPLICATE KEY UPDATE score = VALUES(score), rank_no = VALUES(rank_no)
        """)
    int upsert(@Param("board") String board, @Param("periodKey") String periodKey,
               @Param("userId") Long userId, @Param("score") int score, @Param("rankNo") int rankNo);

    /** 发奖成功后置位；条件里带 awarded=0，重复调用不会把状态改乱。 */
    @Update("""
        UPDATE forum_rank SET awarded = 1
        WHERE board = #{board} AND period_key = #{periodKey} AND user_id = #{userId} AND awarded = 0
        """)
    int markAwarded(@Param("board") String board, @Param("periodKey") String periodKey,
                    @Param("userId") Long userId);

    /** 榜单展示：带昵称与头像，一次查完，不在服务层做 N+1。 */
    @Select("""
        SELECT r.rank_no AS rankNo, r.user_id AS userId, r.score, r.awarded,
               u.nickname, u.avatar
        FROM forum_rank r LEFT JOIN `user` u ON u.id = r.user_id
        WHERE r.board = #{board} AND r.period_key = #{periodKey}
        ORDER BY r.rank_no
        """)
    List<Map<String, Object>> rows(@Param("board") String board, @Param("periodKey") String periodKey);

    /** 管理端列表：带结算时间与发放状态，便于定位「发失败的那一行」。 */
    @Select("""
        SELECT r.rank_no AS rankNo, r.user_id AS userId, r.score, r.awarded,
               r.created_at AS settledAt, u.nickname, u.avatar
        FROM forum_rank r LEFT JOIN `user` u ON u.id = r.user_id
        WHERE r.board = #{board} AND r.period_key = #{periodKey}
        ORDER BY r.rank_no
        """)
    List<Map<String, Object>> adminRows(@Param("board") String board, @Param("periodKey") String periodKey);

    /** 「我的排名」：只查快照里有没有我 —— 没上榜就是没上榜，不额外全表扫一遍算实时名次。 */
    @Select("""
        SELECT rank_no AS rankNo, score, awarded FROM forum_rank
        WHERE board = #{board} AND period_key = #{periodKey} AND user_id = #{userId}
        """)
    Map<String, Object> myRow(@Param("board") String board, @Param("periodKey") String periodKey,
                              @Param("userId") Long userId);

    /** 最近一期已结算的周期；周期键字典序可比（2026-11-08 / 2026-W45）。 */
    @Select("SELECT MAX(period_key) FROM forum_rank WHERE board = #{board}")
    String latestPeriod(@Param("board") String board);

    @Select("SELECT COUNT(*) FROM forum_rank WHERE board = #{board} AND period_key = #{periodKey}")
    int countRows(@Param("board") String board, @Param("periodKey") String periodKey);

    /**
     * 上榜通知。source_key 带周期与用户，靠 notification 的唯一键做幂等：
     * 重结算会把同一周期再跑一遍，通知不能跟着再发一遍。
     */
    @Insert("""
        INSERT IGNORE INTO notification(user_id, title, content, category, tone, source_key)
        VALUES(#{userId}, #{title}, #{content}, 'forum', 'blue', #{sourceKey})
        """)
    int notify(@Param("userId") Long userId, @Param("title") String title,
               @Param("content") String content, @Param("sourceKey") String sourceKey);
}