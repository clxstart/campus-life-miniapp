package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.DormService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 宿舍域（用户侧）。
 *
 * 路径策略：一律不带 userId —— 只能操作自己的宿舍，传别人的 id 也没有入口。
 * 读接口在 AuthInterceptor 的私有读前缀里（未登录直接 401，而不是返回「未加入」）。
 */
@RestController
@RequestMapping("/api/dorm")
@RequiredArgsConstructor
public class DormController {

    private final DormService service;

    private static Long requireSelf(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return userId;
    }

    /** 我的宿舍：未加入 / 已加入两态；已解散返回 dissolved=true。 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        return ApiResponse.ok(service.me(requireSelf(request)));
    }

    /** 创建：{name, building, room}，需已认证。返回结构同 /me（含邀请码）。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        return ApiResponse.ok(service.create(requireSelf(request),
            str(input.get("name")), str(input.get("building")), str(input.get("room"))));
    }

    /** 邀请码加入：{inviteCode}。 */
    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> join(@RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        return ApiResponse.ok(service.join(requireSelf(request), str(input.get("inviteCode"))));
    }

    /** 退出。舍长（还有其他成员时）不能直接退出，需先转让或由管理员解散。 */
    @DeleteMapping("/leave")
    public ApiResponse<Object> leave(HttpServletRequest request) {
        service.leave(requireSelf(request));
        return ApiResponse.ok(null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}