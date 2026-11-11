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
 * 用户侧的认证状态查询与自助提交。
 *
 * 只暴露真实状态：没有核验记录就是「未认证」，绝不返回伪造的"已认证"。
 *
 * 权限：
 *   GET /me     登录本人
 *   GET /config 公开（不登录也能读，只在虚拟页渲染表单要用）
 *   POST /apply 登录本人
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

    /**
     * 认证配置：要填哪几项、学号正则、审核方式、每日次数。
     *
     * 公开读：小程序认证页要在登录前后都能渲染表单结构；
     * 里面没有任何敏感信息（不含审核人、不含别人的材料），公开没有风险。
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(service.config());
    }

    /**
     * 提交认证：{schoolId, college, realName, studentNo, phone}。
     *
     * 校验失败一律 400 + 中文提示（由 GlobalExceptionHandler 统一包装），
     * 前端把这些提示显示在对应字段下方，不弹窗。
     */
    @PostMapping("/apply")
    public ApiResponse<Map<String, Object>> apply(@RequestBody(required = false) Map<String, Object> body,
                                                  HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        Map<String, Object> input = body == null ? Map.of() : body;
        return ApiResponse.ok(service.apply(
            userId,
            longOf(input.get("schoolId")),
            text(input.get("college")),
            text(input.get("realName")),
            text(input.get("studentNo")),
            text(input.get("phone"))
        ));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { return null; }
    }
}