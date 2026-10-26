package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AdminPaymentService;
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
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {
    private final AdminPaymentService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, @RequestParam(defaultValue = "") String payStatus, @RequestParam(required = false) Long shopId, @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) { return ApiResponse.ok(service.list(school(request), role(request), payStatus, shopId, startDate, endDate, keyword, page, size)); }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, HttpServletRequest request) { return ApiResponse.ok(service.stats(school(request), role(request), startDate, endDate)); }

    @GetMapping("/{outTradeNo}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String outTradeNo, HttpServletRequest request) { return ApiResponse.ok(service.detail(outTradeNo, school(request), role(request))); }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, @RequestParam(defaultValue = "") String payStatus, @RequestParam(required = false) Long shopId, @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) { byte[] bytes = service.exportCsv(school(request), role(request), payStatus, shopId, startDate, endDate, keyword).getBytes(StandardCharsets.UTF_8); HttpHeaders headers = new HttpHeaders(); headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8)); headers.setContentDisposition(ContentDisposition.attachment().filename("支付流水.csv", StandardCharsets.UTF_8).build()); return ResponseEntity.ok().headers(headers).body(bytes); }

    private static Long school(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_SCHOOL); return v instanceof Number n ? n.longValue() : null; }
    private static String role(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_ROLE); return v == null ? "school" : String.valueOf(v); }
}
