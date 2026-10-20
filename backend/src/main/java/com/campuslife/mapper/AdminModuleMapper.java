package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// 管理端各模块的数据访问。所有查询按 schoolId 做数据隔离（null 表示超管看全部）。
@Mapper public interface AdminModuleMapper {

    // ---- 看板 ----
    // 用 (#{schoolId} IS NULL OR x.school_id = #{schoolId}) 做隔离：
    // 比在子查询里嵌 <where> 更可靠（后者在 SELECT 列中会生成异常 SQL）。
    @Select("""
        SELECT
          (SELECT COUNT(*) FROM `user` u WHERE (#{schoolId} IS NULL OR u.school_id = #{schoolId})) AS users,
          (SELECT COUNT(*) FROM post p WHERE p.status = 1 AND (#{schoolId} IS NULL OR p.school_id = #{schoolId})) AS posts,
          (SELECT COUNT(*) FROM circle c WHERE c.status = 1 AND (#{schoolId} IS NULL OR c.school_id = #{schoolId})) AS circles,
          (SELECT COUNT(*) FROM goods g WHERE g.status = 1 AND (#{schoolId} IS NULL OR g.school_id = #{schoolId})) AS goods,
          (SELECT COUNT(*) FROM job j WHERE (#{schoolId} IS NULL OR j.school_id = #{schoolId})) AS jobs,
          (SELECT COUNT(*) FROM campus_activity a WHERE a.status = 1 AND (#{schoolId} IS NULL OR a.school_id = #{schoolId})) AS activities,
          (SELECT COUNT(*) FROM comment) AS comments,
          (SELECT COUNT(*) FROM user_order) AS orders
        """)
    Map<String,Object> dashboard(@Param("schoolId") Long schoolId);

    // 近 7 天每日新增帖子：折线图数据
    @Select("""
        <script>
        SELECT DATE(p.created_at) AS day, COUNT(*) AS total
        FROM post p
        WHERE p.status = 1 AND p.created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)
        <if test="schoolId != null">AND p.school_id = #{schoolId}</if>
        GROUP BY DATE(p.created_at) ORDER BY day
        </script>
        """)
    List<Map<String,Object>> postTrend(@Param("schoolId") Long schoolId);

    // ---- 学校 ----
    @Select("""
        SELECT s.id, s.name, s.city, s.logo, s.period_config AS periodConfig, s.status, s.created_at AS createdAt,
               (SELECT COUNT(*) FROM `user` u WHERE u.school_id = s.id) AS userCount,
               (SELECT COUNT(*) FROM circle c WHERE c.school_id = s.id AND c.status = 1) AS circleCount
        FROM school s ORDER BY s.id
        """)
    List<Map<String,Object>> schools();

    // ---- 每校自定义地点 ----
    @Select("""
        SELECT id, school_id AS schoolId, name, sort, status, created_at AS createdAt
        FROM school_location WHERE school_id = #{schoolId}
        ORDER BY sort, id
        """)
    List<Map<String,Object>> schoolLocations(@Param("schoolId") Long schoolId);

    // 公开接口：只给启用中的，字段保持最小
    @Select("""
        SELECT id, name, sort FROM school_location
        WHERE school_id = #{schoolId} AND status = 1
        ORDER BY sort, id
        """)
    List<Map<String,Object>> publicSchoolLocations(@Param("schoolId") Long schoolId);

    @Select("SELECT COUNT(*) FROM school_location WHERE school_id = #{schoolId} AND name = #{name} AND id <> #{excludeId}")
    long locationNameTaken(@Param("schoolId") Long schoolId, @Param("name") String name, @Param("excludeId") Long excludeId);

    @Insert("""
        INSERT INTO school_location(school_id, name, sort, status)
        VALUES(#{schoolId}, #{name}, #{sort}, #{status})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertSchoolLocation(Map<String,Object> row);

    @Update("""
        UPDATE school_location SET name = #{name}, sort = #{sort}, status = #{status}
        WHERE id = #{id} AND school_id = #{schoolId}
        """)
    int updateSchoolLocation(Map<String,Object> row);

    @Delete("DELETE FROM school_location WHERE id = #{id} AND school_id = #{schoolId}")
    int deleteSchoolLocation(@Param("id") Long id, @Param("schoolId") Long schoolId);

    @Select("SELECT school_id AS schoolId FROM school_location WHERE id = #{id}")
    Map<String,Object> schoolLocationBrief(@Param("id") Long id);

    @Insert("""
        INSERT INTO school(name, city, logo, period_config) VALUES(#{name}, #{city}, #{logo}, #{periodConfig})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertSchool(Map<String,Object> row);

    @Update("""
        UPDATE school SET name = #{name}, city = #{city}, logo = #{logo},
               period_config = #{periodConfig}, status = #{status} WHERE id = #{id}
        """)
    int updateSchool(Map<String,Object> row);

    // ---- 用户 ----
    @Select("""
        <script>
        SELECT u.id, u.nickname, u.avatar, u.school, u.school_id AS schoolId, u.created_at AS createdAt,
               (SELECT COUNT(*) FROM post p WHERE p.user_id = u.id AND p.status = 1) AS postCount
        FROM `user` u
        <where>
          <if test="schoolId != null">u.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">(u.nickname LIKE #{keyword} OR u.school LIKE #{keyword}) AND</if>
          1 = 1
        </where>
        ORDER BY u.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> users(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM `user` u
        <where>
          <if test="schoolId != null">u.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">(u.nickname LIKE #{keyword} OR u.school LIKE #{keyword}) AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countUsers(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    // ---- 内容 ----
    @Select("""
        <script>
        SELECT p.id, p.title, p.content, p.tag, p.image, p.status, p.like_count AS likeCount,
               p.comment_count AS commentCount, p.view_count AS viewCount, p.created_at AS createdAt,
               p.school_id AS schoolId, u.nickname AS authorName, u.id AS authorId,
               s.name AS schoolName
        FROM post p LEFT JOIN `user` u ON u.id = p.user_id
        LEFT JOIN school s ON s.id = p.school_id
        <where>
          <if test="schoolId != null">p.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">(p.title LIKE #{keyword} OR p.content LIKE #{keyword}) AND</if>
          <if test="onlyOffline">p.status = 0 AND</if>
          1 = 1
        </where>
        ORDER BY p.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> posts(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
        @Param("onlyOffline") boolean onlyOffline, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM post p
        <where>
          <if test="schoolId != null">p.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">(p.title LIKE #{keyword} OR p.content LIKE #{keyword}) AND</if>
          <if test="onlyOffline">p.status = 0 AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countPosts(@Param("schoolId") Long schoolId, @Param("keyword") String keyword, @Param("onlyOffline") boolean onlyOffline);

    @Update("UPDATE post SET status = #{status} WHERE id = #{id}")
    int setPostStatus(@Param("id") Long id, @Param("status") int status);

    @Delete("DELETE FROM post WHERE id = #{id}")
    int deletePost(@Param("id") Long id);

    @Select("SELECT id, title, user_id AS userId, school_id AS schoolId FROM post WHERE id = #{id}")
    Map<String,Object> postBrief(@Param("id") Long id);

    // ---- 圈子 ----
    // 分页：limit/offset 由 service 算好传入（limit=null 表示不分页，供内部全量调用）
    @Select("""
        <script>
        SELECT c.id, c.name, c.slug, c.category, c.description, c.cover, c.theme,
               c.member_count AS memberCount, c.post_count AS postCount, c.hot, c.sort, c.status,
               c.school_id AS schoolId, s.name AS schoolName
        FROM circle c LEFT JOIN school s ON s.id = c.school_id
        <where>
          <if test="schoolId != null">c.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">c.name LIKE #{keyword} AND</if>
          1 = 1
        </where>
        ORDER BY c.sort, c.id
        <if test="limit != null">LIMIT #{limit} OFFSET #{offset}</if>
        </script>
        """)
    List<Map<String,Object>> circles(@Param("schoolId") Long schoolId,
                                     @Param("keyword") String keyword,
                                     @Param("limit") Integer limit,
                                     @Param("offset") Integer offset);

    // 过滤条件必须与 circles 完全一致，否则 total 和实际翻页条数会对不上
    @Select("""
        <script>
        SELECT COUNT(*) FROM circle c
        <where>
          <if test="schoolId != null">c.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">c.name LIKE #{keyword} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countCircles(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    @Insert("""
        INSERT INTO circle(name, slug, category, description, cover, theme, hot, sort, school_id)
        VALUES(#{name}, #{slug}, #{category}, #{description}, #{cover}, #{theme}, #{hot}, #{sort}, #{schoolId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertCircle(Map<String,Object> row);

    @Update("""
        UPDATE circle SET name = #{name}, category = #{category}, description = #{description},
               cover = #{cover}, theme = #{theme}, hot = #{hot}, sort = #{sort}, status = #{status}
        WHERE id = #{id}
        """)
    int updateCircle(Map<String,Object> row);

    @Select("SELECT COUNT(*) FROM circle WHERE slug = #{slug} AND id <> #{excludeId}")
    long slugTaken(@Param("slug") String slug, @Param("excludeId") Long excludeId);

    // ---- 学期与校历 ----
    @Select("""
        <script>
        SELECT t.id, t.school, t.school_id AS schoolId, t.term_label AS termLabel,
               t.start_date AS startDate, t.end_date AS endDate,
               t.midterm_start AS midtermStart, t.midterm_end AS midtermEnd,
               t.final_start AS finalStart, t.active
        FROM academic_term t
        <where><if test="schoolId != null">t.school_id = #{schoolId}</if></where>
        ORDER BY t.start_date DESC, t.id DESC
        </script>
        """)
    List<Map<String,Object>> terms(@Param("schoolId") Long schoolId);

    @Insert("""
        INSERT INTO academic_term(school, school_id, term_label, start_date, end_date, midterm_start, midterm_end, final_start, active)
        VALUES(#{school}, #{schoolId}, #{termLabel}, #{startDate}, #{endDate}, #{midtermStart}, #{midtermEnd}, #{finalStart}, #{active})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertTerm(Map<String,Object> row);

    @Update("""
        UPDATE academic_term SET term_label = #{termLabel}, start_date = #{startDate}, end_date = #{endDate},
               midterm_start = #{midtermStart}, midterm_end = #{midtermEnd}, final_start = #{finalStart},
               active = #{active} WHERE id = #{id}
        """)
    int updateTerm(Map<String,Object> row);

    @Select("""
        <script>
        SELECT e.id, e.term_id AS termId, e.title, e.start_date AS startDate, e.end_date AS endDate, e.note,
               t.term_label AS termLabel, t.school_id AS schoolId
        FROM calendar_event e JOIN academic_term t ON t.id = e.term_id
        <where><if test="schoolId != null">t.school_id = #{schoolId}</if></where>
        ORDER BY e.start_date, e.id
        </script>
        """)
    List<Map<String,Object>> calendarEvents(@Param("schoolId") Long schoolId);

    @Insert("""
        INSERT INTO calendar_event(term_id, title, start_date, end_date, note)
        VALUES(#{termId}, #{title}, #{startDate}, #{endDate}, #{note})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertEvent(Map<String,Object> row);

    @Update("""
        UPDATE calendar_event SET title = #{title}, start_date = #{startDate}, end_date = #{endDate}, note = #{note}
        WHERE id = #{id}
        """)
    int updateEvent(Map<String,Object> row);

    @Delete("DELETE FROM calendar_event WHERE id = #{id}")
    int deleteEvent(@Param("id") Long id);

    @Select("SELECT id, term_id AS termId FROM calendar_event WHERE id = #{id}")
    Map<String,Object> eventBrief(@Param("id") Long id);

    // ---- 业务：兼职 / 活动 / 二手 ----
    @Select("""
        <script>
        SELECT j.id, j.title, j.day_salary AS daySalary, j.settle, j.location, j.time_desc AS timeDesc,
               j.status, j.description, j.user_id AS userId, j.created_at AS createdAt,
               j.school_id AS schoolId, u.nickname AS publisherName, s.name AS schoolName
        FROM job j LEFT JOIN `user` u ON u.id = j.user_id LEFT JOIN school s ON s.id = j.school_id
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">j.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        ORDER BY j.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> jobs(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM job j
        <where>
          <if test="schoolId != null">j.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">j.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countJobs(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    @Delete("DELETE FROM job WHERE id = #{id}")
    int deleteJob(@Param("id") Long id);

    @Select("""
        <script>
        SELECT a.id, a.title, a.poster, a.time_desc AS timeDesc, a.location, a.enrolled, a.status,
               a.description, a.user_id AS userId, a.created_at AS createdAt,
               a.school_id AS schoolId, s.name AS schoolName
        FROM campus_activity a LEFT JOIN school s ON s.id = a.school_id
        <where>
          <if test="schoolId != null">a.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">a.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> activities(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM campus_activity a
        <where>
          <if test="schoolId != null">a.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">a.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countActivities(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    @Update("UPDATE campus_activity SET status = #{status} WHERE id = #{id}")
    int setActivityStatus(@Param("id") Long id, @Param("status") int status);

    @Select("""
        <script>
        SELECT g.id, g.mode, g.title, g.price, g.condition_level AS conditionLevel, g.category,
               g.image, g.description, g.status, g.view_count AS viewCount,
               g.favorite_count AS favoriteCount, g.contact_count AS contactCount,
               g.created_at AS createdAt, g.school_id AS schoolId, u.nickname AS sellerName,
               s.name AS schoolName
        FROM goods g LEFT JOIN `user` u ON u.id = g.user_id LEFT JOIN school s ON s.id = g.school_id
        <where>
          <if test="schoolId != null">g.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">g.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        ORDER BY g.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> goods(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM goods g
        <where>
          <if test="schoolId != null">g.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">g.title LIKE #{keyword} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countGoods(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    @Update("UPDATE goods SET status = #{status} WHERE id = #{id}")
    int setGoodsStatus(@Param("id") Long id, @Param("status") int status);

    // ---- 运营：Banner ----
    // 分页：limit=null 表示不分页（供内部全量调用）
    @Select("""
        <script>
        SELECT b.id, b.slot, b.eyebrow, b.title, b.subtitle, b.badge, b.theme,
               b.link_type AS linkType, b.link_value AS linkValue, b.sort, b.status,
               b.school_id AS schoolId, s.name AS schoolName
        FROM banner b LEFT JOIN school s ON s.id = b.school_id
        <where><if test="schoolId != null">b.school_id = #{schoolId}</if></where>
        ORDER BY b.slot, b.sort, b.id
        <if test="limit != null">LIMIT #{limit} OFFSET #{offset}</if>
        </script>
        """)
    List<Map<String,Object>> banners(@Param("schoolId") Long schoolId,
                                     @Param("limit") Integer limit,
                                     @Param("offset") Integer offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM banner b
        <where><if test="schoolId != null">b.school_id = #{schoolId}</if></where>
        </script>
        """)
    long countBanners(@Param("schoolId") Long schoolId);

    @Insert("""
        INSERT INTO banner(slot, eyebrow, title, subtitle, badge, theme, link_type, link_value, sort, status, school_id)
        VALUES(#{slot}, #{eyebrow}, #{title}, #{subtitle}, #{badge}, #{theme}, #{linkType}, #{linkValue}, #{sort}, #{status}, #{schoolId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBanner(Map<String,Object> row);

    @Update("""
        UPDATE banner SET slot = #{slot}, eyebrow = #{eyebrow}, title = #{title}, subtitle = #{subtitle},
               badge = #{badge}, theme = #{theme}, link_type = #{linkType}, link_value = #{linkValue},
               sort = #{sort}, status = #{status} WHERE id = #{id}
        """)
    int updateBanner(Map<String,Object> row);

    @Delete("DELETE FROM banner WHERE id = #{id}")
    int deleteBanner(@Param("id") Long id);


    // ---- 外卖：店铺 ----
    @Select("""
        <script>
        SELECT s.id, s.name, s.cover, s.category, s.rating, s.monthly_sales AS monthlySales,
               s.min_price AS minPrice, s.delivery_fee AS deliveryFee, s.delivery_time AS deliveryTime,
               s.discount, s.notice, s.status, s.order_count AS orderCount, s.view_count AS viewCount,
               s.school_id AS schoolId, sc.name AS schoolName
        FROM shop s LEFT JOIN school sc ON sc.id = s.school_id
        <where>
          <if test="schoolId != null">s.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">s.name LIKE #{keyword} AND</if>
          1 = 1
        </where>
        ORDER BY s.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String,Object>> shops(@Param("schoolId") Long schoolId, @Param("keyword") String keyword,
                                   @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM shop s
        <where>
          <if test="schoolId != null">s.school_id = #{schoolId} AND</if>
          <if test="keyword != null and keyword != ''">s.name LIKE #{keyword} AND</if>
          1 = 1
        </where>
        </script>
        """)
    long countShops(@Param("schoolId") Long schoolId, @Param("keyword") String keyword);

    @Select("SELECT id, name, school_id AS schoolId FROM shop WHERE id = #{id}")
    Map<String,Object> shopBrief(@Param("id") Long id);

    @Insert("""
        INSERT INTO shop(name, cover, category, rating, min_price, delivery_fee, delivery_time,
                         discount, notice, status, school_id)
        VALUES(#{name}, #{cover}, #{category}, #{rating}, #{minPrice}, #{deliveryFee}, #{deliveryTime},
               #{discount}, #{notice}, #{status}, #{schoolId})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertShop(Map<String,Object> row);

    @Update("""
        UPDATE shop SET name = #{name}, cover = #{cover}, category = #{category},
               min_price = #{minPrice}, delivery_fee = #{deliveryFee}, delivery_time = #{deliveryTime},
               discount = #{discount}, notice = #{notice}
        WHERE id = #{id}
        """)
    int updateShop(Map<String,Object> row);

    @Update("UPDATE shop SET status = #{status} WHERE id = #{id}")
    int setShopStatus(@Param("id") Long id, @Param("status") int status);

    @Delete("DELETE FROM shop WHERE id = #{id}")
    int deleteShop(@Param("id") Long id);

    // ---- 外卖：菜品 ----
    @Select("""
        SELECT g.id, g.shop_id AS shopId, g.category, g.name, g.price,
               g.monthly_sales AS monthlySales, g.image, g.sort_num AS sortNum, g.status
        FROM shop_goods g WHERE g.shop_id = #{shopId}
        ORDER BY g.sort_num, g.id
        """)
    List<Map<String,Object>> shopGoods(@Param("shopId") Long shopId);

    @Select("SELECT id, shop_id AS shopId FROM shop_goods WHERE id = #{id}")
    Map<String,Object> shopGoodsBrief(@Param("id") Long id);

    @Insert("""
        INSERT INTO shop_goods(shop_id, category, name, price, image, sort_num, status)
        VALUES(#{shopId}, #{category}, #{name}, #{price}, #{image}, #{sortNum}, #{status})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertShopGoods(Map<String,Object> row);

    @Update("""
        UPDATE shop_goods SET category = #{category}, name = #{name}, price = #{price},
               image = #{image}, sort_num = #{sortNum}, status = #{status}
        WHERE id = #{id}
        """)
    int updateShopGoods(Map<String,Object> row);

    @Delete("DELETE FROM shop_goods WHERE id = #{id}")
    int deleteShopGoods(@Param("id") Long id);

    // ---- 校园评选：选手 ----
    // idol_contestant 没有 school_id，评选是全平台活动，不按校隔离。
    @Select("""
        <script>
        SELECT id, user_id AS userId, name, gender, college, photo, slogan,
               vote_count AS voteCount, status, created_at AS createdAt
        FROM idol_contestant
        <where>
          <if test="gender != null">gender = #{gender} AND</if>
          1 = 1
        </where>
        ORDER BY gender, vote_count DESC, id
        </script>
        """)
    List<Map<String,Object>> idolContestants(@Param("gender") Integer gender);

    @Select("SELECT id, name, gender, photo FROM idol_contestant WHERE id = #{id}")
    Map<String,Object> idolContestantBrief(@Param("id") Long id);

    @Insert("""
        INSERT INTO idol_contestant(user_id, name, gender, college, photo, slogan, status)
        VALUES(#{userId}, #{name}, #{gender}, #{college}, #{photo}, #{slogan}, #{status})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertIdolContestant(Map<String,Object> row);

    @Update("""
        UPDATE idol_contestant SET name = #{name}, gender = #{gender}, college = #{college},
               photo = #{photo}, slogan = #{slogan}
        WHERE id = #{id}
        """)
    int updateIdolContestant(Map<String,Object> row);

    @Update("UPDATE idol_contestant SET status = #{status} WHERE id = #{id}")
    int setIdolContestantStatus(@Param("id") Long id, @Param("status") int status);

    @Delete("DELETE FROM idol_contestant WHERE id = #{id}")
    int deleteIdolContestant(@Param("id") Long id);
    // ---- 学校名查询（校管提交时需要写入 academic_term.school 兼容字段）----
    @Select("SELECT id, name FROM school WHERE id = #{id}")
    Map<String,Object> schoolBrief(@Param("id") Long id);

    // ---- 内容编辑（帖子 / 二手 / 活动）----
    // 管理端原来只能下架/删除，改不了内容也换不了图。
    @Update("""
        UPDATE post SET title = #{title}, content = #{content}, image = #{image}, tag = #{tag}
        WHERE id = #{id}
        """)
    int updatePostContent(Map<String,Object> row);

    @Update("""
        UPDATE goods SET title = #{title}, price = #{price}, condition_level = #{conditionLevel},
               category = #{category}, image = #{image}, description = #{description}
        WHERE id = #{id}
        """)
    int updateGoodsContent(Map<String,Object> row);

    @Update("""
        UPDATE campus_activity SET title = #{title}, poster = #{poster}, time_desc = #{timeDesc},
               location = #{location}, description = #{description}
        WHERE id = #{id}
        """)
    int updateActivityContent(Map<String,Object> row);

    @Select("""
        SELECT id, user_id AS userId, title, price, condition_level AS conditionLevel,
               category, image, description, school_id AS schoolId
        FROM goods WHERE id = #{id}
        """)
    Map<String,Object> goodsBrief(@Param("id") Long id);

    @Select("""
        SELECT id, title, poster, time_desc AS timeDesc, location, description, school_id AS schoolId
        FROM campus_activity WHERE id = #{id}
        """)
    Map<String,Object> activityBrief(@Param("id") Long id);

    // ---- 用户头像 ----
    @Update("UPDATE `user` SET avatar = #{avatar} WHERE id = #{id}")
    int setUserAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    @Select("SELECT id, nickname, avatar, school_id AS schoolId FROM `user` WHERE id = #{id}")
    Map<String,Object> userBrief(@Param("id") Long id);
}
