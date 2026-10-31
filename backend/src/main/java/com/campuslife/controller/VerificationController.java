package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户侧的认证状态查询。
 *
 * 只暴露真实状态：没有核验记录就是「未认证」，绝不返回伪造的"已认证"。
 */
@RestController
@RequestMapping("/api/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService service;

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return ApiResponse.ok(service.status(userId));
    }
}
