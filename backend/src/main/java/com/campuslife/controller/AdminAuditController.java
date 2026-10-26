package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuditController {
    private final AuditService auditService;

    @GetMapping("/audit/list")
    public ApiResponse<Map<String,Object>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(auditService.list(type, status, keyword, startDate, endDate, page, size, scope(request)));
    }

    @GetMapping("/audit/stats")
    public ApiResponse<Map<String,Object>> stats(HttpServletRequest request) {
        return ApiResponse.ok(auditService.stats(scope(request)));
    }

    @GetMapping("/audit/detail/{type}/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable String type, @PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(auditService.detail(type, id, scope(request)));
    }

    @PostMapping("/audit/{type}/{id}/pass")
    public ApiResponse<Object> pass(@PathVariable String type, @PathVariable Long id, HttpServletRequest request) {
        auditService.pass(type, id, scope(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/audit/{type}/{id}/reject")
    public ApiResponse<Object> reject(@PathVariable String type, @PathVariable Long id,
                                      @RequestBody Map<String,Object> body, HttpServletRequest request) {
        auditService.reject(type, id, scope(request), text(body.get("reason")));
        return ApiResponse.ok(null);
    }

    @PostMapping("/audit/{type}/{id}/restore")
    public ApiResponse<Object> restore(@PathVariable String type, @PathVariable Long id, HttpServletRequest request) {
        auditService.restore(type, id, scope(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/sensitive-words")
    public ApiResponse<Map<String,Object>> words(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.words(category, keyword, page, size));
    }

    @PostMapping("/sensitive-words")
    public ApiResponse<Map<String,Object>> addWord(@RequestBody Map<String,Object> body) {
        return ApiResponse.ok(auditService.addWord(text(body.get("word")), textOr(body.get("category"), "custom")));
    }

    @PutMapping("/sensitive-words/{id}")
    public ApiResponse<Object> updateWord(@PathVariable Long id, @RequestBody Map<String,Object> body) {
        auditService.updateWord(id, text(body.get("word")), textOr(body.get("category"), "custom"), bool(body.get("enabled")));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/sensitive-words/{id}")
    public ApiResponse<Object> deleteWord(@PathVariable Long id) {
        auditService.deleteWord(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/sensitive-words/import")
    public ApiResponse<Map<String,Object>> importWords(@RequestBody Map<String,Object> body) {
        Object values = body.get("words");
        if (!(values instanceof List<?> raw)) throw new IllegalArgumentException("请提供词语列表");
        List<String> parsed = raw.stream().map(String::valueOf).toList();
        return ApiResponse.ok(auditService.importWords(parsed, textOr(body.get("category"), "custom")));
    }

    /**
     * 敏感词检测试接口（联调 / 回归用）
     *
     * 传入一段文本，返回命中的敏感词列表，便于验证词表与引擎行为。
     * 只读操作，不落库。
     */
    @PostMapping("/sensitive-words/check")
    public ApiResponse<Map<String,Object>> checkText(@RequestBody Map<String,Object> body) {
        String value = text(body.get("text"));
        java.util.List<String> hits = auditService.matchWords(value);
        return ApiResponse.ok(Map.of("text", value, "hits", hits, "hit", !hits.isEmpty()));
    }

    private static Long scope(HttpServletRequest request) {
        if ("super".equals(request.getAttribute(AdminInterceptor.ADMIN_ROLE))) return null;
        Object value = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        if (value instanceof Number number) return number.longValue();
        throw new ForbiddenException("管理员学校范围未配置");
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String textOr(Object value, String fallback) { String result = text(value).trim(); return result.isEmpty() ? fallback : result; }
    private static boolean bool(Object value) { return value instanceof Boolean b ? b : "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)); }
}
