package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.*;
import com.campuslife.service.CircleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CircleController {
    private final CircleService service;

    @GetMapping("/circles")
    public ApiResponse<Map<String, Object>> circles(@RequestParam(defaultValue = "1") Long userId,
                                                    @RequestParam(defaultValue = "") String category,
                                                    @RequestParam(defaultValue = "false") boolean hot,
                                                    @RequestParam(defaultValue = "local") String schoolScope,
                                                    @RequestParam(required = false) Long schoolId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(service.circles(Math.max(CurrentUser.resolve(request, userId), 1), category, hot,
            Math.max(page, 1), Math.min(Math.max(size, 1), 50), schoolScope, schoolId));
    }

    @GetMapping("/circles/hot")
    public ApiResponse<List<Map<String, Object>>> hot(@RequestParam(defaultValue = "1") Long userId,
                                                      @RequestParam(defaultValue = "5") int limit,
                                                      @RequestParam(defaultValue = "local") String schoolScope,
                                                      @RequestParam(required = false) Long schoolId,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.hot(Math.max(CurrentUser.resolve(request, userId), 1), limit, schoolScope, schoolId));
    }

    @GetMapping("/circles/categories")
    public ApiResponse<List<Map<String, Object>>> categories() { return ApiResponse.ok(service.categories()); }

    // 推荐流：兴趣（已加入圈子）+ 热度 + 时效，排序在 SQL 内完成
    @GetMapping("/recommend")
    public ApiResponse<Map<String, Object>> recommend(@RequestParam(defaultValue = "1") Long userId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.recommend(Math.max(CurrentUser.resolve(request, userId), 1),
            Math.max(page, 1), Math.min(Math.max(size, 1), 50)));
    }

    @GetMapping("/circles/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "1") Long userId,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.detail(id, Math.max(CurrentUser.resolve(request, userId), 1)));
    }

    @GetMapping("/circles/{id}/posts")
    public ApiResponse<Map<String, Object>> posts(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "1") Long userId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(service.posts(id, Math.max(CurrentUser.resolve(request, userId), 1),
            Math.max(page, 1), Math.min(Math.max(size, 1), 50)));
    }

    @PostMapping("/circles/{id}/join")
    public ApiResponse<Map<String, Object>> join(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Long> r,
                                                 HttpServletRequest request) {
        Long claimed = r == null ? null : r.get("userId");
        return ApiResponse.ok(service.join(id, CurrentUser.resolve(request, claimed)));
    }

    @PostMapping("/circles/{id}/leave")
    public ApiResponse<Map<String, Object>> leave(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, Long> r,
                                                  HttpServletRequest request) {
        Long claimed = r == null ? null : r.get("userId");
        return ApiResponse.ok(service.leave(id, CurrentUser.resolve(request, claimed)));
    }
}
