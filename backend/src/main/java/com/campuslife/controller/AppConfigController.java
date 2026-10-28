package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运营配置。
 *
 * 公开读：只暴露白名单里的几项（咨询二维码/微信号/文案），
 * 用于小程序「论文指导咨询」页展示 —— 不登录也能看，方便新用户直接联系。
 *
 * 管理端写：需要管理员 token，路径在 /api/admin/** 下由 AdminInterceptor 校验。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AppConfigController {

    private final AppConfigService service;

    /** 公开：一对一咨询入口配置（论文指导）。 */
    @GetMapping("/contact-config")
    public ApiResponse<Map<String, String>> contact() {
        return ApiResponse.ok(service.publicContact());
    }

    /** 公开：微信群入口配置（我的 → 微信群聊）。 */
    @GetMapping("/group-config")
    public ApiResponse<Map<String, String>> group() {
        return ApiResponse.ok(service.publicGroup());
    }

    /** 管理端：全部配置项（带说明）。 */
    @GetMapping("/admin/app-config")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.listAll());
    }

    /** 管理端：批量保存。只允许白名单 key。 */
    @PutMapping("/admin/app-config")
    public ApiResponse<Object> save(@RequestBody(required = false) Map<String, Object> body) {
        service.update(body);
        return ApiResponse.ok(null);
    }
}

