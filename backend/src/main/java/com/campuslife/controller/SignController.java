package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.SignService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全站签到。路径挂在 /api/points 下，但单独一个 Controller：
 * 签到有自己的事务与连签逻辑，和积分查询不是同一类职责。
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class SignController {

    private final SignService service;

    @PostMapping("/sign")
    public ApiResponse<Map<String, Object>> sign(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return ApiResponse.ok(service.sign(userId));
    }
}