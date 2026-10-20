package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.dto.CreateOrderRequest;
import com.campuslife.entity.Shop;
import com.campuslife.service.BizService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BizController {
    private final BizService service;

    @GetMapping("/shops")
    public ApiResponse<List<Shop>> shops(@RequestParam(defaultValue = "") String category,
                                         @RequestParam(defaultValue = "local") String schoolScope,
                                         @RequestParam(required = false) Long schoolId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        return ApiResponse.ok(service.shops(category, schoolScope, schoolId, userId));
    }

    @GetMapping("/shops/{id}/menu")
    public ApiResponse<List<Map<String, Object>>> menu(@PathVariable Long id) { return ApiResponse.ok(service.menu(id)); }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> order(@Valid @RequestBody CreateOrderRequest request) { return ApiResponse.ok(service.order(request)); }

    @GetMapping("/goods")
    public ApiResponse<List<Map<String, Object>>> goods(@RequestParam(defaultValue = "1") Integer mode,
                                                        @RequestParam(defaultValue = "") String category,
                                                        @RequestParam(defaultValue = "local") String schoolScope,
                                                        @RequestParam(required = false) Long schoolId,
                                                        @RequestParam(defaultValue = "1") Long userId) {
        return ApiResponse.ok(service.goods(mode, category, schoolScope, schoolId, userId));
    }

    @GetMapping("/goods/{id}")
    public ApiResponse<Map<String, Object>> good(@PathVariable Long id) { return ApiResponse.ok(service.good(id)); }

    @PostMapping("/goods")
    public ApiResponse<Map<String, Object>> publishGood(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.publishGood(body)); }

    // 下架/删除自己发布的商品
    @DeleteMapping("/goods/{id}")
    public ApiResponse<Object> deleteGood(@PathVariable Long id, @RequestParam(defaultValue = "1") Long userId) {
        service.deleteGood(id, userId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs(@RequestParam(defaultValue = "") String settle,
                                                       @RequestParam(defaultValue = "") String keyword,
                                                       @RequestParam(defaultValue = "local") String schoolScope,
                                                       @RequestParam(required = false) Long schoolId,
                                                       @RequestParam(defaultValue = "1") Long userId) {
        return ApiResponse.ok(service.jobs(settle, keyword, schoolScope, schoolId, userId));
    }

    // 发布兼职：对应「发布」页的「兼职信息」选项
    @PostMapping("/jobs")
    public ApiResponse<Map<String, Object>> publishJob(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.publishJob(body)); }

    // 我发布的内容计数（帖子/闲置/兼职/活动）
    @GetMapping("/users/{id}/publish-count")
    public ApiResponse<Map<String, Object>> publishCount(@PathVariable Long id) { return ApiResponse.ok(service.myPublishCount(id)); }

    @GetMapping("/activities")
    public ApiResponse<List<Map<String, Object>>> activities(@RequestParam(defaultValue = "1") Long userId,
                                                             @RequestParam(defaultValue = "local") String schoolScope,
                                                             @RequestParam(required = false) Long schoolId) {
        return ApiResponse.ok(service.activities(userId, schoolScope, schoolId));
    }

    // 发布活动：对应「发布」页的「活动组队」选项
    @PostMapping("/activities")
    public ApiResponse<Map<String, Object>> publishActivity(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.publishActivity(body)); }

    @PostMapping("/activities/{id}/enroll")
    public ApiResponse<Map<String, Object>> enrollActivity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("用户不存在");
        Long userId;
        try { userId = Long.parseLong(String.valueOf(body.getOrDefault("userId", 1))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("用户不存在"); }
        return ApiResponse.ok(service.enrollActivity(id, userId));
    }
}
