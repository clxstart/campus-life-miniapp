package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * AI 陪聊：规则查询 + 校园数据取样 + 日志 + 配置。
 * 所有参数均绑定，不拼接来自请求的 SQL。
 */
@Mapper
public interface AiChatMapper {

    /** 所有启用中的规则，按 priority 降序（匹配逻辑在 service 里做） */
    @Select("""
        SELECT id, category, keywords, reply, quick_replies AS quickReplies, priority, status
        FROM ai_chat_rule WHERE status = 1
        ORDER BY priority DESC, id
        """)
    List<Map<String, Object>> activeRules();

    /** 用户所属学校（schoolId 为空时的兜底） */
    @Select("SELECT school_id FROM `user` WHERE id = #{userId}")
    Long userSchoolId(@Param("userId") Long userId);

    /** 本校启用中的店铺名（用于「吃什么」） */
    @Select("""
        <script>
        SELECT name FROM shop WHERE status = 1
        <if test="schoolId != null">AND (school_id = #{schoolId} OR school_id IS NULL)</if>
        ORDER BY order_count DESC, id
        LIMIT #{limit}
        </script>
        """)
    List<String> shopNames(@Param("schoolId") Long schoolId, @Param("limit") int limit);

    /** 本校在招岗位标题（用于「兼职」） */
    @Select("""
        <script>
        SELECT title FROM job WHERE 1 = 1
        <if test="schoolId != null">AND (school_id = #{schoolId} OR school_id IS NULL)</if>
        ORDER BY id DESC
        LIMIT #{limit}
        </script>
        """)
    List<String> jobTitles(@Param("schoolId") Long schoolId, @Param("limit") int limit);

    /** 本校启用中的活动标题（用于「有什么活动」） */
    @Select("""
        <script>
        SELECT title FROM campus_activity WHERE status = 1
        <if test="schoolId != null">AND (school_id = #{schoolId} OR school_id IS NULL)</if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<String> activityTitles(@Param("schoolId") Long schoolId, @Param("limit") int limit);

    // ================= 管理端：规则 CRUD =================

    /** 管理端规则列表：可按分类/关键词筛选（含已停用的） */
    @Select("""
        <script>
        SELECT id, category, keywords, reply, quick_replies AS quickReplies,
               priority, status, created_at AS createdAt
        FROM ai_chat_rule
        <where>
          <if test="category != null and category != ''">category = #{category} AND</if>
          <if test="keyword != null and keyword != ''">
            (keywords LIKE #{keyword} OR reply LIKE #{keyword}) AND
          </if>
          1 = 1
        </where>
        ORDER BY priority DESC, id
        </script>
        """)
    List<Map<String, Object>> rulesForAdmin(@Param("category") String category,
                                            @Param("keyword") String keyword);

    @Select("SELECT id, category, keywords FROM ai_chat_rule WHERE id = #{id}")
    Map<String, Object> ruleBrief(@Param("id") Long id);

    @Insert("""
        INSERT INTO ai_chat_rule(category, keywords, reply, quick_replies, priority, status)
        VALUES(#{category}, #{keywords}, #{reply}, #{quickReplies}, #{priority}, #{status})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRule(Map<String, Object> row);

    @Update("""
        UPDATE ai_chat_rule SET category = #{category}, keywords = #{keywords}, reply = #{reply},
               quick_replies = #{quickReplies}, priority = #{priority}, status = #{status}
        WHERE id = #{id}
        """)
    int updateRule(Map<String, Object> row);

    @Update("UPDATE ai_chat_rule SET status = #{status} WHERE id = #{id}")
    int setRuleStatus(@Param("id") Long id, @Param("status") int status);

    @Delete("DELETE FROM ai_chat_rule WHERE id = #{id}")
    int deleteRule(@Param("id") Long id);

    // ================= 对话日志 =================

    @Insert("""
        INSERT INTO ai_chat_log(user_id, school_id, message, matched, category, rule_id)
        VALUES(#{userId}, #{schoolId}, #{message}, #{matched}, #{category}, #{ruleId})
        """)
    int insertLog(Map<String, Object> row);

    /** 未命中的问题按频次排序（运营据此补规则） */
    @Select("""
        SELECT message, COUNT(*) AS count, MAX(created_at) AS lastAt
        FROM ai_chat_log
        WHERE matched = 0
        GROUP BY message
        ORDER BY count DESC, lastAt DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> unmatchedTop(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_chat_log")
    long totalChats();

    @Select("SELECT COUNT(*) FROM ai_chat_log WHERE matched = 1")
    long matchedChats();

    @Select("""
        SELECT category, COUNT(*) AS count
        FROM ai_chat_log
        WHERE category IS NOT NULL
        GROUP BY category
        ORDER BY count DESC
        """)
    List<Map<String, Object>> chatsByCategory();

    // ================= 话术配置 =================

    @Select("SELECT config_value FROM ai_chat_config WHERE config_key = #{key}")
    String configValue(@Param("key") String key);

    @Insert("""
        INSERT INTO ai_chat_config(config_key, config_value) VALUES(#{key}, #{value})
        ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)
        """)
    int upsertConfig(@Param("key") String key, @Param("value") String value);
}
