package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.SystemStatusService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端「系统状态」页的数据来源。
 *
 * 为什么不让前端直接读 /actuator/**：actuator 的 metrics 里有大量内部细节，
 * 直接对外暴露不合适；而且它不走管理端鉴权。这里做一层收敛：
 * 只把「运维真正关心的几项」整理成固定结构返回，顺便带上业务侧计数。
 *
 * 鉴权：路径在 /api/admin/** 下，由 AdminInterceptor 强制校验管理员 token。
 */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class AdminSystemController {

    private final SystemStatusService service;

    /** 一次拿全：服务健康、JVM、数据库连接池、磁盘、业务计数、慢请求。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(HttpServletRequest request) {
        return ApiResponse.ok(service.overview());
    }
}
