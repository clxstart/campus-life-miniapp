package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.*;
import com.campuslife.service.CampusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CampusController {
    private final CampusService service;

    @GetMapping("/schools")
    public ApiResponse<List<Map<String, Object>>> schools() { return ApiResponse.ok(service.publicSchools()); }

    @GetMapping("/schools/{id}/locations")
    public ApiResponse<List<Map<String, Object>>> schoolLocations(@PathVariable Long id) {
        return ApiResponse.ok(service.publicSchoolLocations(id));
    }

    // ---- 以下全部是「我的数据」，AuthInterceptor 保证带有效 token ----
    @GetMapping("/conversations")
    public ApiResponse<List<Map<String, Object>>> conversations(@RequestParam(defaultValue = "1") Long userId,
                                                                HttpServletRequest request) {
        return ApiResponse.ok(service.conversations(CurrentUser.resolve(request, userId)));
    }

    @GetMapping("/conversations/{peerId}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable Long peerId,
                                                     @RequestParam(defaultValue = "1") Long userId,
                                                     @RequestParam(required = false) Long beforeId,
                                                     @RequestParam(defaultValue = "30") int size,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(service.messages(CurrentUser.resolve(request, userId), peerId, beforeId,
            Math.min(Math.max(size, 1), 50)));
    }

    @PostMapping("/conversations/{peerId}/messages")
    public ApiResponse<Map<String, Object>> send(@PathVariable Long peerId,
                                                 @Valid @RequestBody SendMessageRequest r,
                                                 HttpServletRequest request) {
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        return ApiResponse.ok(service.send(peerId, r));
    }

    @PostMapping("/conversations/{peerId}/read")
    public ApiResponse<Object> read(@PathVariable Long peerId, @RequestBody Map<String, Long> r, HttpServletRequest request) {
        service.readChat(CurrentUser.resolve(request, r.get("userId")), peerId, r.get("upToId"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/conversations/{peerId}/clear")
    public ApiResponse<Object> clear(@PathVariable Long peerId, @RequestBody Map<String, Long> r, HttpServletRequest request) {
        service.clear(CurrentUser.resolve(request, r.get("userId")), peerId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/conversations/{peerId}/mute")
    public ApiResponse<Object> mute(@PathVariable Long peerId,
                                    @RequestParam(defaultValue = "1") Long userId,
                                    @RequestParam boolean muted,
                                    HttpServletRequest request) {
        service.mute(CurrentUser.resolve(request, userId), peerId, muted);
        return ApiResponse.ok(null);
    }

    @GetMapping("/notifications")
    public ApiResponse<List<Map<String, Object>>> notifications(@RequestParam(defaultValue = "1") Long userId,
                                                                HttpServletRequest request) {
        return ApiResponse.ok(service.notifications(CurrentUser.resolve(request, userId)));
    }

    @PostMapping("/messages/read-all")
    public ApiResponse<Object> readAll(@RequestBody Map<String, Long> r, HttpServletRequest request) {
        service.readAll(CurrentUser.resolve(request, r.get("userId")));
        return ApiResponse.ok(null);
    }

    @GetMapping("/timetable")
    public ApiResponse<Map<String, Object>> timetable(@RequestParam(defaultValue = "1") Long userId,
                                                      @RequestParam(required = false) Integer week,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.timetable(CurrentUser.resolve(request, userId), week));
    }

    // 校历接口（/api/calendar）已随「校历」功能下线移除

    // 学生自建课程：写入自己名下（挂当前学期），同格同名视为更新
    @PostMapping("/timetable/courses")
    public ApiResponse<Map<String, Object>> addCourse(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long claimed = body.get("userId") instanceof Number n ? n.longValue() : null;
        return ApiResponse.ok(service.addUserCourse(CurrentUser.resolve(request, claimed), body));
    }

    // 删除自己的课程
    @DeleteMapping("/timetable/courses/{id}")
    public ApiResponse<Object> deleteCourse(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") Long userId,
                                            HttpServletRequest request) {
        service.removeUserCourse(CurrentUser.resolve(request, userId), id);
        return ApiResponse.ok(null);
    }

    // 课表批量导入：各校教务系统无公开接口，改为管理员整理 CSV 后导入；将来可替换数据来源。
    // 注意：本接口挂在 /api/admin/** 下，已由 AdminInterceptor 校验管理员 token。
    @PostMapping("/admin/timetable/import")
    public ApiResponse<Map<String, Object>> importTimetable(@RequestBody Map<String, Object> body) {
        Long admin = body.get("adminUserId") instanceof Number n ? n.longValue() : 1L;
        Long target = body.get("userId") instanceof Number n2 ? n2.longValue() : admin;
        String school = body.get("school") instanceof String s ? s : null;
        String csv = body.get("csv") instanceof String c ? c : null;
        boolean replace = Boolean.TRUE.equals(body.get("replace"));
        return ApiResponse.ok(service.importTimetable(admin, target, school, csv, replace));
    }
}
