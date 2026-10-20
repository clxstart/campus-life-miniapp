package com.campuslife.service;

import com.campuslife.dto.SendMessageRequest;
import com.campuslife.entity.User;
import com.campuslife.mapper.CampusMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @RequiredArgsConstructor
public class CampusService {
 private final CampusMapper mapper; private final UserMapper users;
 private static long number(Object x){return x instanceof Boolean b?(b?1:0):x instanceof Number n?n.longValue():0;}
 private User user(Long id){if(id==null||id<1)throw new IllegalArgumentException("用户参数无效");User u=users.selectById(id);if(u==null)throw new IllegalArgumentException("用户不存在");return u;}
 private void pair(Long userId,Long peerId){user(userId);user(peerId);if(userId.equals(peerId))throw new IllegalArgumentException("不能与自己聊天");}
 public List<Map<String,Object>> conversations(Long userId){user(userId);List<Map<String,Object>> rows=mapper.conversations(userId);for(Map<String,Object> row:rows){String name=String.valueOf(row.get("name"));row.put("avatarText",name.isEmpty()?"同":name.substring(0,1));row.put("color","#7EB6F6");row.put("online",false);row.put("muted",number(row.get("muted"))>0);if("image".equals(row.get("type")))row.put("preview","[图片]");}return rows;}
 public Map<String,Object> messages(Long userId,Long peerId,Long beforeId,int size){
   pair(userId,peerId);if(beforeId!=null&&beforeId<1)throw new IllegalArgumentException("分页游标无效");
   Map<String,Object> pref=mapper.preference(userId,peerId);long cleared=pref==null?0:number(pref.get("clearedBefore"));
   List<Map<String,Object>> rows=mapper.messages(userId,peerId,cleared,beforeId,size+1);
   boolean hasMore=rows.size()>size;if(hasMore)rows=new ArrayList<>(rows.subList(0,size));
   Collections.reverse(rows);for(Map<String,Object> row:rows)row.put("mine",number(row.get("senderId"))==userId);
   return Map.of("list",rows,"hasMore",hasMore,"muted",pref!=null&&number(pref.get("muted"))>0);
 }
 @Transactional public Map<String,Object> send(Long peerId,SendMessageRequest r){pair(r.getUserId(),peerId);Map<String,Object> row=new LinkedHashMap<>();row.put("senderId",r.getUserId());row.put("receiverId",peerId);row.put("content",r.getContent().trim());row.put("clientId",r.getClientId());mapper.send(row);Map<String,Object> out=mapper.message(number(row.get("id")));if(number(out.get("receiverId"))!=peerId||!Objects.equals(out.get("content"),r.getContent().trim()))throw new IllegalArgumentException("消息请求标识已被其他内容使用");out.put("mine",true);return out;}
 public void readChat(Long userId,Long peerId,Long upToId){pair(userId,peerId);if(upToId==null||upToId<1)throw new IllegalArgumentException("消息游标无效");mapper.readChat(userId,peerId,upToId);}
 @Transactional public void readAll(Long userId){user(userId);mapper.readAllChats(userId);mapper.readNotifications(userId);}
 @Transactional public void clear(Long userId,Long peerId){pair(userId,peerId);long last=mapper.latestId(userId,peerId);mapper.clear(userId,peerId,last);if(last>0)mapper.readChat(userId,peerId,last);}
 public void mute(Long userId,Long peerId,boolean muted){pair(userId,peerId);mapper.mute(userId,peerId,muted);}
 public List<Map<String,Object>> notifications(Long userId){user(userId);List<Map<String,Object>> rows=mapper.notifications(userId);for(Map<String,Object> row:rows)row.put("unread",row.get("readAt")==null);return rows;}
 private Map<String,Object> termFor(Long userId){User u=user(userId);Map<String,Object> term=mapper.term(u.getSchool());if(term==null)throw new IllegalArgumentException("该学校尚未配置学期");for(String key:List.of("termStart","termEnd","midtermStart","midtermEnd","finalStart"))term.put(key,date(term.get(key)).toString());return term;}
 private static LocalDate date(Object value){return LocalDate.parse(value.toString());}
 public Map<String,Object> timetable(Long userId,Integer week){Map<String,Object> term=termFor(userId);int current=Math.max(1,(int)ChronoUnit.WEEKS.between(date(term.get("termStart")).with(java.time.DayOfWeek.MONDAY),LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY))+1);int target=week==null?current:Math.max(1,Math.min(week,60));return Map.of("termInfo",term,"week",target,"courses",mapper.courses(userId,number(term.get("id")),target));}
 public Map<String,Object> calendar(Long userId){Map<String,Object> term=termFor(userId);List<Map<String,Object>> events=mapper.events(number(term.get("id")));for(Map<String,Object> event:events){event.put("start",date(event.get("start")).toString());event.put("end",date(event.get("end")).toString());}return Map.of("termInfo",term,"events",events);}

 // ---- 学生自建课表 ----
 // 学生自己维护课表（默认作息可在端上改、调休周自己标），不依赖管理员逐个录入。
 @Transactional public Map<String,Object> addUserCourse(Long userId,Map<String,Object> body){
   Map<String,Object> term=termFor(userId);
   String name=body.get("name")==null?"":String.valueOf(body.get("name")).trim();
   if(name.isEmpty()||name.length()>64)throw new IllegalArgumentException("课程名称须为1至64字");
   int day=(int)number(body.get("day"));
   if(day<0||day>6)throw new IllegalArgumentException("星期不合法（0=周一）");
   int period=(int)number(body.get("period"));
   if(period<0||period>9)throw new IllegalArgumentException("节次不合法（0=第一大节）");
   int weekStart=body.get("weekStart")==null?1:(int)number(body.get("weekStart"));
   int weekEnd=body.get("weekEnd")==null?21:(int)number(body.get("weekEnd"));
   if(weekStart<1||weekEnd<1||weekStart>60||weekEnd>60||weekStart>weekEnd)throw new IllegalArgumentException("周次范围不合法");
   String room=body.get("room")==null?"":String.valueOf(body.get("room")).trim();
   if(room.length()>64)throw new IllegalArgumentException("教室不能超过64字");
   String theme=body.get("theme")==null||String.valueOf(body.get("theme")).isBlank()?"blue":String.valueOf(body.get("theme")).trim();
   if(theme.length()>16)throw new IllegalArgumentException("配色不合法");
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("termId",number(term.get("id")));
   row.put("userId",userId);
   row.put("name",name);
   row.put("room",room);
   row.put("day",day);
   row.put("period",period);
   row.put("theme",theme);
   row.put("weekStart",weekStart);
   row.put("weekEnd",weekEnd);
   mapper.upsertCourse(row);
   // 同格同名视为更新；upsert 对更新路径不自增 id，按唯一键取回最终行
   return mapper.courseByKey(number(term.get("id")),userId,day,period,name);
 }

 // 删除自己的课程；courseById 先查存在，再用 user_id 条件删除防越权
 @Transactional public void removeUserCourse(Long userId,Long courseId){
   Map<String,Object> course=mapper.courseById(courseId);
   if(course==null)throw new IllegalArgumentException("课程不存在");
   if(number(course.get("userId"))!=userId)throw new IllegalArgumentException("只能删除自己的课程");
   if(mapper.deleteUserCourse(courseId,userId)!=1)throw new IllegalArgumentException("课程不存在或已被删除");
 }


 // ---- 课表批量导入（管理员）----
 // 各高校教务系统没有公开接口，各校字段也不统一，因此不做「假装对接」：
 // 改为管理员把教务系统导出的课表整理成 CSV 后批量导入，将来拿到真实接口只需替换数据来源。
 private static final List<String> DAY_NAMES=List.of("周一","周二","周三","周四","周五","周六","周日");
 // CSV 列：课程名,教室,星期,节次,起始周,结束周[,配色]
 public Map<String,Object> importTimetable(Long adminUserId,Long targetUserId,String school,String csv,boolean replace){
   if(csv==null||csv.isBlank())throw new IllegalArgumentException("导入内容为空");
   if(adminUserId==null||adminUserId<1)throw new IllegalArgumentException("缺少操作人");
   User target=user(targetUserId==null?adminUserId:targetUserId);
   String useSchool=school==null||school.isBlank()?target.getSchool():school.trim();
   Long termId=mapper.activeTermId(useSchool);
   if(termId==null)throw new IllegalArgumentException("该学校尚未配置学期，请先在 academic_term 添加当前学期");
   String[] lines=csv.split("\\r?\\n");
   List<Map<String,Object>> preview=new ArrayList<>();List<String> errors=new ArrayList<>();
   for(int i=0;i<lines.length;i++){
     String line=lines[i].trim();
     if(line.isEmpty()||line.startsWith("#"))continue;
     // 跳过表头
     if(i==0&&(line.contains("课程名")||line.toLowerCase().contains("name")))continue;
     String[] cells=line.split(",");
     if(cells.length<6){errors.add("第"+(i+1)+"行：字段不足（需 课程名,教室,星期,节次,起始周,结束周）");continue;}
     for(int c=0;c<cells.length;c++)cells[c]=cells[c].trim();
     if(cells[0].isEmpty()||cells[0].length()>64){errors.add("第"+(i+1)+"行：课程名称为空或超过64字");continue;}
     int day=dayIndex(cells[2]);
     if(day<0){errors.add("第"+(i+1)+"行：无法识别星期「"+cells[2]+"」");continue;}
     int period=parseIntSafe(cells[3],-1);
     if(period<0||period>9){errors.add("第"+(i+1)+"行：节次应为 0-9（0 表示第一大节）");continue;}
     int weekStart=parseIntSafe(cells[4],1),weekEnd=parseIntSafe(cells[5],21);
     if(weekStart<1||weekEnd<1||weekStart>60||weekEnd>60||weekStart>weekEnd){errors.add("第"+(i+1)+"行：周次范围不合法");continue;}
     Map<String,Object> row=new LinkedHashMap<>();
     row.put("termId",termId);row.put("userId",target.getId());row.put("name",cells[0]);
     row.put("room",cells[1].isEmpty()?"待定":(cells[1].length()>64?cells[1].substring(0,64):cells[1]));
     row.put("day",day);row.put("period",period);
     row.put("theme",cells.length>6&&!cells[6].isEmpty()?cells[6]:"blue");
     row.put("weekStart",weekStart);row.put("weekEnd",weekEnd);
     preview.add(row);
   }
   if(!errors.isEmpty())return Map.of("imported",0,"skipped",errors.size(),"errors",errors.subList(0,Math.min(errors.size(),20)),"preview",List.of());
   if(preview.isEmpty())throw new IllegalArgumentException("没有可导入的有效行");
   if(replace)mapper.clearCourses(termId,target.getId());
   for(Map<String,Object> row:preview)mapper.upsertCourse(row);
   return Map.of("imported",preview.size(),"skipped",0,"errors",List.of(),
     "total",mapper.countCourses(termId,target.getId()),"userId",target.getId(),"termId",termId,
     "preview",preview.subList(0,Math.min(preview.size(),5)));
 }
 private static int dayIndex(String text){
   if(text==null)return -1;String t=text.trim();
   if(t.isEmpty())return -1;
   int named=DAY_NAMES.indexOf(t.startsWith("星期")?"周"+t.substring(2):t);
   if(named>=0)return named;
   // 也接受数字 1-7（1 表示周一）
   int n=parseIntSafe(t,-1);
   if(n>=1&&n<=7)return n-1;
   return -1;
 }
    // 小程序选学校：公开列表，无需登录
    public List<Map<String, Object>> publicSchools() {
        return mapper.publicSchools();
    }

    // 小程序发帖选地点：按选中学校拉
    public List<Map<String, Object>> publicSchoolLocations(Long schoolId) {
        if (schoolId == null || schoolId < 1) return List.of();
        return mapper.publicSchoolLocations(schoolId);
    }

    private static int parseIntSafe(String text,int fallback){try{return Integer.parseInt(text.trim());}catch(Exception error){return fallback;}}
}
