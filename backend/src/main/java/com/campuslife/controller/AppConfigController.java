package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AppConfigService;
import jakarta.servlet.http.HttpServletRequest;
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
 * 管理端读写：**仅超级管理员**。
 *
 * 为什么这里要自己拦 super：AdminInterceptor 只要求「super 或 school」身份，
 * 而 app_config 是平台级配置（没有学校维度）。以前这里只有二维码和文案，校管误改后果有限；
 * V3.1 装上「学号正则」「审核方式」「每日次数」之后，任何一个校管都能改掉全平台的认证规则 ——
 * 那不是一个学校能决定的事。Web 路由上的 meta.superOnly 只是前端隐藏，后端必须自己拦。
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

    /** 管理端：全部配置项（带说明）。含学号正则与审核方式，同样只给超管看。 */
    @GetMapping("/admin/app-config")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        requireSuper(request);
        return ApiResponse.ok(service.listAll());
    }

    /** 管理端：批量保存。只允许白名单 key。 */
    @PutMapping("/admin/app-config")
    public ApiResponse<Object> save(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        requireSuper(request);
        service.update(body);
        return ApiResponse.ok(null);
    }

    /** 照抄 AdminFeatureFlagController 的写法：平台级配置只认 super。 */
    private static void requireSuper(HttpServletRequest request) {
        if (!"super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)))) {
            throw new ForbiddenException("仅超级管理员可以修改全平台配置");
        }
    }
}

