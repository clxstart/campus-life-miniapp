package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.RewardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 兑换商品池（管理端）。
 *
 * 商品池是全平台一套，改价格/库存影响所有学校，只有 super 能改；
 * 校级管理员连看都不给 —— 与任务目录同一口径。
 */
@RestController
@RequestMapping("/api/admin/reward-item")
@RequiredArgsConstructor
public class AdminRewardController {

    private final RewardService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        requireSuper(request);
        return ApiResponse.ok(service.adminList());
    }

    @PutMapping("/{itemKey}")
    public ApiResponse<Object> update(@PathVariable String itemKey,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        requireSuper(request);
        service.updateItem(itemKey, body);
        return ApiResponse.ok(null);
    }

    private static void requireSuper(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role)) throw new ForbiddenException("仅平台超管可修改兑换商品池");
    }
}