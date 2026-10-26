package com.campuslife.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper; import com.campuslife.dto.*; import com.campuslife.entity.*; import com.campuslife.mapper.*;
import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime; import java.util.*;
@Service @RequiredArgsConstructor public class IdolService {
 private final IdolContestantMapper contestantMapper; private final IdolVoteMapper voteMapper;
 // 活动配置：演示期固定投票截止时间，接入管理后台后改为读配置
 private static final String VOTE_END_AT = "2026-10-31 23:59:59";
 public Map<String,Object> activity(){
   long contestants=contestantMapper.selectCount(new LambdaQueryWrapper<IdolContestant>().eq(IdolContestant::getStatus,1));
   long votes=voteMapper.selectCount(null);
   Map<String,Object> out=new LinkedHashMap<>(); out.put("title","校园人气王评选"); out.put("phase","voting"); out.put("voteEndAt",VOTE_END_AT); out.put("contestants",contestants); out.put("votes",votes); return out;}
 public List<Map<String,Object>> contestants(Integer gender,Long userId){
   List<IdolContestant> list=contestantMapper.selectList(new LambdaQueryWrapper<IdolContestant>().eq(IdolContestant::getStatus,1).eq(IdolContestant::getGender,gender).orderByDesc(IdolContestant::getVoteCount));
   Set<Long> votedSet=new HashSet<>();
   if(!list.isEmpty()){
     List<Long> ids=new ArrayList<>(); for(IdolContestant c:list){ids.add(c.getId());}
     for(IdolVote v:voteMapper.selectList(new LambdaQueryWrapper<IdolVote>().eq(IdolVote::getUserId,userId).in(IdolVote::getContestantId,ids))){votedSet.add(v.getContestantId());}
   }
   List<Map<String,Object>> out=new ArrayList<>();
   for(IdolContestant c:list){Map<String,Object> m=new LinkedHashMap<>(); m.put("id",c.getId()); m.put("name",c.getName()); m.put("gender",c.getGender()); m.put("college",c.getCollege()); m.put("photo",c.getPhoto()); m.put("slogan",c.getSlogan()); m.put("voteCount",c.getVoteCount()); m.put("voted",votedSet.contains(c.getId())); m.put("createdAt",c.getCreatedAt()); out.add(m);}
   return out;}
 @Transactional public Map<String,Object> vote(Long id,Long userId){
   IdolContestant c=contestantMapper.selectById(id);
   if(c==null||c.getStatus()==null||c.getStatus()!=1){throw new IllegalArgumentException("选手不存在或已下架");}
   if(voteMapper.selectOne(new LambdaQueryWrapper<IdolVote>().eq(IdolVote::getContestantId,id).eq(IdolVote::getUserId,userId))!=null){throw new IllegalStateException("每人为同一位选手只能投一票");}
   IdolVote v=new IdolVote(); v.setContestantId(id); v.setUserId(userId); v.setCreatedAt(LocalDateTime.now()); voteMapper.insert(v);
   contestantMapper.update(null,new LambdaUpdateWrapper<IdolContestant>().eq(IdolContestant::getId,id).setSql("vote_count = vote_count + 1"));
   return Map.of("voted",true,"voteCount",c.getVoteCount()+1);}
 @Transactional public IdolContestant join(IdolJoinRequest r){
   IdolContestant c=new IdolContestant(); c.setUserId(r.getUserId()); c.setName(r.getName()); c.setGender(r.getGender()); c.setCollege(r.getCollege()==null?"":r.getCollege()); c.setPhoto(r.getPhoto()); c.setSlogan(r.getSlogan()==null?"":r.getSlogan()); c.setVoteCount(0); c.setStatus(1); c.setCreatedAt(LocalDateTime.now()); contestantMapper.insert(c); return c;}
}
