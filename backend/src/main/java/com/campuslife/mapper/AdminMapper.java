package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.AdminUser;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper public interface AdminMapper extends BaseMapper<AdminUser> {

    @Select("""
        SELECT id, username, password_hash AS passwordHash, real_name AS realName, role,
               school_id AS schoolId, shop_id AS shopId, status, fail_count AS failCount, locked_until AS lockedUntil,
               last_login_at AS lastLoginAt, created_at AS createdAt
        FROM admin_user WHERE username = #{username} LIMIT 1
        """)
    AdminUser findByUsername(@Param("username") String username);

    // 登录失败累计；达到阈值时写入锁定截止时间
    @Update("UPDATE admin_user SET fail_count = fail_count + 1, locked_until = #{lockedUntil} WHERE id = #{id}")
    int bumpFail(@Param("id") Long id, @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("UPDATE admin_user SET fail_count = 0, locked_until = NULL, last_login_at = NOW() WHERE id = #{id}")
    int markLogin(@Param("id") Long id);

    @Insert("""
        INSERT INTO admin_log(admin_id, admin_name, action, target_type, target_id, detail, ip)
        VALUES(#{adminId}, #{adminName}, #{action}, #{targetType}, #{targetId}, #{detail}, #{ip})
        """)
    int insertLog(Map<String,Object> row);

    @Select("""
        <script>
        SELECT l.id, l.admin_id AS adminId, l.admin_name AS adminName, l.action,
               l.target_type AS targetType, l.target_id AS targetId, l.detail, l.ip,
               l.created_at AS createdAt
        FROM admin_log l
        <where>
          <if test="adminId != null">l.admin_id = #{adminId} AND</if>
          1 = 1
        </where>
        ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> logs(@Param("adminId") Long adminId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM admin_log l
        <where>
          <if test="adminId != null">l.admin_id = #{adminId} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countLogs(@Param("adminId") Long adminId);

    @Select("""
        SELECT id, username, real_name AS realName, role, school_id AS schoolId, shop_id AS shopId, status,
               last_login_at AS lastLoginAt, created_at AS createdAt
        FROM admin_user ORDER BY role, id
        """)
    List<Map<String,Object>> admins();

    @Insert("""
        INSERT INTO admin_user(username, password_hash, real_name, role, school_id)
        VALUES(#{username}, #{passwordHash}, #{realName}, #{role}, #{schoolId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAdmin(Map<String,Object> row);

    @Update("UPDATE admin_user SET status = #{status} WHERE id = #{id}")
    int setAdminStatus(@Param("id") Long id, @Param("status") int status);

    @Select("SELECT id FROM school WHERE id = #{id} AND status = 1")
    Long activeSchool(@Param("id") Long id);

    @Select("SELECT id, role, school_id AS schoolId, status FROM admin_user WHERE id = #{id}")
    Map<String,Object> accountBrief(@Param("id") Long id);

    @Select("""
        SELECT a.id, a.username, a.real_name AS realName, a.school_id AS schoolId,
               s.name AS schoolName, a.status, a.phone, a.rider_status AS riderStatus,
               (SELECT COUNT(*) FROM user_order o WHERE o.rider_id=a.id AND o.status='completed'
                 AND o.delivered_at >= CURDATE() AND o.delivered_at < CURDATE() + INTERVAL 1 DAY) AS todayOrders
        FROM admin_user a LEFT JOIN school s ON s.id=a.school_id
        WHERE a.role='rider' AND (#{schoolId} IS NULL OR a.school_id=#{schoolId})
        ORDER BY a.id DESC
        """)
    List<Map<String,Object>> riders(@Param("schoolId") Long schoolId);

    @Insert("INSERT INTO admin_user(username,password_hash,real_name,role,school_id,phone) VALUES(#{username},#{passwordHash},#{realName},'rider',#{schoolId},#{phone})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRider(Map<String,Object> row);

    @Update("UPDATE admin_user SET status=#{status} WHERE id=#{id} AND role='rider'")
    int setRiderStatus(@Param("id") Long id, @Param("status") int status);

    @Update("UPDATE admin_user SET password_hash=#{passwordHash},fail_count=0,locked_until=NULL WHERE id=#{id} AND role='rider'")
    int resetRiderPassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);
}
