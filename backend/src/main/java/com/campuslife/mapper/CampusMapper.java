package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 消息和教学数据的参数全部绑定，不拼接来自请求的 SQL。
@Mapper
public interface CampusMapper {
    // 小程序公开学校列表：只返回启用中的学校，字段保持最小
    // （不带用户数/圈子数等运营数据，那些只给管理端看）
    @Select("""
        SELECT id, name, city, logo, period_config AS periodConfig
        FROM school WHERE status = 1 ORDER BY id
        """)
    List<Map<String,Object>> publicSchools();
    // 小程序选地点：按学校拉启用中的地点
    @Select("""
        SELECT id, name, sort FROM school_location
        WHERE school_id = #{schoolId} AND status = 1
        ORDER BY sort, id
        """)
    List<Map<String,Object>> publicSchoolLocations(@Param("schoolId") Long schoolId);
    @Select("""
        SELECT u.id, u.nickname AS name, u.avatar,
               m.type, m.content AS preview, m.created_at AS createdAt,
               COALESCE(p.muted,0) AS muted,
               (SELECT COUNT(*) FROM chat_message z WHERE z.sender_id=u.id AND z.receiver_id=#{userId}
                AND z.read_at IS NULL AND z.id>COALESCE(p.cleared_before,0)) AS unread
        FROM `user` u
        LEFT JOIN chat_preference p ON p.user_id=#{userId} AND p.peer_id=u.id
        JOIN chat_message m ON m.id=(SELECT MAX(x.id) FROM chat_message x
          WHERE ((x.sender_id=#{userId} AND x.receiver_id=u.id) OR (x.sender_id=u.id AND x.receiver_id=#{userId}))
          AND x.id>COALESCE(p.cleared_before,0))
        WHERE u.id<>#{userId} ORDER BY m.id DESC
        """)
    List<Map<String,Object>> conversations(@Param("userId") Long userId);

    @Select("""
        SELECT cleared_before AS clearedBefore, muted FROM chat_preference WHERE user_id=#{userId} AND peer_id=#{peerId}
        """)
    Map<String,Object> preference(@Param("userId") Long userId, @Param("peerId") Long peerId);

    @Select("""
        <script>
        SELECT id, sender_id AS senderId, receiver_id AS receiverId, type, content, image, created_at AS createdAt
        FROM chat_message
        WHERE ((sender_id=#{userId} AND receiver_id=#{peerId}) OR (sender_id=#{peerId} AND receiver_id=#{userId}))
        AND id>#{clearedBefore}
        <if test="beforeId != null">AND id &lt; #{beforeId}</if>
        ORDER BY id DESC LIMIT #{limit}
        </script>
        """)
    List<Map<String,Object>> messages(@Param("userId") Long userId, @Param("peerId") Long peerId,
        @Param("clearedBefore") long clearedBefore, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO chat_message(sender_id,receiver_id,type,content,client_id)
        VALUES(#{senderId},#{receiverId},'text',#{content},#{clientId})
        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int send(Map<String,Object> params);

    @Select("SELECT id,sender_id AS senderId,receiver_id AS receiverId,type,content,image,created_at AS createdAt FROM chat_message WHERE id=#{id}")
    Map<String,Object> message(@Param("id") Long id);

    @Update("UPDATE chat_message SET read_at=CURRENT_TIMESTAMP WHERE receiver_id=#{userId} AND sender_id=#{peerId} AND id<=#{upToId} AND read_at IS NULL")
    int readChat(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("upToId") Long upToId);

    @Update("UPDATE chat_message SET read_at=CURRENT_TIMESTAMP WHERE receiver_id=#{userId} AND read_at IS NULL")
    int readAllChats(@Param("userId") Long userId);

    @Select("SELECT COALESCE(MAX(id),0) FROM chat_message WHERE (sender_id=#{userId} AND receiver_id=#{peerId}) OR (sender_id=#{peerId} AND receiver_id=#{userId})")
    long latestId(@Param("userId") Long userId,@Param("peerId") Long peerId);

    @Insert("""
        INSERT INTO chat_preference(user_id,peer_id,cleared_before) VALUES(#{userId},#{peerId},#{id})
        ON DUPLICATE KEY UPDATE cleared_before=GREATEST(cleared_before,VALUES(cleared_before))
        """)
    int clear(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("id") long id);

    @Insert("""
        INSERT INTO chat_preference(user_id,peer_id,muted) VALUES(#{userId},#{peerId},#{muted})
        ON DUPLICATE KEY UPDATE muted=VALUES(muted)
        """)
    int mute(@Param("userId") Long userId,@Param("peerId") Long peerId,@Param("muted") boolean muted);

    @Select("SELECT id,title,content,tone,category,post_id AS postId,created_at AS createdAt,read_at AS readAt FROM notification WHERE user_id=#{userId} ORDER BY id DESC LIMIT 100")
    List<Map<String,Object>> notifications(@Param("userId") Long userId);

    @Update("UPDATE notification SET read_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND read_at IS NULL")
    int readNotifications(@Param("userId") Long userId);

    @Insert("INSERT INTO notification(user_id,title,content,category,tone,post_id) VALUES(#{userId},#{title},#{content},#{category},'blue',#{postId})")
    int notifyUser(@Param("userId") Long userId,@Param("title") String title,@Param("content") String content,@Param("category") String category,@Param("postId") Long postId);

    @Select("SELECT id,term_label AS termLabel,start_date AS termStart,end_date AS termEnd,midterm_start AS midtermStart,midterm_end AS midtermEnd,final_start AS finalStart FROM academic_term WHERE school=#{school} AND active=1 ORDER BY start_date DESC LIMIT 1")
    Map<String,Object> term(@Param("school") String school);

    @Select("SELECT id,name,room,day,period,theme,week_start AS weekStart,week_end AS weekEnd FROM course_schedule WHERE user_id=#{userId} AND term_id=#{termId} AND week_start<=#{week} AND week_end>=#{week} ORDER BY day,period,id")
    List<Map<String,Object>> courses(@Param("userId") Long userId,@Param("termId") Long termId,@Param("week") int week);

    @Select("SELECT id,title,start_date AS start,end_date AS end,note FROM calendar_event WHERE term_id=#{termId} ORDER BY start_date,id")
    List<Map<String,Object>> events(@Param("termId") Long termId);

    // ---- 课表导入（管理员）：对接教务系统的替代方案 ----
    // 各校教务系统无公开接口，故提供批量导入入口；将来有真实接口时替换数据来源即可。
    @Select("SELECT id FROM academic_term WHERE school=#{school} AND active=1 ORDER BY start_date DESC LIMIT 1")
    Long activeTermId(@Param("school") String school);

    @Insert("""
        INSERT INTO course_schedule(term_id,user_id,name,room,day,period,theme,week_start,week_end)
        VALUES(#{termId},#{userId},#{name},#{room},#{day},#{period},#{theme},#{weekStart},#{weekEnd})
        ON DUPLICATE KEY UPDATE room=VALUES(room),theme=VALUES(theme),week_start=VALUES(week_start),week_end=VALUES(week_end)
        """)
    int upsertCourse(Map<String,Object> row);

    @Delete("DELETE FROM course_schedule WHERE term_id=#{termId} AND user_id=#{userId}")
    int clearCourses(@Param("termId") Long termId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM course_schedule WHERE term_id=#{termId} AND user_id=#{userId}")
    int countCourses(@Param("termId") Long termId, @Param("userId") Long userId);

    // ---- 学生自建课表：增删自己的课程 ----
    @Select("SELECT id, term_id AS termId, user_id AS userId, name, room, day, period, theme, week_start AS weekStart, week_end AS weekEnd FROM course_schedule WHERE id=#{id}")
    Map<String,Object> courseById(@Param("id") Long id);

    // 只能删除自己的课程（user_id 条件兜底，防止越权删别人的）
    @Delete("DELETE FROM course_schedule WHERE id=#{id} AND user_id=#{userId}")
    int deleteUserCourse(@Param("id") Long id, @Param("userId") Long userId);

    // upsert 后按唯一键取回最终行（ON DUPLICATE KEY UPDATE 路径不自增 id）
    @Select("SELECT id, name, room, day, period, theme, week_start AS weekStart, week_end AS weekEnd FROM course_schedule WHERE term_id=#{termId} AND user_id=#{userId} AND day=#{day} AND period=#{period} AND name=#{name} LIMIT 1")
    Map<String,Object> courseByKey(@Param("termId") Long termId, @Param("userId") Long userId,
        @Param("day") int day, @Param("period") int period, @Param("name") String name);
}
