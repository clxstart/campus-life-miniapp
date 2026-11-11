package com.campuslife.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 宿舍域的数据访问（全注解 SQL，无 XML，与仓库其他 Mapper 一致）。
 *
 * 三条纪律：
 *   1. 发分的 SQL 一行都不写在这里 —— 发分只有 {@link com.campuslife.service.PointsService} 一个口，
 *      宿舍域只负责「该不该发」和「业务号是什么」。
 *   2. 「一人一宿舍」靠 dorm_member.uk_dorm_user，「全员只发一次」靠 dorm_sign 的条件更新。
 *      服务层的先查后改只是友好提示，正确性一律压在数据库约束与条件 UPDATE 上。
 *   3. 时间参数（biz_date / from / to）都由 Java 按北京时间算好再传进来，
 *      不在这里调用 CURDATE()/NOW()，避免换台 UTC 服务器整域错位一天。
 */
@Mapper
public interface DormMapper {

    // ==================== 宿舍 ====================

    /** 新建宿舍；id 回写到入参 Map 的 "id"（调用方据此再插舍长）。 */
    @Insert("""
        INSERT INTO dorm(school_id, name, building, room, leader_id, invite_code, member_limit, status)
        VALUES(#{schoolId}, #{name}, #{building}, #{room}, #{leaderId}, #{inviteCode}, #{memberLimit}, 1)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDorm(Map<String, Object> row);

    @Select("""
        SELECT id, school_id AS schoolId, name, building, room, leader_id AS leaderId,
               invite_code AS inviteCode, member_limit AS memberLimit, status,
               created_at AS createdAt, dismissed_at AS dismissedAt, dismiss_reason AS dismissReason
        FROM dorm WHERE id = #{id}
        """)
    Map<String, Object> dormById(@Param("id") Long id);

    /** 按邀请码找宿舍；找不到返回 null（调用方统一报「邀请码无效」）。 */
    @Select("""
        SELECT id, school_id AS schoolId, name, member_limit AS memberLimit, status
        FROM dorm WHERE invite_code = #{code}
        """)
    Map<String, Object> dormByCode(@Param("code") String code);

    /**
     * 加入时先锁住宿舍行：「数人数 → 插入」必须串行，
     * 否则 8 人的宿舍能被第 9、第 10 个人同时挤进来。
     */
    @Select("SELECT id FROM dorm WHERE id = #{id} FOR UPDATE")
    Long lockDorm(@Param("id") Long id);

    /** 解散：只改 status=0，不物理删除，历史签到记录与审计都还在。 */
    @Update("""
        UPDATE dorm SET status = 0, dismissed_by = #{adminId}, dismissed_at = #{at}, dismiss_reason = #{reason}
        WHERE id = #{id} AND status = 1
        """)
    int dismiss(@Param("id") Long id, @Param("adminId") Long adminId,
                @Param("at") LocalDateTime at, @Param("reason") String reason);

    /** 单人宿舍的舍长退出：空壳宿舍必须一起收掉，否则邀请码会指向一个没有舍长的孤儿宿舍。 */
    @Update("""
        UPDATE dorm SET status = 0, dismissed_at = #{at}, dismiss_reason = '舍长退出，单人宿舍自动解散'
        WHERE id = #{id} AND status = 1
        """)
    int closeLoneDorm(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 建宿舍时带上用户所在学校；用户没绑学校时返回 null（允许建，但不参与跨校隔离）。 */
    @Select("SELECT school_id FROM user WHERE id = #{userId}")
    Long userSchoolId(@Param("userId") Long userId);

    // ==================== 成员 ====================

    @Insert("INSERT INTO dorm_member(dorm_id, user_id, role) VALUES(#{dormId}, #{userId}, #{role})")
    int addMember(@Param("dormId") Long dormId, @Param("userId") Long userId, @Param("role") String role);

    @Select("SELECT dorm_id FROM dorm_member WHERE user_id = #{userId}")
    Long dormIdOfUser(@Param("userId") Long userId);

    @Select("SELECT dorm_id AS dormId, role FROM dorm_member WHERE user_id = #{userId}")
    Map<String, Object> memberOf(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM dorm_member WHERE dorm_id = #{dormId}")
    int memberCount(@Param("dormId") Long dormId);

    @Select("SELECT user_id FROM dorm_member WHERE dorm_id = #{dormId} ORDER BY id")
    List<Long> memberIds(@Param("dormId") Long dormId);

    @Delete("DELETE FROM dorm_member WHERE user_id = #{userId}")
    int removeMember(@Param("userId") Long userId);

    /** 解散时解除全部舍友关系（历史 dorm_sign 保留）。 */
    @Delete("DELETE FROM dorm_member WHERE dorm_id = #{dormId}")
    int removeAllMembers(@Param("dormId") Long dormId);

    /**
     * 宿舍成员 + 今日是否已签到（签到事实以 sign_roll 为准，不用宿舍表另存一份）。
     * 批量传 dormIds，避免管理端列表 N+1。
     */
    @Select("""
        <script>
        SELECT m.dorm_id AS dormId, m.user_id AS userId, u.nickname AS nickname, u.avatar AS avatar,
               m.role AS role, m.joined_at AS joinedAt,
               (CASE WHEN s.user_id IS NULL THEN 0 ELSE 1 END) AS signedToday
        FROM dorm_member m
        LEFT JOIN user u ON u.id = m.user_id
        LEFT JOIN sign_roll s ON s.user_id = m.user_id AND s.biz_date = #{bizDate}
        WHERE m.dorm_id IN
        <foreach collection="dormIds" item="dormId" open="(" separator="," close=")">#{dormId}</foreach>
        ORDER BY m.id
        </script>
        """)
    List<Map<String, Object>> members(@Param("dormIds") List<Long> dormIds, @Param("bizDate") LocalDate bizDate);

    // ==================== 当日全员签到 ====================

    /**
     * 建当日行并固化成员数。
     * ON DUPLICATE KEY UPDATE dorm_id = dorm_id 是"什么也不改"：成员数只在第一次写入时生效，
     * 中途有人退出/加入都不会改变当日判定基数（测试手册第 11 条）。
     */
    @Insert("""
        INSERT INTO dorm_sign(dorm_id, biz_date, signed_count, member_count, all_done)
        VALUES(#{dormId}, #{bizDate}, 0, #{memberCount}, 0)
        ON DUPLICATE KEY UPDATE dorm_id = dorm_id
        """)
    int ensureSignRow(@Param("dormId") Long dormId, @Param("bizDate") LocalDate bizDate,
                      @Param("memberCount") int memberCount);

    /** 当日宿舍里已签到的成员数（按成员集合重算，天然幂等，不会因为重复通知而多加一个）。 */
    @Select("""
        SELECT COUNT(*) FROM dorm_member m
        JOIN sign_roll s ON s.user_id = m.user_id AND s.biz_date = #{bizDate}
        WHERE m.dorm_id = #{dormId}
        """)
    int countSignedMembers(@Param("dormId") Long dormId, @Param("bizDate") LocalDate bizDate);

    @Update("""
        UPDATE dorm_sign SET signed_count = #{signedCount}
        WHERE dorm_id = #{dormId} AND biz_date = #{bizDate}
        """)
    int updateSignedCount(@Param("dormId") Long dormId, @Param("bizDate") LocalDate bizDate,
                          @Param("signedCount") int signedCount);

    /**
     * 只发一次的关键：条件更新。
     * 并发下只有一个请求能把它从 0 改成 1（InnoDB 当前读 + 行锁），其余影响 0 行；
     * 服务层只在返回 1 的那一刻对成员发分，所以"晚到一次全员"永远拿不到第二次奖励。
     */
    @Update("""
        UPDATE dorm_sign SET all_done = 1
        WHERE dorm_id = #{dormId} AND biz_date = #{bizDate}
          AND all_done = 0 AND signed_count >= member_count
        """)
    int claimAllDone(@Param("dormId") Long dormId, @Param("bizDate") LocalDate bizDate);

    @Select("""
        SELECT dorm_id AS dormId, biz_date AS bizDate, signed_count AS signedCount,
               member_count AS memberCount, all_done AS allDone
        FROM dorm_sign WHERE dorm_id = #{dormId} AND biz_date = #{bizDate}
        """)
    Map<String, Object> signOf(@Param("dormId") Long dormId, @Param("bizDate") LocalDate bizDate);

    // ==================== 周榜 ====================

    /**
     * 本周各宿舍的分数 = 成员本周所有任务流水的 p 之和。
     * 口径统一（积分总和覆盖所有行为），且复用已有 task_log，不额外埋点。
     * 排序即结果：分数高的在前，并列时 dorm_id 小者在前（设计稿「五、周榜结算」）。
     */
    @Select("""
        SELECT d.id AS dormId, d.name AS name, COALESCE(SUM(t.p), 0) AS score
        FROM dorm d
        LEFT JOIN dorm_member m ON m.dorm_id = d.id
        LEFT JOIN task_log t ON t.user_id = m.user_id AND t.biz_date BETWEEN #{from} AND #{to}
        WHERE d.status = 1
        GROUP BY d.id, d.name
        ORDER BY score DESC, d.id ASC
        """)
    List<Map<String, Object>> weekScores(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // ==================== 管理端列表 ====================

    @Select("""
        <script>
        SELECT d.id AS id, d.school_id AS schoolId, sch.name AS schoolName, d.name AS name,
               d.building AS building, d.room AS room, d.leader_id AS leaderId,
               d.invite_code AS inviteCode, d.member_limit AS memberLimit, d.status AS status,
               d.created_at AS createdAt, d.dismissed_at AS dismissedAt, d.dismiss_reason AS dismissReason,
               (SELECT COUNT(*) FROM dorm_member m WHERE m.dorm_id = d.id) AS memberCount,
               (SELECT COALESCE(SUM(t.p), 0) FROM dorm_member m2
                  JOIN task_log t ON t.user_id = m2.user_id AND t.biz_date BETWEEN #{from} AND #{to}
                 WHERE m2.dorm_id = d.id) AS weekScore
        FROM dorm d
        LEFT JOIN school sch ON sch.id = d.school_id
        WHERE 1 = 1
        <if test="schoolId != null"> AND d.school_id = #{schoolId}</if>
        <if test="status != null"> AND d.status = #{status}</if>
        <if test="keyword != null and keyword != ''"> AND d.name LIKE CONCAT('%', #{keyword}, '%')</if>
        ORDER BY d.id DESC
        LIMIT #{size} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> adminList(@Param("schoolId") Long schoolId, @Param("status") Integer status,
                                        @Param("keyword") String keyword,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("size") int size, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM dorm d WHERE 1 = 1
        <if test="schoolId != null"> AND d.school_id = #{schoolId}</if>
        <if test="status != null"> AND d.status = #{status}</if>
        <if test="keyword != null and keyword != ''"> AND d.name LIKE CONCAT('%', #{keyword}, '%')</if>
        </script>
        """)
    long countForAdmin(@Param("schoolId") Long schoolId, @Param("status") Integer status,
                       @Param("keyword") String keyword);

    // ==================== 奶茶券（外部依赖，必须容错）====================

    /** reward_item 是「新人礼包与积分兑换」功能的表，可能尚未创建；调用方必须自己兜住异常。 */
    @Select("""
        SELECT item_key AS itemKey, coupon_title AS couponTitle, coupon_amount AS couponAmount,
               coupon_expire AS couponExpire, expire_days AS expireDays, stock AS stock
        FROM reward_item WHERE item_key = #{itemKey} AND enabled = 1 AND stock > 0
        """)
    Map<String, Object> rewardItem(@Param("itemKey") String itemKey);

    /** 库存条件更新：并发下不会扣成负数，也不会把同一件商品超发出去。 */
    @Update("UPDATE reward_item SET stock = stock - 1 WHERE item_key = #{itemKey} AND stock > 0")
    int takeRewardStock(@Param("itemKey") String itemKey);

    /** 券复用现有 coupon 表：用户已经在"优惠与权益"页看券，不再造第二套券列表。 */
    @Insert("""
        INSERT INTO coupon(user_id, title, amount, expire_at, status)
        VALUES(#{userId}, #{title}, #{amount}, #{expireAt}, 1)
        """)
    int grantCoupon(@Param("userId") Long userId, @Param("title") String title,
                    @Param("amount") String amount, @Param("expireAt") String expireAt);

    /**
     * 解散通知：成员关系解除后，用户只剩「未加入」态，页面无法再知道宿舍是被解散的。
     * 用通知留一份可追溯的告知；sourceKey 唯一，重复解散不会重复通知。
     */
    @Insert("""
        INSERT IGNORE INTO notification(user_id, title, content, category, tone, source_key)
        VALUES(#{userId}, '你的宿舍已解散', #{content}, 'dorm', 'blue', #{sourceKey})
        """)
    int notifyDismissed(@Param("userId") Long userId, @Param("content") String content,
                        @Param("sourceKey") String sourceKey);
}