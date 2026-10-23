package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 圈子群聊专用数据访问层；不复用一对一 chat_message。 */
@Mapper
public interface CircleChatMapper {
    @Select("SELECT id,name,status FROM circle WHERE id=#{circleId}")
    Map<String, Object> circle(@Param("circleId") Long circleId);

    @Select("SELECT COUNT(*) FROM circle_member WHERE circle_id=#{circleId} AND user_id=#{userId}")
    long member(@Param("circleId") Long circleId, @Param("userId") Long userId);

    @Select("""
        <script>
        SELECT m.id, m.circle_id AS circleId, m.sender_id AS senderId,
               u.nickname AS senderName, u.avatar AS senderAvatar,
               m.type AS messageType, m.content, m.image,
               m.client_id AS clientId, m.created_at AS createdAt
        FROM circle_message m LEFT JOIN `user` u ON u.id=m.sender_id
        WHERE m.circle_id=#{circleId}
        <if test="beforeId != null">AND m.id &lt; #{beforeId}</if>
        <if test="afterId != null">AND m.id &gt; #{afterId}</if>
        ORDER BY m.id <choose><when test="afterId != null">ASC</when><otherwise>DESC</otherwise></choose>
        LIMIT #{limit}
        </script>
        """)
    List<Map<String, Object>> history(@Param("circleId") Long circleId, @Param("beforeId") Long beforeId,
                                      @Param("afterId") Long afterId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO circle_message(circle_id,sender_id,type,content,image,client_id)
        VALUES(#{circleId},#{senderId},#{type},#{content},#{image},#{clientId})
        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertMessage(Map<String, Object> row);

    @Select("""
        SELECT m.id, m.circle_id AS circleId, m.sender_id AS senderId,
               u.nickname AS senderName, u.avatar AS senderAvatar,
               m.type AS messageType, m.content, m.image,
               m.client_id AS clientId, m.created_at AS createdAt
        FROM circle_message m LEFT JOIN `user` u ON u.id=m.sender_id
        WHERE m.id=#{id}
        """)
    Map<String, Object> message(@Param("id") Long id);

    @Select("SELECT id FROM circle_message WHERE sender_id=#{senderId} AND client_id=#{clientId} LIMIT 1")
    Long messageIdByClient(@Param("senderId") Long senderId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(*) FROM circle_message WHERE circle_id=#{circleId} AND id=#{messageId}")
    long messageExists(@Param("circleId") Long circleId, @Param("messageId") Long messageId);

    @Update("""
        UPDATE circle
        SET last_message_at=(SELECT m.created_at FROM circle_message m WHERE m.circle_id=#{circleId} ORDER BY m.id DESC LIMIT 1),
            last_message=(SELECT CASE WHEN m.type='image' THEN '[图片]' ELSE LEFT(m.content,100) END
                          FROM circle_message m WHERE m.circle_id=#{circleId} ORDER BY m.id DESC LIMIT 1)
        WHERE id=#{circleId}
        """)
    int updateCircleSummary(@Param("circleId") Long circleId);

    @Insert("""
        INSERT INTO circle_read(circle_id,user_id,last_read_id)
        VALUES(#{circleId},#{userId},#{lastReadId})
        ON DUPLICATE KEY UPDATE last_read_id=GREATEST(last_read_id,VALUES(last_read_id))
        """)
    int markRead(@Param("circleId") Long circleId, @Param("userId") Long userId, @Param("lastReadId") Long lastReadId);

    @Select("""
        SELECT c.id AS circleId,
               COUNT(m.id) AS unreadCount
        FROM circle c
        LEFT JOIN circle_message m ON m.circle_id=c.id
          AND m.id > COALESCE((SELECT r.last_read_id FROM circle_read r
                               WHERE r.circle_id=c.id AND r.user_id=#{userId}),0)
        WHERE c.status=1 AND EXISTS (SELECT 1 FROM circle_member cm WHERE cm.circle_id=c.id AND cm.user_id=#{userId})
        GROUP BY c.id
        ORDER BY c.id
        """)
    List<Map<String, Object>> unread(@Param("userId") Long userId);
}
