package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.FeatureFlags;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端：功能开关。
 *
 * 用途：线上某个功能出问题时，在这里点一下关闭即可止血，不用改代码、不用重启、不用发版。
 * 关闭后用户看到的是该功能的「维护中」提示，其他功能不受影响。
 *
 * 鉴权：路径在 /api/admin/** 下，由 AdminInterceptor 强制校验管理员 token。
 */
@RestController
@RequestMapping("/api/admin/feature-flags")
@RequiredArgsConstructor
public class AdminFeatureFlagController {

    private final FeatureFlags featureFlags;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(featureFlags.list());
    }

    @PutMapping("/{key}")
    public ApiResponse<Object> update(@PathVariable String key,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Object raw = input.get("enabled");
        boolean enabled = raw instanceof Boolean b ? b
            : raw instanceof Number n ? n.intValue() != 0
            : Boolean.parseBoolean(String.valueOf(raw));
        String remark = input.get("remark") == null ? "" : String.valueOf(input.get("remark"));
        Object adminName = request.getAttribute(AdminInterceptor.ADMIN_NAME);
        Object adminId = request.getAttribute(AdminInterceptor.ADMIN_ID);
        String operator = adminName != null ? String.valueOf(adminName)
            : adminId != null ? "admin#" + adminId : "admin";
        featureFlags.update(key, enabled, remark, operator);
        return ApiResponse.ok(null);
    }
}
