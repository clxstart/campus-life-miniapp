package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PointsService;
import com.campuslife.service.SignService;
import com.campuslife.service.TierService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 我的积分：余额 / 任务 / 明细。
 *
 * 只有本人能看，路径一律不带 userId —— 传别人的 id 也不该读到别人的账。
 *
 * /me 一次返回段位、签到状态与特权：小程序首屏不为了这三个字段多发请求。
 * 组合放在 Controller 而不是 PointsService，避免积分服务反向依赖签到/段位服务形成环。
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService service;
    private final TierService tierService;
    private final SignService signService;

    private static Long requireSelf(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return userId;
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Long userId = requireSelf(request);
        Map<String, Object> out = service.me(userId);
        // 段位：由 gTotal 推导；/me 里已有的 gTotal 与段位服务读的是同一张 user_growth
        out.put("tier", tierService.forGrowth(PointsService.number(out.get("gTotal"))));
        // 签到：已签/连签/下一档
        Map<String, Object> sign = signService.status(userId);
        out.put("signedToday", sign.get("signedToday"));
        out.put("streakDays", sign.get("streakDays"));
        out.put("nextMilestone", sign.get("nextMilestone"));
        // 段位特权：三项开关的真实判定结果
        out.put("privileges", tierService.privileges(userId));
        return ApiResponse.ok(out);
    }

    @GetMapping("/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks(HttpServletRequest request) {
        return ApiResponse.ok(service.tasks(requireSelf(request)));
    }

    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.logs(requireSelf(request), page, size));
    }
}