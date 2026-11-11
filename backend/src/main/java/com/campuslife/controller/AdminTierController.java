package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.TierService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 段位配置（管理端，只读）。
 *
 * 为什么只读：门槛一旦放开修改，已达成用户会被"改高后掉段"，争议成本高。
 * 要调门槛走代码发布，后台只用来核对线上生效值。
 */
@RestController
@RequestMapping("/api/admin/tier-config")
@RequiredArgsConstructor
public class AdminTierController {

    private final TierService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role)) throw new ForbiddenException("仅平台超管可查看段位配置");
        return ApiResponse.ok(service.configForAdmin());
    }
}