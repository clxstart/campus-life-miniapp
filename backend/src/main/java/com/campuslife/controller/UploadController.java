package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.*;
import com.campuslife.service.FeatureFlags;
import com.campuslife.service.RateLimiter;
import com.campuslife.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class UploadController {
    private final UploadService service;
    private final RateLimiter limiter;
    private final FeatureFlags flags;

    /** 上传开关：关闭后统一返回可读提示，而不是 500 或静默失败。 */
    private void requireUploadEnabled() {
        if (!flags.isEnabled("upload.enabled")) {
            throw new IllegalArgumentException(flags.disabledMessage("upload.enabled", "上传功能维护中，请稍后再试"));
        }
    }

    // 单图上传：小程序 wx.uploadFile 使用 name="file"。
    // 刻意保持无鉴权 —— 小程序要支持「未登录也能演示」，收紧会破坏既有行为。
    // 但加了频率限制，防止脚本刷爆磁盘。
    @PostMapping("/upload")
    public ApiResponse<Map<String,Object>> upload(@RequestParam("file") MultipartFile file,
                                                  HttpServletRequest request) {
        requireUploadEnabled();
        limiter.acquire(RateLimiter.UPLOAD, clientKey(request));
        return ApiResponse.ok(service.store(file));
    }

    // 管理端上传：路径落在 /api/admin/** 下，由 AdminInterceptor 强制校验 token（无 token 直接 401）。
    // 管理端一律用这个接口，不要用上面那个公开接口。
    @PostMapping("/admin/upload")
    public ApiResponse<Map<String,Object>> adminUpload(@RequestParam("file") MultipartFile file,
                                                       HttpServletRequest request) {
        requireUploadEnabled();
        limiter.acquire(RateLimiter.UPLOAD, adminKey(request));
        return ApiResponse.ok(service.store(file));
    }

    // 私有上传：学生证等敏感图片。存到 uploads/private/，不走 /uploads/** 静态映射，
    // 因此无法被直接访问；需要时由带鉴权的接口读取。
    @PostMapping("/admin/upload/private")
    public ApiResponse<Map<String,Object>> adminUploadPrivate(@RequestParam("file") MultipartFile file,
                                                              HttpServletRequest request) {
        requireUploadEnabled();
        limiter.acquire(RateLimiter.UPLOAD, privateUploadKey(request));
        return ApiResponse.ok(service.storePrivate(file));
    }

    // 孤儿文件清理：默认只预览，传 dryRun=false 才真的删。
    @GetMapping("/admin/uploads/orphans")
    public ApiResponse<Map<String,Object>> orphans(@RequestParam(defaultValue = "true") boolean dryRun,
                                                   @RequestParam(defaultValue = "7") int olderThanDays,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.cleanOrphans(dryRun, olderThanDays));
    }

    /** Authenticated staff may use a client label; anonymous uploads use only the socket peer address. */
    private static String adminKey(HttpServletRequest request) {
        Object adminId = request.getAttribute(AdminInterceptor.ADMIN_ID);
        String who = request.getHeader("X-Upload-Client");
        if (adminId != null) return "admin:" + adminId + (who == null ? "" : ":" + who);
        return "admin-ip:" + request.getRemoteAddr() + (who == null ? "" : ":" + who);
    }

    private static String privateUploadKey(HttpServletRequest request) {
        Object adminId = request.getAttribute(AdminInterceptor.ADMIN_ID);
        return adminId == null ? "private-guest:" + request.getRemoteAddr() : "admin:" + adminId;
    }

    private static String clientKey(HttpServletRequest request) {
        return "guest:" + request.getRemoteAddr();
    }
}

