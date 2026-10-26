package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.*;
import com.campuslife.service.RecommendService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendController {
    private final RecommendService service;

    // 首页今日推荐：外卖最火 + 二手最火；batch 用于「换一批」轮换
    @GetMapping("/recommend/home")
    public ApiResponse<Map<String, Object>> home(@RequestParam(defaultValue = "1") Long userId,
                                                 @RequestParam(defaultValue = "0") int batch,
                                                 @RequestParam(defaultValue = "1") int perType,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.home(CurrentUser.resolve(request, userId), Math.max(batch, 0),
            Math.min(Math.max(perType, 1), 10)));
    }

    // 二手商品收藏（真实热度来源之一）：收藏人取登录身份
    @PostMapping("/goods/{id}/favorite")
    public ApiResponse<Map<String, Object>> favorite(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Long> r,
                                                     HttpServletRequest request) {
        Long claimed = r != null ? r.get("userId") : null;
        return ApiResponse.ok(service.toggleGoodsFavorite(id, CurrentUser.resolve(request, claimed, null)));
    }

    // 商品浏览上报：打开详情页时调用，view_count +1
    @PostMapping("/goods/{id}/view")
    public ApiResponse<Object> view(@PathVariable Long id) { service.viewGoods(id); return ApiResponse.ok(null); }

    // 私聊卖家上报：contact_count +1
    @PostMapping("/goods/{id}/contact")
    public ApiResponse<Object> contact(@PathVariable Long id) { service.contactGoods(id); return ApiResponse.ok(null); }

    // 店铺浏览上报
    @PostMapping("/shops/{id}/view")
    public ApiResponse<Object> viewShop(@PathVariable Long id) { service.viewShop(id); return ApiResponse.ok(null); }
}
