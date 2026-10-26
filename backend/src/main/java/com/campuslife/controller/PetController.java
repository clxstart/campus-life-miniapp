package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 宠物：接口全部属于「我的数据」，身份一律取 token（AuthInterceptor 已保证 /api/pet/** 带有效 token）。
 */
@RestController
@RequestMapping("/api/pet")
@RequiredArgsConstructor
public class PetController {
    private final PetService service;

    private static Long me(HttpServletRequest request, Long claimed) {
        Long id = CurrentUser.resolve(request, claimed, null);
        if (id == null || id < 1) throw new UnauthorizedException("请先登录");
        return id;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> pet(@RequestParam(required = false) Long userId, HttpServletRequest request) {
        return ApiResponse.ok(service.petOf(me(request, userId)));
    }

    @PostMapping("/feed")
    public ApiResponse<Map<String, Object>> feed(@RequestBody(required = false) Map<String, Long> body, HttpServletRequest request) {
        return ApiResponse.ok(service.interact(me(request, body == null ? null : body.get("userId")), "feed"));
    }

    @PostMapping("/touch")
    public ApiResponse<Map<String, Object>> touch(@RequestBody(required = false) Map<String, Long> body, HttpServletRequest request) {
        return ApiResponse.ok(service.interact(me(request, body == null ? null : body.get("userId")), "touch"));
    }

    @PostMapping("/checkin")
    public ApiResponse<Map<String, Object>> checkin(@RequestBody(required = false) Map<String, Long> body, HttpServletRequest request) {
        return ApiResponse.ok(service.interact(me(request, body == null ? null : body.get("userId")), "checkin"));
    }

    @PutMapping("/name")
    public ApiResponse<Object> rename(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long claimed = body.get("userId") instanceof Number n ? n.longValue() : null;
        service.rename(me(request, claimed), (String) body.get("name"));
        return ApiResponse.ok(null);
    }
}
