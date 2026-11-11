package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {
    @Select("SELECT word FROM sensitive_word WHERE enabled=1 ORDER BY id")
    List<String> enabledWords();

    @Select("""
        <script>
        SELECT * FROM (
          SELECT 'post' AS type, p.id, p.title AS title, p.content AS content, p.image AS image,
                 p.audit_status AS auditStatus, p.audit_reason AS auditReason, p.audit_at AS auditAt,
                 p.created_at AS createdAt, p.user_id AS userId, u.nickname AS userName,
                 p.school_id AS schoolId, s.name AS schoolName, p.status AS businessStatus
          FROM post p LEFT JOIN `user` u ON u.id=p.user_id LEFT JOIN school s ON s.id=p.school_id
          UNION ALL
          SELECT 'comment', c.id, '', c.content, NULL, c.audit_status, c.audit_reason, c.audit_at,
                 c.created_at, c.user_id, u.nickname, u.school_id, s.name, NULL
          FROM `comment` c LEFT JOIN `user` u ON u.id=c.user_id LEFT JOIN school s ON s.id=u.school_id
          UNION ALL
          SELECT 'goods', g.id, g.title, g.description, g.images, g.audit_status, g.audit_reason, g.audit_at,
                 g.created_at, g.user_id, u.nickname, g.school_id, s.name, g.status
          FROM goods g LEFT JOIN `user` u ON u.id=g.user_id LEFT JOIN school s ON s.id=g.school_id
          UNION ALL
          SELECT 'job', j.id, j.title, j.description, NULL, j.audit_status, j.audit_reason, j.audit_at,
                 j.created_at, j.user_id, u.nickname, j.school_id, s.name, j.status
          FROM job j LEFT JOIN `user` u ON u.id=j.user_id LEFT JOIN school s ON s.id=j.school_id
          UNION ALL
          SELECT 'chat', m.id, '', m.content, NULL, m.audit_status, m.audit_reason, m.audit_at,
                 m.created_at, m.sender_id, u.nickname, u.school_id, s.name, NULL
          FROM chat_message m LEFT JOIN `user` u ON u.id=m.sender_id LEFT JOIN school s ON s.id=u.school_id
          UNION ALL
          SELECT 'circle', c.id, c.name, c.description, c.cover, c.audit_status, c.audit_reason, c.audit_at,
                 c.created_at, NULL, NULL, c.school_id, s.name, c.status
          FROM circle c LEFT JOIN school s ON s.id=c.school_id
        ) a
        <where>
          <if test="type != null and type != ''">AND a.type=#{type}</if>
          <if test="status != null and status != ''">AND a.auditStatus=#{status}</if>
          <if test="schoolId != null">AND a.schoolId=#{schoolId}</if>
          <if test="keyword != null and keyword != ''">AND (a.title LIKE CONCAT('%',#{keyword},'%') OR a.content LIKE CONCAT('%',#{keyword},'%') OR a.auditReason LIKE CONCAT('%',#{keyword},'%'))</if>
          <if test="startDate != null and startDate != ''">AND a.createdAt &gt;= CONCAT(#{startDate},' 00:00:00')</if>
          <if test="endDate != null and endDate != ''">AND a.createdAt &lt; DATE_ADD(#{endDate}, INTERVAL 1 DAY)</if>
        </where>
        ORDER BY a.createdAt DESC, a.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> list(@Param("type") String type, @Param("status") String status,
        @Param("keyword") String keyword, @Param("startDate") String startDate,
        @Param("endDate") String endDate, @Param("schoolId") Long schoolId,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM (
          SELECT 'post' AS type, p.id, p.title, p.content, p.audit_status AS auditStatus, p.audit_reason AS auditReason, p.created_at AS createdAt, p.school_id AS schoolId FROM post p
          UNION ALL SELECT 'comment', c.id, '', c.content, c.audit_status, c.audit_reason, c.created_at, u.school_id FROM `comment` c LEFT JOIN `user` u ON u.id=c.user_id
          UNION ALL SELECT 'goods', g.id, g.title, g.description, g.audit_status, g.audit_reason, g.created_at, g.school_id FROM goods g
          UNION ALL SELECT 'job', j.id, j.title, j.description, j.audit_status, j.audit_reason, j.created_at, j.school_id FROM job j
          UNION ALL SELECT 'chat', m.id, '', m.content, m.audit_status, m.audit_reason, m.created_at, u.school_id FROM chat_message m LEFT JOIN `user` u ON u.id=m.sender_id
          UNION ALL SELECT 'circle', c.id, c.name, c.description, c.audit_status, c.audit_reason, c.created_at, c.school_id FROM circle c
        ) a
        <where>
          <if test="type != null and type != ''">AND a.type=#{type}</if>
          <if test="status != null and status != ''">AND a.auditStatus=#{status}</if>
          <if test="schoolId != null">AND a.schoolId=#{schoolId}</if>
          <if test="keyword != null and keyword != ''">AND (a.title LIKE CONCAT('%',#{keyword},'%') OR a.content LIKE CONCAT('%',#{keyword},'%') OR a.auditReason LIKE CONCAT('%',#{keyword},'%'))</if>
          <if test="startDate != null and startDate != ''">AND a.createdAt &gt;= CONCAT(#{startDate},' 00:00:00')</if>
          <if test="endDate != null and endDate != ''">AND a.createdAt &lt; DATE_ADD(#{endDate}, INTERVAL 1 DAY)</if>
        </where>
        </script>
        """)
    long count(@Param("type") String type, @Param("status") String status,
        @Param("keyword") String keyword, @Param("startDate") String startDate,
        @Param("endDate") String endDate, @Param("schoolId") Long schoolId);

    @Select("""
        SELECT
          (SELECT COUNT(*) FROM post WHERE audit_at >= CURDATE() AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM `comment` c JOIN `user` u ON u.id=c.user_id WHERE c.audit_at >= CURDATE() AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM goods WHERE audit_at >= CURDATE() AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM job WHERE audit_at >= CURDATE() AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM chat_message m JOIN `user` u ON u.id=m.sender_id WHERE m.audit_at >= CURDATE() AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM circle WHERE audit_at >= CURDATE() AND (#{schoolId} IS NULL OR school_id=#{schoolId})) AS today,
          (SELECT COUNT(*) FROM post WHERE audit_status='reviewing' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM `comment` c JOIN `user` u ON u.id=c.user_id WHERE c.audit_status='reviewing' AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM goods WHERE audit_status='reviewing' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM job WHERE audit_status='reviewing' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM chat_message m JOIN `user` u ON u.id=m.sender_id WHERE m.audit_status='reviewing' AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM circle WHERE audit_status='reviewing' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) AS reviewing,
          (SELECT COUNT(*) FROM post WHERE audit_status='rejected' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM `comment` c JOIN `user` u ON u.id=c.user_id WHERE c.audit_status='rejected' AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM goods WHERE audit_status='rejected' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM job WHERE audit_status='rejected' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM chat_message m JOIN `user` u ON u.id=m.sender_id WHERE m.audit_status='rejected' AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})) +
          (SELECT COUNT(*) FROM circle WHERE audit_status='rejected' AND (#{schoolId} IS NULL OR school_id=#{schoolId})) AS rejected,
          -- 发布入口被敏感词直接拦下、从未入库的内容，单独统计（后台展示「累计拦截」）
          (SELECT COUNT(*) FROM audit_log WHERE result='reject') AS blocked
        """)
    Map<String,Object> stats(@Param("schoolId") Long schoolId);

    @Select("SELECT p.id,p.user_id AS userId,p.title,p.content,p.image,p.audit_status AS auditStatus,p.audit_reason AS auditReason,p.audit_at AS auditAt,p.created_at AS createdAt,p.school_id AS schoolId FROM post p WHERE p.id=#{id} AND (#{schoolId} IS NULL OR p.school_id=#{schoolId})")
    Map<String,Object> post(@Param("id") Long id, @Param("schoolId") Long schoolId);
    @Select("SELECT c.id,c.user_id AS userId,c.post_id AS postId,c.content,c.audit_status AS auditStatus,c.audit_reason AS auditReason,c.audit_at AS auditAt,c.created_at AS createdAt,u.school_id AS schoolId FROM `comment` c JOIN `user` u ON u.id=c.user_id WHERE c.id=#{id} AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})")
    Map<String,Object> comment(@Param("id") Long id, @Param("schoolId") Long schoolId);
    @Select("SELECT g.id,g.user_id AS userId,g.title,g.description,g.image,g.images,g.audit_status AS auditStatus,g.audit_reason AS auditReason,g.audit_at AS auditAt,g.created_at AS createdAt,g.school_id AS schoolId FROM goods g WHERE g.id=#{id} AND (#{schoolId} IS NULL OR g.school_id=#{schoolId})")
    Map<String,Object> goods(@Param("id") Long id, @Param("schoolId") Long schoolId);
    @Select("SELECT j.id,j.user_id AS userId,j.title,j.description,j.audit_status AS auditStatus,j.audit_reason AS auditReason,j.audit_at AS auditAt,j.created_at AS createdAt,j.school_id AS schoolId FROM job j WHERE j.id=#{id} AND (#{schoolId} IS NULL OR j.school_id=#{schoolId})")
    Map<String,Object> job(@Param("id") Long id, @Param("schoolId") Long schoolId);
    @Select("SELECT m.id,m.sender_id AS userId,m.receiver_id AS receiverId,m.content,m.audit_status AS auditStatus,m.audit_reason AS auditReason,m.audit_at AS auditAt,m.created_at AS createdAt,u.school_id AS schoolId FROM chat_message m JOIN `user` u ON u.id=m.sender_id WHERE m.id=#{id} AND (#{schoolId} IS NULL OR u.school_id=#{schoolId})")
    Map<String,Object> chat(@Param("id") Long id, @Param("schoolId") Long schoolId);
    @Select("SELECT c.id,c.name,c.description,c.cover,c.audit_status AS auditStatus,c.audit_reason AS auditReason,c.audit_at AS auditAt,c.created_at AS createdAt,c.school_id AS schoolId FROM circle c WHERE c.id=#{id} AND (#{schoolId} IS NULL OR c.school_id=#{schoolId})")
    Map<String,Object> circle(@Param("id") Long id, @Param("schoolId") Long schoolId);

    @Select("SELECT id,word,category,enabled,created_at AS createdAt FROM sensitive_word WHERE (#{category} IS NULL OR #{category}='' OR category=#{category}) AND (#{keyword} IS NULL OR #{keyword}='' OR word LIKE CONCAT('%',#{keyword},'%')) ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> words(@Param("category") String category, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") int offset);
    @Select("SELECT COUNT(*) FROM sensitive_word WHERE (#{category} IS NULL OR #{category}='' OR category=#{category}) AND (#{keyword} IS NULL OR #{keyword}='' OR word LIKE CONCAT('%',#{keyword},'%'))")
    long countWords(@Param("category") String category, @Param("keyword") String keyword);
    @Select("SELECT COUNT(*) FROM sensitive_word WHERE enabled=1") long countEnabledWords();
    @Insert("INSERT IGNORE INTO sensitive_word(word,category,enabled) VALUES(#{word},#{category},1)")
    int insertWord(@Param("word") String word, @Param("category") String category);
    @Update("UPDATE sensitive_word SET word=#{word},category=#{category},enabled=#{enabled} WHERE id=#{id}")
    int updateWord(@Param("id") Long id, @Param("word") String word, @Param("category") String category, @Param("enabled") int enabled);
    @Delete("DELETE FROM sensitive_word WHERE id=#{id}") int deleteWord(@Param("id") Long id);

    @Insert("INSERT INTO audit_log(target_type,target_id,user_id,content_snippet,source,result,matched_words) VALUES(#{type},#{targetId},#{userId},#{snippet},#{source},#{result},#{words})")
    int log(@Param("type") String type, @Param("targetId") Long targetId, @Param("userId") Long userId,
        @Param("snippet") String snippet, @Param("source") String source,
        @Param("result") String result, @Param("words") String words);

    /**
     * 这条内容当初是不是走「先发后审」上线的？
     * 只有黄金免审发帖会写 source='fastlane' 的日志，用它区分「免审后被撤回」与普通违规。
     */
    @Select("SELECT COUNT(*) FROM audit_log WHERE target_type=#{type} AND target_id=#{targetId} AND source='fastlane'")
    int countFastlaneLog(@Param("type") String type, @Param("targetId") Long targetId);

    @Update("UPDATE post SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW(),status=#{visible} WHERE id=#{id}") int decidePost(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason, @Param("visible") int visible);
    @Update("UPDATE `comment` SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW() WHERE id=#{id}") int decideComment(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason);
    @Update("UPDATE post SET comment_count=comment_count+1 WHERE id=#{postId}") int incrementPostComment(@Param("postId") Long postId);
    @Update("UPDATE goods SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW(),status=#{visible} WHERE id=#{id}") int decideGoods(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason, @Param("visible") int visible);
    @Update("UPDATE job SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW(),status=IF(#{visible}=1,'招聘中','已下架') WHERE id=#{id}") int decideJob(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason, @Param("visible") int visible);
    @Update("UPDATE chat_message SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW() WHERE id=#{id}") int decideChat(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason);
    @Update("UPDATE circle SET audit_status=#{auditStatus},audit_reason=#{reason},audit_at=NOW(),status=#{visible} WHERE id=#{id}") int decideCircle(@Param("id") Long id, @Param("auditStatus") String auditStatus, @Param("reason") String reason, @Param("visible") int visible);

    @Update("UPDATE post SET audit_status=IF(audit_status='reviewing','reviewing','passed'),audit_reason=IF(audit_status='reviewing',audit_reason,NULL),audit_at=NOW(),status=IF(audit_status='reviewing',status,1) WHERE id=#{id}") int imagePassedPost(@Param("id") Long id);
    @Update("UPDATE goods SET audit_status=IF(audit_status='reviewing','reviewing','passed'),audit_reason=IF(audit_status='reviewing',audit_reason,NULL),audit_at=NOW(),status=IF(audit_status='reviewing',status,1) WHERE id=#{id}") int imagePassedGoods(@Param("id") Long id);
    @Update("UPDATE post SET audit_status='rejected',audit_reason=#{reason},audit_at=NOW(),status=0 WHERE id=#{id}") int imageRejectedPost(@Param("id") Long id, @Param("reason") String reason);
    @Update("UPDATE goods SET audit_status='rejected',audit_reason=#{reason},audit_at=NOW(),status=0 WHERE id=#{id}") int imageRejectedGoods(@Param("id") Long id, @Param("reason") String reason);
}
