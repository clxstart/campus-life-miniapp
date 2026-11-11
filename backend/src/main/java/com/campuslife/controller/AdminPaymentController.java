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

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
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

    /**
     * 支付流水导出（finding-06）：先按同一筛选口径计数，超过上限返回 413 JSON 错误（不生成被截断的文件）；
     * 未超限时以 500 行一批流式写出，响应头 X-Export-Row-Count 给出总行数供前端核对。
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(defaultValue = "") String startDate, @RequestParam(defaultValue = "") String endDate, @RequestParam(defaultValue = "") String payStatus, @RequestParam(required = false) Long shopId, @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) {
        Long schoolId = school(request);
        String role = role(request);
        long total = service.exportCount(schoolId, role, payStatus, shopId, startDate, endDate, keyword);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("支付流水.csv", StandardCharsets.UTF_8).build());
        headers.set("X-Export-Row-Count", String.valueOf(total));
        headers.setAccessControlExposeHeaders(java.util.List.of("X-Export-Row-Count", HttpHeaders.CONTENT_DISPOSITION));
        StreamingResponseBody body = out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            service.exportCsv(schoolId, role, payStatus, shopId, startDate, endDate, keyword, writer);
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @ExceptionHandler(AdminPaymentService.ExportTooLargeException.class)
    public ResponseEntity<ApiResponse<Object>> exportTooLarge(AdminPaymentService.ExportTooLargeException e) {
        return ResponseEntity.status(413).body(new ApiResponse<>(413, e.getMessage(), null));
    }

    private static Long school(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_SCHOOL); return v instanceof Number n ? n.longValue() : null; }
    private static String role(HttpServletRequest r) { Object v = r.getAttribute(AdminInterceptor.ADMIN_ROLE); return v == null ? "school" : String.valueOf(v); }
}
