package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PointsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 积分任务配置（管理端）。
 *
 * 任务目录是全平台一套：改一个 P 值影响所有学校的用户，所以只有 super 能改。
 * 校级管理员只能看，不能改 —— 否则一个校管手滑就能让全平台发分翻倍。
 */
@RestController
@RequestMapping("/api/admin/task-catalog")
@RequiredArgsConstructor
public class AdminTaskCatalogController {

    private final PointsService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        requireSuper(request);
        return ApiResponse.ok(service.catalogForAdmin());
    }

    @PutMapping("/{taskKey}")
    public ApiResponse<Object> update(@PathVariable String taskKey,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        requireSuper(request);
        if (body == null) throw new IllegalArgumentException("没有要保存的配置");
        service.updateTask(
            taskKey,
            intOrNull(body.get("p")),
            intOrNull(body.get("g")),
            intOrNull(body.get("dailyCap")),
            boolOrNull(body.get("enabled"))
        );
        return ApiResponse.ok(null);
    }

    private static void requireSuper(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role)) throw new ForbiddenException("仅平台超管可修改任务配置");
    }

    private static Integer intOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("数值格式不正确"); }
    }

    private static Boolean boolOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        String v = String.valueOf(value).trim();
        if (v.isEmpty()) return null;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }
}