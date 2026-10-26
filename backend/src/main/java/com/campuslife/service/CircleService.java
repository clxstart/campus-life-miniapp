package com.campuslife.service;

import com.campuslife.entity.User;
import com.campuslife.mapper.CircleMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class CircleService {
 private final CircleMapper mapper; private final UserMapper users;
 private static long number(Object x){return x instanceof Boolean b?(b?1:0):x instanceof Number n?n.longValue():0;}
 private User user(Long id){if(id==null||id<1)throw new IllegalArgumentException("用户参数无效");User u=users.selectById(id);if(u==null)throw new IllegalArgumentException("用户不存在");return u;}
 private Map<String,Object> circle(Long id,Long userId){Map<String,Object> row=mapper.circle(id,userId);if(row==null)throw new IllegalArgumentException("圈子不存在或已解散");return row;}

 // 圈子列表：分页 + 分类筛选；hotOnly 供轮播使用；按校隔离（每校有自己的圈子）
 // explicitSchoolId = 小程序里用户选中的学校，优先于用户账号所属学校
 public Map<String,Object> circles(Long userId,String category,boolean hotOnly,int page,int size,String schoolScope,Long explicitSchoolId){
   User u=user(userId);String cat=category==null?"":category.trim();
   Long schoolId=resolveSchoolId(schoolScope,explicitSchoolId,u);
   List<Map<String,Object>> rows=mapper.circles(userId,cat,hotOnly,size,(page-1)*size,schoolId);
   for(Map<String,Object> row:rows)row.put("joined",number(row.get("joined"))>0);
   return Map.of("list",rows,"total",mapper.countCircles(cat,hotOnly,schoolId),"page",page,"size",size);
 }

 // 热门圈子：轮播数据源，默认取 5 个；同样遵守学校范围
 public List<Map<String,Object>> hot(Long userId,int limit,String schoolScope,Long explicitSchoolId){
   User u=user(userId);int capped=Math.max(1,Math.min(limit,10));
   Long schoolId=resolveSchoolId(schoolScope,explicitSchoolId,u);
   List<Map<String,Object>> rows=mapper.circles(userId,"",true,capped,0,schoolId);
   for(Map<String,Object> row:rows)row.put("joined",number(row.get("joined"))>0);
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
   row.put("hot",number(row.get("hot"))>0);
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
}
