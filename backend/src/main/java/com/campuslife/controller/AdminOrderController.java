package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AdminOrderService;
import com.campuslife.service.PayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrderController {
    private final AdminOrderService service;
    private final PayService payService;

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String status, @RequestParam(required = false) Long shopId, @RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) { return ApiResponse.ok(service.list(school(request), role(request), status, shopId, startDate, endDate, keyword, page, size)); }

    @PostMapping("/orders/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) { service.assertAccessible(id, school(request), role(request)); String target = String.valueOf(body.get("status")); if ("refunding".equals(target)) return ApiResponse.ok(payService.refundByAdmin(id, String.valueOf(body.getOrDefault("reason", "商家拒单")))); payService.transitionStatus(id, target); return ApiResponse.ok(payService.detail(id)); }

    @PostMapping("/orders/{id}/refund")
    public ApiResponse<Map<String, Object>> refund(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) { service.assertAccessible(id, school(request), role(request)); return ApiResponse.ok(payService.refundByAdmin(id, String.valueOf(body.getOrDefault("reason", "管理端退款")))); }

    @GetMapping("/orders/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String status, @RequestParam(required = false) Long shopId, @RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) { byte[] bytes = service.exportCsv(school(request), role(request), status, shopId, startDate, endDate, keyword).getBytes(StandardCharsets.UTF_8); HttpHeaders headers = new HttpHeaders(); headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8)); headers.setContentDisposition(ContentDisposition.attachment().filename("订单导出.csv", StandardCharsets.UTF_8).build()); return ResponseEntity.ok().headers(headers).body(bytes); }

    private static Long school(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_SCHOOL); return v instanceof Number n ? n.longValue() : null; }
    private static String role(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_ROLE); return v == null ? "school" : String.valueOf(v); }
}
