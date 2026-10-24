package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {
    private final MerchantService service;

    @PostMapping("/apply")
    public ApiResponse<Map<String,Object>> submit(@RequestBody Map<String,Object> body) {
        return ApiResponse.ok(service.submit(body));
    }

    @GetMapping("/home")
    public ApiResponse<Map<String,Object>> home(HttpServletRequest request) {
        return ApiResponse.ok(service.dashboard(shopId(request)));
    }

    @GetMapping("/shop")
    public ApiResponse<Map<String,Object>> shop(HttpServletRequest request) {
        return ApiResponse.ok(service.shop(shopId(request)));
    }

    @PutMapping("/shop")
    public ApiResponse<Object> updateShop(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        service.updateShop(shopId(request), body);
        return ApiResponse.ok(null);
    }

    @GetMapping("/goods")
    public ApiResponse<List<Map<String,Object>>> goods(HttpServletRequest request) {
        return ApiResponse.ok(service.goods(shopId(request)));
    }

    @PostMapping("/goods")
    public ApiResponse<Map<String,Object>> createGoods(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.createGoods(shopId(request), body));
    }

    @PutMapping("/goods/{id}")
    public ApiResponse<Object> updateGoods(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        service.updateGoods(shopId(request), id, body);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/goods/{id}")
    public ApiResponse<Object> deleteGoods(@PathVariable Long id, HttpServletRequest request) {
        service.deleteGoods(shopId(request), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/goods/{id}/status")
    public ApiResponse<Object> goodsStatus(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        service.goodsStatus(shopId(request), id, intOr(body == null ? null : body.get("status"), 0));
        return ApiResponse.ok(null);
    }

    @PostMapping("/goods/status/batch")
    public ApiResponse<Object> goodsStatusBatch(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        Object ids = body == null ? null : body.get("ids");
        service.goodsStatusBatch(shopId(request), ids instanceof List<?> values ? values : null,
            intOr(body == null ? null : body.get("status"), 0));
        return ApiResponse.ok(null);
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String,Object>> orders(@RequestParam(defaultValue = "") String status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(service.orders(shopId(request), status, page, size));
    }

    @PostMapping("/orders/{id}/accept")
    public ApiResponse<Object> accept(@PathVariable Long id, HttpServletRequest request) {
        service.accept(shopId(request), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/orders/{id}/reject")
    public ApiResponse<Map<String,Object>> reject(@PathVariable Long id, @RequestBody(required = false) Map<String,Object> body,
                                                  HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(service.rejectOrder(shopId(request), id, reason));
    }

    @PostMapping("/orders/{id}/complete")
    public ApiResponse<Object> complete(@PathVariable Long id, HttpServletRequest request) {
        service.complete(shopId(request), id);
        return ApiResponse.ok(null);
    }

    private static Long shopId(HttpServletRequest request) {
        return (Long) request.getAttribute(AdminInterceptor.MERCHANT_SHOP);
    }

    private static int intOr(Object value, int fallback) {
        try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (Exception error) { return fallback; }
    }
}
