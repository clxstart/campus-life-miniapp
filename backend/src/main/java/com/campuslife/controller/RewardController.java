package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.RewardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 积分兑换（用户侧）：商品池与兑换。 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class RewardController {

    private final RewardService service;

    private static Long requireSelf(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return userId;
    }

    @GetMapping("/rewards")
    public ApiResponse<Map<String, Object>> rewards(HttpServletRequest request) {
        return ApiResponse.ok(service.rewards(requireSelf(request)));
    }

    @PostMapping("/redeem")
    public ApiResponse<Map<String, Object>> redeem(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        String itemKey = body == null || body.get("itemKey") == null ? "" : String.valueOf(body.get("itemKey")).trim();
        if (itemKey.isEmpty()) throw new IllegalArgumentException("请选择要兑换的商品");
        return ApiResponse.ok(service.redeem(requireSelf(request), itemKey));
    }
}