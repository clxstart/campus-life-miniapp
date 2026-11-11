package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.RiderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rider")
@RequiredArgsConstructor
public class RiderController {
    private final RiderService service;

    @GetMapping("/stats")
    public ApiResponse<Map<String,Object>> stats(HttpServletRequest request) {
        return ApiResponse.ok(service.stats(schoolId(request), riderId(request)));
    }

    @GetMapping("/pool")
    public ApiResponse<List<Map<String,Object>>> pool(HttpServletRequest request) {
        return ApiResponse.ok(service.pool(schoolId(request)));
    }

    @PostMapping("/orders/{id}/grab")
    public ApiResponse<Object> grab(@PathVariable Long id, HttpServletRequest request) {
        service.grab(id, riderId(request), String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_NAME)));
        return ApiResponse.ok(Map.of("grabbed", true));
    }

    @GetMapping("/orders/mine")
    public ApiResponse<List<Map<String,Object>>> mine(HttpServletRequest request) {
        return ApiResponse.ok(service.mine(riderId(request)));
    }

    /** 承运骑手按单查看收件人完整电话；列表接口不再返回完整号码 */
    @GetMapping("/orders/{id}/contact-phone")
    public ApiResponse<Map<String,Object>> contactPhone(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(Map.of("contactPhone", service.revealPhone(id, riderId(request))));
    }

    @PostMapping("/orders/{id}/pick")
    public ApiResponse<Object> pick(@PathVariable Long id, HttpServletRequest request) {
        service.pick(id, riderId(request));
        return ApiResponse.ok(Map.of("picked", true));
    }

    @PostMapping("/orders/{id}/deliver")
    public ApiResponse<Object> deliver(@PathVariable Long id, HttpServletRequest request) {
        service.deliver(id, riderId(request));
        return ApiResponse.ok(Map.of("delivered", true));
    }

    @GetMapping("/orders/history")
    public ApiResponse<Map<String,Object>> history(@RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.history(riderId(request), startDate, endDate, page, size));
    }

    private static Long riderId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }
    private static Long schoolId(HttpServletRequest request) { return ((Number) request.getAttribute(AdminInterceptor.ADMIN_SCHOOL)).longValue(); }
}
