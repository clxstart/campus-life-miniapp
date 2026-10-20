package com.campuslife.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.dto.*;
import com.campuslife.entity.*;
import com.campuslife.mapper.*;
import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime; import java.util.*;
@Service @RequiredArgsConstructor public class UserService {
 private final CampusMapper campusMapper; private final UserMapper userMapper; private final PostMapper postMapper; private final FollowMapper followMapper; private final FavoriteMapper favoriteMapper; private final PostViewMapper viewMapper; private final UserAddressMapper addressMapper; private final CouponMapper couponMapper; private final FeedbackMapper feedbackMapper;
 // 个人资料：昵称/学校/头像 + 真实统计（发帖数、关注数、粉丝数）
 public Map<String,Object> profile(Long id){
   User u=userMapper.selectById(id);
   Map<String,Object> out=new LinkedHashMap<>();
   out.put("id",id);
   out.put("nickname",u==null?"校园同学":u.getNickname());
   out.put("school",u==null?"":u.getSchool());
   out.put("avatar",u==null?null:u.getAvatar());
   out.put("posts",postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getUserId,id).eq(Post::getStatus,1)));
   out.put("following",followMapper.selectCount(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowerId,id)));
   out.put("followers",followMapper.selectCount(new LambdaQueryWrapper<Follow>().eq(Follow::getFolloweeId,id)));
   return out;}
 // 个人主页：按作者分页查帖子（不再受「前 50 条」限制）
 public Map<String,Object> postsByAuthor(Long userId,Long viewerId,int page,int size){
   List<Map<String,Object>> rows=userMapper.postsByAuthor(userId,viewerId,size,(page-1)*size);
   for(Map<String,Object> row:rows){
     row.put("liked",row.get("liked") instanceof Boolean b?b:false);
     row.put("followed",row.get("followed") instanceof Boolean b?b:false);
   }
   return Map.of("list",rows,"total",userMapper.countPostsByAuthor(userId),"page",page,"size",size);}
 // 个人主页头部：资料 + 真实统计（发帖/关注/粉丝）
 public Map<String,Object> stats(Long id){
   Map<String,Object> out=new LinkedHashMap<>(profile(id));
   out.put("likedTotal",postMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Post>()
     .select("IFNULL(SUM(like_count),0) AS total").eq("user_id",id).eq("status",1)).stream()
     .findFirst().map(m->m.get("total")).orElse(0));
   return out;}
 @Transactional public Map<String,Object> updateProfile(Long id,ProfileUpdateRequest r){
   User u=userMapper.selectById(id);
   if(u==null){throw new IllegalArgumentException("用户不存在");}
   u.setNickname(r.getNickname()); userMapper.updateById(u);
   return Map.of("nickname",r.getNickname());}
 // 我的收藏：收藏的帖子，按收藏时间倒序
 public List<Map<String,Object>> favorites(Long id){
   List<Favorite> list=favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId,id).orderByDesc(Favorite::getCreatedAt));
   return postCards(list.stream().map(Favorite::getPostId).toList());}
 // 浏览记录：同一篇帖子只保留最近一条
 public List<Map<String,Object>> views(Long id){
   List<PostView> all=viewMapper.selectList(new LambdaQueryWrapper<PostView>().eq(PostView::getUserId,id).orderByDesc(PostView::getCreatedAt));
   Set<Long> seen=new LinkedHashSet<>(); for(PostView v:all){seen.add(v.getPostId());}
   return postCards(new ArrayList<>(seen));}
 @Transactional public Map<String,Object> toggleFavorite(Long userId,Long postId){
   LambdaQueryWrapper<Favorite> q=new LambdaQueryWrapper<Favorite>().eq(Favorite::getPostId,postId).eq(Favorite::getUserId,userId);
   Favorite old=favoriteMapper.selectOne(q);
   if(old!=null){favoriteMapper.delete(q);return Map.of("favored",false);}
   Favorite f=new Favorite(); f.setPostId(postId); f.setUserId(userId); f.setCreatedAt(LocalDateTime.now()); favoriteMapper.insert(f);
   Post post=postMapper.selectById(postId);if(post!=null&&!post.getUserId().equals(userId))campusMapper.notifyUser(post.getUserId(),"收到新的收藏","有同学收藏了你的帖子："+post.getTitle(),"favorite",postId);return Map.of("favored",true);}
 public List<Map<String,Object>> coupons(Long id){
   List<Coupon> list=couponMapper.selectList(new LambdaQueryWrapper<Coupon>().eq(Coupon::getUserId,id).orderByDesc(Coupon::getId));
   List<Map<String,Object>> out=new ArrayList<>();
   for(Coupon c:list){Map<String,Object> m=new LinkedHashMap<>(); m.put("id",c.getId()); m.put("title",c.getTitle()); m.put("amount",c.getAmount()); m.put("expireAt",c.getExpireAt()); m.put("status",c.getStatus()); out.add(m);}
   return out;}
 public List<Map<String,Object>> addresses(Long id){
   List<UserAddress> list=addressMapper.selectList(new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId,id).orderByDesc(UserAddress::getId));
   List<Map<String,Object>> out=new ArrayList<>();
   for(UserAddress a:list){Map<String,Object> m=new LinkedHashMap<>(); m.put("id",a.getId()); m.put("contact",a.getContact()); m.put("phone",a.getPhone()); m.put("detail",a.getDetail()); out.add(m);}
   return out;}
 @Transactional public UserAddress addAddress(Long id,AddressAddRequest r){
   UserAddress a=new UserAddress(); a.setUserId(id); a.setContact(r.getContact()==null||r.getContact().isBlank()?"我":r.getContact()); a.setPhone(r.getPhone()==null?"":r.getPhone()); a.setDetail(r.getDetail()); addressMapper.insert(a); return a;}
 @Transactional public Map<String,Object> feedback(Long userId,String content){
   Feedback f=new Feedback(); f.setUserId(userId); f.setContent(content); f.setCreatedAt(LocalDateTime.now()); feedbackMapper.insert(f);
   return Map.of("received",true);}
 // 收藏/浏览的帖子卡片：列表页只需要标题与时间
 private List<Map<String,Object>> postCards(List<Long> postIds){
   List<Map<String,Object>> out=new ArrayList<>();
   if(postIds.isEmpty())return out;
   for(Post p:postMapper.selectBatchIds(postIds)){Map<String,Object> m=new LinkedHashMap<>(); m.put("id",p.getId()); m.put("title",p.getTitle()); m.put("createdAt",p.getCreatedAt()); out.add(m);}
   return out;}
}
