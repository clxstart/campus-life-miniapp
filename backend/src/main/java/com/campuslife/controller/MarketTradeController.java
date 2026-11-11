package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.MarketTradeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 二手成交（F10）。
 *
 * 权限：三个写接口都要求登录（AuthInterceptor 对 POST 默认强制 token），
 * 身份一律取 token（{@link CurrentUser}），不信任客户端传的 userId。
 * GET /api/market/trades/mine 是「我的数据」，读接口不在拦截器私有前缀里，
 * 所以在这里显式要求登录。
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketTradeController {

    private final MarketTradeService service;

    /** 买家点「我已买到」：发起待确认成交。 */
    @PostMapping("/{goodsId}/trade")
    public ApiResponse<Map<String, Object>> initiate(@PathVariable Long goodsId, HttpServletRequest request) {
        return ApiResponse.ok(service.initiate(goodsId, requireSelf(request)));
    }

    /** 卖家点「确认成交」：双方发分、商品置已售。 */
    @PostMapping("/trade/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.confirm(id, requireSelf(request)));
    }

    /** 买卖任一方取消待确认的成交。 */
    @PostMapping("/trade/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.cancel(id, requireSelf(request)));
    }

    /** 买家「我的成交」：刷新/重进商品页时恢复「等待卖家确认」；没有记录 data 返回 {}。 */
    @GetMapping("/{goodsId}/trade")
    public ApiResponse<Map<String, Object>> findMine(@PathVariable Long goodsId, HttpServletRequest request) {
        return ApiResponse.ok(service.findMine(goodsId, requireSelf(request)));
    }

    /** 卖家「我的发布」：商品 + 最近一笔成交（供「待确认成交」角标/按钮）。 */
    @GetMapping("/trades/mine")
    public ApiResponse<Map<String, Object>> mine(HttpServletRequest request) {
        return ApiResponse.ok(service.mine(requireSelf(request)));
    }

    private static Long requireSelf(HttpServletRequest request) {
        Long userId = CurrentUser.optional(request);
        if (userId == null) throw new ForbiddenException("请先登录");
        return userId;
    }
}