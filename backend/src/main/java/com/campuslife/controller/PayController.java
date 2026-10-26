package com.campuslife.controller;

import com.campuslife.config.AuthInterceptor;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PayController {
    private final PayService payService;

    @PostMapping("/orders/{id}/pay")
    public ApiResponse<Map<String, Object>> pay(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(payService.createPayment(id, requestUserId(request)));
    }

    @PostMapping("/pay/notify")
    public ResponseEntity<String> notify(HttpServletRequest request) throws Exception {
        String body;
        try (BufferedReader reader = request.getReader()) { body = reader.lines().collect(Collectors.joining("\n")); }
        boolean ok = payService.handleNotify(body, request.getHeader("Wechatpay-Timestamp"), request.getHeader("Wechatpay-Nonce"), request.getHeader("Wechatpay-Signature"), request.getHeader("Wechatpay-Serial"));
        return ResponseEntity.ok(ok ? "{\"code\":\"SUCCESS\",\"message\":\"成功\"}" : "{\"code\":\"FAIL\",\"message\":\"验签失败\"}");
    }

    // 订单详情统一由 BizController#myOrder 提供（GET /api/orders/{id}），这里不再重复映射，避免 Ambiguous mapping。

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) { return ApiResponse.ok(payService.cancel(id, requestUserId(request), body == null ? "" : String.valueOf(body.getOrDefault("reason", "")))); }

    @PostMapping("/orders/{id}/refund")
    public ApiResponse<Map<String, Object>> refund(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) { return ApiResponse.ok(payService.refund(id, requestUserId(request), body == null ? "" : String.valueOf(body.getOrDefault("reason", "")))); }

    /**
     * 取下单人身份：必须已登录。
     *
     * 之前这里可能返回 null，而 PayService 里的归属校验写成 `if (userId != null && !userId.equals(...))`，
     * 于是「不带 token」正好让校验整体短路 —— 任何人可以支付/取消/退款别人的订单。
     * 现在没有身份直接 401，服务层再把 null 当异常处理，两层都堵。
     */
    private static Long requestUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.USER_ID);
        if (value instanceof Number n && n.longValue() > 0) return n.longValue();
        throw new UnauthorizedException("请先登录后操作订单");
    }
}
