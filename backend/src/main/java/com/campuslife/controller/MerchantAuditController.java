package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.MerchantService;
import com.campuslife.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/merchant-applies")
@RequiredArgsConstructor
public class MerchantAuditController {
    private static final Pattern PRIVATE_IMAGE = Pattern.compile("^/private/[a-f0-9]{32}\\.(jpg|jpeg|png|gif|webp)$");
    private final MerchantService service;
    private final UploadService uploadService;

    @GetMapping
    public ApiResponse<Map<String,Object>> applications(@RequestParam(defaultValue = "") String status,
                                                        @RequestParam(required = false) Long schoolId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(service.applications(role(request), schoolId(request), schoolId, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> application(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.application(id, role(request), schoolId(request)));
    }

    @GetMapping("/{id}/license-image")
    public ResponseEntity<FileSystemResource> licenseImage(@PathVariable Long id, HttpServletRequest request) {
        Map<String,Object> row = service.application(id, role(request), schoolId(request));
        String storedPath = String.valueOf(row.get("licenseImage"));
        if (!PRIVATE_IMAGE.matcher(storedPath).matches()) throw new IllegalArgumentException("营业执照图片路径无效");
        Path root = uploadService.privateRoot().toAbsolutePath().normalize();
        Path target = root.resolve(storedPath.substring("/private/".length())).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) throw new IllegalArgumentException("营业执照图片不存在");
        MediaType type = mediaType(target);
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=license-image")
            .body(new FileSystemResource(target));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String,Object>> approve(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.approve(id, adminId(request), role(request), schoolId(request)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Object> reject(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        service.reject(id, adminId(request), role(request), schoolId(request), body == null ? "" : String.valueOf(body.getOrDefault("reason", "")));
        return ApiResponse.ok(null);
    }

    private static String role(HttpServletRequest request) { return String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)); }
    private static Long schoolId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_SCHOOL); }
    private static Long adminId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }

    private static MediaType mediaType(Path file) {
        try {
            String type = Files.probeContentType(file);
            if (type != null && type.startsWith("image/")) return MediaType.parseMediaType(type);
        } catch (IOException ignored) { }
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }
}
