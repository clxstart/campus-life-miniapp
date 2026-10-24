package com.campuslife.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; import com.baomidou.mybatisplus.extension.plugins.pagination.Page; import com.campuslife.dto.*; import com.campuslife.entity.*; import com.campuslife.mapper.*; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.time.LocalDateTime; import java.util.*;
@Service @RequiredArgsConstructor public class ForumService {
 private final CampusMapper campusMapper; private final CircleService circleService; private final PostMapper postMapper; private final UserMapper userMapper; private final PostLikeMapper likeMapper; private final CommentMapper commentMapper; private final FollowMapper followMapper; private final PostViewMapper viewMapper; private final FavoriteMapper favoriteMapper; private final com.campuslife.mapper.BannerMapper bannerMapper; private final AuditService auditService;
 public List<com.campuslife.entity.Banner> banners(String slot){return bannerMapper.selectList(new LambdaQueryWrapper<com.campuslife.entity.Banner>().eq(com.campuslife.entity.Banner::getSlot,slot).eq(com.campuslife.entity.Banner::getStatus,1).orderByAsc(com.campuslife.entity.Banner::getSort));}
 public Map<String,Object> page(String tag,String scope,String postScope,Long userId,int page,int size){
   return page(tag,scope,postScope,userId,page,size,null);
 }

 // explicitSchoolId = 小程序里用户选中的学校，优先于账号所属学校
 public Map<String,Object> page(String tag,String scope,String postScope,Long userId,int page,int size,Long explicitSchoolId){
   LambdaQueryWrapper<Post> wrapper=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1);
   // 帖子按校可见（混合模式）：默认本校流 = 本校帖 + 跨校帖(school_id 为空)；
   // postScope=all 返回全部学校。用户无学校归属时不加过滤，避免新用户看到空流。
   if(!"all".equals(postScope)){
     Long scopedSchoolId=(explicitSchoolId!=null&&explicitSchoolId>0)?explicitSchoolId:userSchoolId(userId);
     if(scopedSchoolId!=null){ wrapper.and(q->q.eq(Post::getSchoolId,scopedSchoolId).or().isNull(Post::getSchoolId)); }
   }
   if("followed".equals(scope)){
     List<Long> ids=new ArrayList<>();
     for(Follow f:followMapper.selectList(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,userId))){ids.add(f.getFolloweeId());}
     if(ids.isEmpty())return Map.of("list",List.of(),"total",0);
     wrapper.in(Post::getUserId,ids);
   }
   if(!Strings.isBlank(tag)){ wrapper.and(q->q.eq(Post::getTag,tag).or().like(Post::getTag,tag)); }
   if("hot".equals(scope)){
     // 发现页热度流：只收被点赞或浏览过的帖子，按 点赞>浏览>评论 排序
     wrapper.and(q->q.gt(Post::getLikeCount,0).or().gt(Post::getViewCount,0));
     wrapper.orderByDesc(Post::getLikeCount).orderByDesc(Post::getViewCount).orderByDesc(Post::getCommentCount);
   }else{
     wrapper.orderByDesc(Post::getCreatedAt);
   }
   return pageResult(wrapper,userId,page,size);
 }
 // 学习分类的编码与小程序 utils/learn.js 保持一致。
 private static final Set<String> LEARN_CATEGORIES=Set.of("daike","paper","job","kaoyan","kaogong");
 private Long learnSchoolId(Long explicitSchoolId,Long userId){
   if(explicitSchoolId!=null){
     if(explicitSchoolId<1)throw new IllegalArgumentException("学校不合法");
     return explicitSchoolId;
   }
   return userSchoolId(userId);
 }
 @SuppressWarnings("unchecked")
 public Map<String,Object> learnTopics(String category,Long schoolId,Long userId,Long beforeId,int size){
   int safeSize=Math.min(50,Math.max(1,size));
   Long selectedSchoolId=learnSchoolId(schoolId,userId);
   if(category==null||!LEARN_CATEGORIES.contains(category)||selectedSchoolId==null)
     return Map.of("list",List.of(),"total",0,"hasMore",false,"nextCursor","");
   if(beforeId!=null&&beforeId<1)throw new IllegalArgumentException("分页游标不合法");
   // 学习交流仅看当前学校，绝不把其它学校或 NULL 归属的内容并进来。
   LambdaQueryWrapper<Post> wrapper=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1)
     .eq(Post::getLearnCategory,category).eq(Post::getSchoolId,selectedSchoolId);
   long total=postMapper.selectCount(wrapper);
   if(beforeId!=null)wrapper.lt(Post::getId,beforeId);
   wrapper.orderByDesc(Post::getId);
   Map<String,Object> result=pageResult(wrapper,userId,1,safeSize+1);
   List<ForumPostDTO> list=new ArrayList<>((List<ForumPostDTO>)result.get("list"));
   boolean hasMore=list.size()>safeSize;
   if(hasMore)list.remove(list.size()-1);
   result.put("list",list);result.put("total",total);result.put("hasMore",hasMore);
   result.put("nextCursor",hasMore&&!list.isEmpty()?String.valueOf(list.get(list.size()-1).getId()):"");
   return result;
 }
 public Map<String,Long> learnCounts(Long schoolId,Long userId){
   Map<String,Long> counts=new LinkedHashMap<>();
   for(String key:List.of("daike","paper","job","kaoyan","kaogong"))counts.put(key,0L);
   Long selectedSchoolId=learnSchoolId(schoolId,userId);
   if(selectedSchoolId==null)return counts;
   com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Post> wrapper=new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Post>()
     .select("learn_category","COUNT(*) AS total").eq("status",1).in("learn_category",LEARN_CATEGORIES).eq("school_id",selectedSchoolId).groupBy("learn_category");
   for(Map<String,Object> row:postMapper.selectMaps(wrapper)){
     Object key=row.get("learn_category"),value=row.get("total");
     if(key!=null&&counts.containsKey(key.toString())&&value instanceof Number)counts.put(key.toString(),((Number)value).longValue());
   }
   return counts;
 }
 // 用户的学校归属；无归属返回 null（此时帖子流不加学校过滤）
 private Long userSchoolId(Long userId){
   User u=userMapper.selectById(userId);
   return u==null?null:u.getSchoolId();
 }
 // 图片必须是服务端可访问地址；小程序本地临时路径（wxfile:// / http://tmp/）一律拒绝
 private static boolean validImage(String url){
   if(url==null)return true;
   String v=url.trim();
   if(v.isEmpty())return true;
   if(v.startsWith("wxfile://"))return false;
   if(v.startsWith("http://tmp/")||v.startsWith("https://tmp/"))return false;
   if(v.startsWith("http://")||v.startsWith("https://"))return true;
   return v.startsWith("/uploads/")||v.startsWith("/assets/");
 }
 public Map<String,Object> search(String keyword,Long userId,int page,int size){
   // LIKE 参数由 MyBatis-Plus 绑定；OR 必须括起来，避免绕过 status=1。
   LambdaQueryWrapper<Post> wrapper=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1)
     .and(q->q.like(Post::getTitle,keyword).or().like(Post::getContent,keyword))
     .orderByDesc(Post::getCreatedAt).orderByDesc(Post::getId);
   return pageResult(wrapper,userId,page,size);
 }
 private Map<String,Object> pageResult(LambdaQueryWrapper<Post> wrapper,Long userId,int page,int size){
   Page<Post> p=postMapper.selectPage(new Page<>(page,size),wrapper);
   List<Long> uids=new ArrayList<>();
   for(Post x:p.getRecords()){uids.add(x.getUserId());}
   Map<Long,User> users=new HashMap<>();
   if(!uids.isEmpty()){for(User u:userMapper.selectBatchIds(uids)){users.put(u.getId(),u);}}
   Set<Long> likedSet=new HashSet<>();
   if(!p.getRecords().isEmpty()){
     List<Long> pids=new ArrayList<>();
     for(Post x:p.getRecords()){pids.add(x.getId());}
     for(PostLike l:likeMapper.selectList(new LambdaQueryWrapper<PostLike>().eq(PostLike::getUserId,userId).in(PostLike::getPostId,pids))){likedSet.add(l.getPostId());}
   }
   Set<Long> followedSet=new HashSet<>();
   if(!uids.isEmpty()){
     for(Follow f:followMapper.selectList(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,userId).in(Follow::getFolloweeId,uids))){followedSet.add(f.getFolloweeId());}
   }
   List<ForumPostDTO> list=new ArrayList<>();
   for(Post x:p.getRecords()){User u=users.get(x.getUserId()); ForumPostDTO d=new ForumPostDTO(); d.setId(x.getId()); d.setAuthorId(x.getUserId()); d.setAuthorNickname(u==null?"校园同学":u.getNickname()); d.setAuthorAvatar(u==null?null:u.getAvatar()); d.setTitle(x.getTitle()); d.setContent(x.getContent()); d.setImage(x.getImage()); d.setTag(x.getTag()); d.setLearnCategory(x.getLearnCategory()); d.setSchoolId(x.getSchoolId()); d.setCrossSchool(x.getSchoolId()==null); d.setLocation(x.getLocation()); d.setLikeCount(x.getLikeCount()); d.setCommentCount(x.getCommentCount()); d.setViewCount(x.getViewCount()); d.setLiked(likedSet.contains(x.getId())); d.setFollowed(followedSet.contains(x.getUserId())); d.setCreatedAt(x.getCreatedAt()); list.add(d);}
   Map<String,Object> out=new LinkedHashMap<>(); out.put("list",list); out.put("total",p.getTotal()); return out;}
 public ForumPostDTO detail(Long id,Long userId){
   Post x=postMapper.selectById(id);
   if(x==null||x.getStatus()==null||x.getStatus()!=1)return null;
   User u=userMapper.selectById(x.getUserId());
   ForumPostDTO d=new ForumPostDTO();
   d.setId(x.getId()); d.setAuthorId(x.getUserId()); d.setAuthorNickname(u==null?"校园同学":u.getNickname()); d.setAuthorAvatar(u==null?null:u.getAvatar()); d.setTitle(x.getTitle()); d.setContent(x.getContent()); d.setImage(x.getImage()); d.setTag(x.getTag()); d.setLearnCategory(x.getLearnCategory()); d.setSchoolId(x.getSchoolId()); d.setCrossSchool(x.getSchoolId()==null); d.setLocation(x.getLocation()); d.setLikeCount(x.getLikeCount()); d.setCommentCount(x.getCommentCount()); d.setViewCount(x.getViewCount()); d.setCreatedAt(x.getCreatedAt());
   d.setLiked(likeMapper.selectCount(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId,id).eq(PostLike::getUserId,userId))>0);
   d.setFollowed(followMapper.selectCount(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,userId).eq(Follow::getFolloweeId,x.getUserId()))>0);
   d.setFavored(favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getPostId,id).eq(Favorite::getUserId,userId))>0);
   return d;}
 @Transactional public Post create(CreatePostRequest r){
   String learnCategory=r.getLearnCategory();
   if(learnCategory!=null&&!learnCategory.isEmpty()&&!LEARN_CATEGORIES.contains(learnCategory))throw new IllegalArgumentException("学习分类不合法");
   if(!validImage(r.getImage()))throw new IllegalArgumentException("图片未上传成功，请重新选择图片");
   AuditResult textAudit=auditService.checkText(r.getTitle()+"\n"+r.getContent(),"post",r.getUserId());
   auditService.rejectIfNeeded(textAudit);
   Post p=new Post(); p.setUserId(r.getUserId());p.setTitle(r.getTitle());p.setContent(r.getContent());p.setImage(r.getImage());p.setTag(learnCategory!=null&&!learnCategory.isEmpty()?"学习":r.getTag());p.setLearnCategory(learnCategory==null||learnCategory.isEmpty()?null:learnCategory);p.setLocation(r.getLocation());
   if(r.getCircleId()!=null&&r.getCircleId()>0){circleService.brief(r.getCircleId());p.setCircleId(r.getCircleId());}
   if(learnCategory!=null&&!learnCategory.isEmpty()){
     if(Boolean.TRUE.equals(r.getCrossSchool()))throw new IllegalArgumentException("学习交流仅在所选学校可见");
     Long selectedSchoolId=learnSchoolId(r.getSchoolId(),r.getUserId());
     if(selectedSchoolId==null||campusMapper.publicSchools().stream().noneMatch(row->row.get("id") instanceof Number&&selectedSchoolId.equals(((Number)row.get("id")).longValue())))throw new IllegalArgumentException("请先选择有效学校");
     p.setSchoolId(selectedSchoolId);
   }else if(Boolean.TRUE.equals(r.getCrossSchool())){p.setSchoolId(null);}else{p.setSchoolId(userSchoolId(r.getUserId()));}
   String auditStatus=auditService.initialStatus(textAudit,r.getImage()!=null&&!r.getImage().isBlank());
   LocalDateTime now=LocalDateTime.now();
   p.setAuditStatus(auditStatus);p.setAuditReason(textAudit.reason());p.setAuditAt("passed".equals(auditStatus)?now:null);
   p.setLikeCount(0);p.setCommentCount(0);p.setStatus("passed".equals(auditStatus)?1:0);p.setCreatedAt(now);p.setUpdatedAt(now);
   postMapper.insert(p);
   auditService.logTextResult("post",p.getId(),r.getUserId(),r.getTitle()+"\n"+r.getContent(),textAudit);
   if(p.getImage()!=null&&!p.getImage().isBlank())auditService.scheduleImagesAfterCommit(List.of(p.getImage()),"post",p.getId(),r.getUserId());
   if(p.getCircleId()!=null)circleService.syncPostCount(p.getCircleId());
   return p;
 }
 @Transactional public Map<String,Object> toggleLike(Long id,Long uid){LambdaQueryWrapper<PostLike> q=new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId,id).eq(PostLike::getUserId,uid);PostLike old=likeMapper.selectOne(q); boolean liked;if(old==null){PostLike x=new PostLike();x.setPostId(id);x.setUserId(uid);x.setCreatedAt(LocalDateTime.now());likeMapper.insert(x);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = like_count + 1"));liked=true;}else{likeMapper.delete(q);postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("like_count = GREATEST(like_count - 1, 0)"));liked=false;}if(liked){Post post=postMapper.selectById(id);if(post!=null&&!post.getUserId().equals(uid))campusMapper.notifyUser(post.getUserId(),"收到新的赞","有同学赞了你的帖子："+post.getTitle(),"like",id);}return Map.of("liked",liked);}
 @Transactional public Comment addComment(Long id,CommentRequest r){AuditResult textAudit=auditService.checkText(r.getContent(),"comment",r.getUserId());auditService.rejectIfNeeded(textAudit);Comment c=new Comment();c.setPostId(id);c.setUserId(r.getUserId());c.setContent(r.getContent());c.setAuditStatus(textAudit.reviewing()?"reviewing":"passed");c.setAuditReason(textAudit.reason());c.setAuditAt(textAudit.reviewing()?null:LocalDateTime.now());c.setCreatedAt(LocalDateTime.now());commentMapper.insert(c);auditService.logTextResult("comment",c.getId(),r.getUserId(),r.getContent(),textAudit);if(!textAudit.reviewing()){postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("comment_count = comment_count + 1"));Post post=postMapper.selectById(id);if(post!=null&&!post.getUserId().equals(r.getUserId()))campusMapper.notifyUser(post.getUserId(),"收到新的评论",r.getContent(),"comment",id);}return c;}
 public List<Map<String,Object>> comments(Long id){
   List<Comment> cs=commentMapper.selectList(new LambdaQueryWrapper<Comment>().eq(Comment::getPostId,id).eq(Comment::getAuditStatus,"passed").orderByAsc(Comment::getCreatedAt));
   List<Long> uids=new ArrayList<>(); for(Comment c:cs){uids.add(c.getUserId());}
   Map<Long,User> users=new HashMap<>();
   if(!uids.isEmpty()){for(User u:userMapper.selectBatchIds(uids)){users.put(u.getId(),u);}}
   List<Map<String,Object>> out=new ArrayList<>();
   for(Comment c:cs){User u=users.get(c.getUserId()); Map<String,Object> m=new LinkedHashMap<>(); m.put("id",c.getId()); m.put("userId",c.getUserId()); m.put("content",c.getContent()); m.put("createdAt",c.getCreatedAt()==null?null:c.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))); m.put("authorNickname",u==null?"同学":u.getNickname()); m.put("authorAvatar",u==null?null:u.getAvatar()); out.add(m);}
   return out;}
 @Transactional public void view(Long id,Long userId){postMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,id).setSql("view_count = view_count + 1"));PostView record=new PostView();record.setPostId(id);record.setUserId(userId);record.setCreatedAt(LocalDateTime.now());viewMapper.insert(record);}
 @Transactional public Map<String,Object> toggleFollow(Long followee,Long follower){LambdaQueryWrapper<Follow> q=new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,follower).eq(Follow::getFolloweeId,followee);Follow old=followMapper.selectOne(q);if(old==null){Follow f=new Follow();f.setFollowerId(follower);f.setFolloweeId(followee);f.setCreatedAt(LocalDateTime.now());followMapper.insert(f);if(!followee.equals(follower))campusMapper.notifyUser(followee,"新的关注","有同学关注了你","follow",null);return Map.of("followed",true);}followMapper.delete(q);return Map.of("followed",false);}
 public List<Map<String,Object>> hot(){return postMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Post>().select("tag","COUNT(*) AS posts","IFNULL(SUM(like_count),0)+IFNULL(SUM(comment_count),0) AS count").eq("status",1).groupBy("tag").orderByDesc("count").last("LIMIT 10"));}
 static class Strings{static boolean isBlank(String s){return s==null||s.isBlank();}}
}
