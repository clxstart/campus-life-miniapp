package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.User;
import com.campuslife.mapper.CircleMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class CircleService {
 private final CircleMapper mapper; private final UserMapper users; private final CircleStreakService streaks;
 private final TierService tier; private final FeatureFlags flags; private final AuditService audit;

 /** 用户可选的圈子分类：与现有分类保持一致，避免建出管理端筛不到的新词。 */
 private static final Set<String> USER_CATEGORIES = Set.of("兴趣", "学习", "生活", "游戏", "运动");

 private static String trim(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
 private static long number(Object x){return x instanceof Boolean b?(b?1:0):x instanceof Number n?n.longValue():0;}
 /**
  * 圈子火花展示字段。
  * 库里的 streak_days 只是"最后一次活跃时的连续天数"：漏了一天必须展示成 0，
  * 否则用户会看到一个已经断掉的假连续记录。时间判断统一由 CircleStreakService 按北京时间做。
  */
 private void decorateStreak(Map<String,Object> row){
  if(row==null)return;
  Map<String,Object> streak=streaks.view(null,row.get("streakDays"),row.get("lastActiveDate"),row.get("sparkOn"));
  row.put("streak",streak);
  row.put("streakDays",streak.get("streakDays"));
  row.put("sparkOn",streak.get("sparkOn"));
  row.put("activeToday",streak.get("activeToday"));
  row.put("nextSparkIn",streak.get("nextSparkIn"));
 }
 private User user(Long id){if(id==null||id<1)throw new IllegalArgumentException("用户参数无效");User u=users.selectById(id);if(u==null)throw new IllegalArgumentException("用户不存在");return u;}
 private Map<String,Object> circle(Long id,Long userId){Map<String,Object> row=mapper.circle(id,userId);if(row==null)throw new IllegalArgumentException("圈子不存在或已解散");return row;}

 // 圈子列表：分页 + 分类筛选；hotOnly 供轮播使用；按校隔离（每校有自己的圈子）
 // explicitSchoolId = 小程序里用户选中的学校，优先于用户账号所属学校
 public Map<String,Object> circles(Long userId,String category,boolean hotOnly,int page,int size,String schoolScope,Long explicitSchoolId){
   User u=user(userId);String cat=category==null?"":category.trim();
   Long schoolId=resolveSchoolId(schoolScope,explicitSchoolId,u);
   List<Map<String,Object>> rows=mapper.circles(userId,cat,hotOnly,size,(page-1)*size,schoolId);
   for(Map<String,Object> row:rows){row.put("joined",number(row.get("joined"))>0);decorateStreak(row);}
   return Map.of("list",rows,"total",mapper.countCircles(cat,hotOnly,schoolId),"page",page,"size",size);
 }

 // 热门圈子：轮播数据源，默认取 5 个；同样遵守学校范围
 public List<Map<String,Object>> hot(Long userId,int limit,String schoolScope,Long explicitSchoolId){
   User u=user(userId);int capped=Math.max(1,Math.min(limit,10));
   Long schoolId=resolveSchoolId(schoolScope,explicitSchoolId,u);
   List<Map<String,Object>> rows=mapper.circles(userId,"",true,capped,0,schoolId);
   for(Map<String,Object> row:rows){row.put("joined",number(row.get("joined"))>0);decorateStreak(row);}
   return rows;
 }

 // 学校范围三档：显式选中的学校 > all > 用户账号所属学校
 private static Long resolveSchoolId(String schoolScope,Long explicitSchoolId,User u){
   if(explicitSchoolId!=null&&explicitSchoolId>0)return explicitSchoolId;
   if("all".equals(schoolScope))return null;
   return u==null?null:u.getSchoolId();
 }

 public Map<String,Object> detail(Long id,Long userId){
   user(userId);Map<String,Object> row=circle(id,userId);
   row.put("joined",number(row.get("joined"))>0);
   row.put("hot",number(row.get("hot"))>0);decorateStreak(row);
   return row;
 }

 public Map<String,Object> posts(Long id,Long userId,int page,int size){
   user(userId);circle(id,userId);
   List<Map<String,Object>> rows=mapper.circlePosts(id,userId,size,(page-1)*size);
   for(Map<String,Object> row:rows){
     row.put("liked",number(row.get("liked"))>0);
     row.put("followed",number(row.get("followed"))>0);
     row.put("favored",number(row.get("favored"))>0);
   }
   return Map.of("list",rows,"total",mapper.countCirclePosts(id),"page",page,"size",size);
 }

 // 加入：唯一键保证重复调用幂等，计数以实际成员数为准
 @Transactional public Map<String,Object> join(Long id,Long userId){
   user(userId);circle(id,userId);
   mapper.join(id,userId);mapper.syncMemberCount(id);
   return Map.of("joined",true,"memberCount",mapper.memberCount(id));
 }

 @Transactional public Map<String,Object> leave(Long id,Long userId){
   user(userId);circle(id,userId);
   mapper.leave(id,userId);mapper.syncMemberCount(id);
   return Map.of("joined",false,"memberCount",mapper.memberCount(id));
 }

 // 发帖后同步圈子计数（由 ForumService 调用）
 public void syncPostCount(Long circleId){if(circleId!=null&&circleId>0)mapper.syncPostCount(circleId);}

 public List<Map<String,Object>> categories(){return mapper.categories();}

 public Map<String,Object> brief(Long id){Map<String,Object> row=mapper.brief(id);if(row==null)throw new IllegalArgumentException("圈子不存在或已解散");return row;}

 // 推荐流：兴趣（已加入圈子）+ 热度 + 时效，排序在后端 SQL 内完成
 public Map<String,Object> recommend(Long userId,int page,int size){
   user(userId);
   List<Map<String,Object>> rows=mapper.recommend(userId,size,(page-1)*size);
   for(Map<String,Object> row:rows){
     row.put("liked",number(row.get("liked"))>0);
     row.put("followed",number(row.get("followed"))>0);
     row.put("favored",number(row.get("favored"))>0);
     // 分数是内部排序依据，不返回给客户端，避免暴露排序细节
     row.remove("score");
   }
   return Map.of("list",rows,"total",mapper.countAllPosts(),"page",page,"size",size);
 }

 /**
  * 用户建圈（黄金段位特权）。
  *
  * 校验顺序与设计稿一致：开关 → 段位 → 名额 → 参数 → 内容审核。
  * 落库 status=0 待审：审核通过前不在圈子列表出现，避免群聊入口失控。
  * 建圈即成为 owner；名额按 creator_id 统计，待审也占名额（防止反复提交刷名额）。
  */
 @Transactional
 public Map<String,Object> createForUser(Long userId, Map<String,Object> body){
   User u = user(userId);
   if(!flags.isEnabled("circle.create.enabled")) throw new ForbiddenException("圈子创建暂未开放");
   if(tier.rankOf(tier.effectiveTierCode(userId)) < tier.rankOf(TierService.GOLD))
     throw new ForbiddenException("创建圈子需要黄金段位（成长值 200）");
   if(mapper.countByCreator(userId) >= 1) throw new ForbiddenException("最多创建 1 个圈子");

   Map<String,Object> data = body == null ? Map.of() : body;
   String name = trim(data.get("name"));
   if(name.isEmpty() || name.length() > 16) throw new IllegalArgumentException("圈子名称须为1至16字");
   String category = trim(data.get("category"));
   if(!USER_CATEGORIES.contains(category)) throw new IllegalArgumentException("请选择圈子分类");
   String description = trim(data.get("description"));
   if(description.length() > 64) throw new IllegalArgumentException("简介不能超过64字");
   String cover = trim(data.get("cover"));

   var textAudit = audit.checkText(name + "\n" + description, "circle", userId);
   audit.rejectWithLog(textAudit, "circle", userId, name + "\n" + description);

   String slug = "u" + userId + "-" + System.currentTimeMillis();
   if(slug.length() > 32 || mapper.slugTaken(slug) > 0) throw new IllegalArgumentException("圈子标识冲突，请重试");
   String auditStatus = audit.initialStatus(textAudit, false);
   Map<String,Object> row = new LinkedHashMap<>();
   row.put("name", name);
   row.put("slug", slug);
   row.put("category", category);
   row.put("description", description);
   row.put("cover", cover);
   row.put("theme", "blue");
   row.put("schoolId", u.getSchoolId());
   row.put("creatorId", userId);
   row.put("auditStatus", auditStatus);
   row.put("auditReason", textAudit.reason());
   row.put("auditAt", "passed".equals(auditStatus) ? LocalDateTime.now() : null);
   mapper.insertUserCircle(row);
   Object idValue = row.get("id");
   Long circleId = idValue instanceof Number n ? n.longValue() : null;
   if(circleId == null || circleId < 1) throw new IllegalStateException("圈子创建失败");
   mapper.addOwner(circleId, userId);
   mapper.syncMemberCount(circleId);
   return Map.of("circleId", circleId, "status", "pending");
 }
}
