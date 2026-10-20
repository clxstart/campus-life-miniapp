package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.*;
import com.campuslife.service.UploadRateLimiter;
import com.campuslife.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class UploadController {
    private final UploadService service;
    private final UploadRateLimiter limiter;

    // 单图上传：小程序 wx.uploadFile 使用 name="file"。
    // 刻意保持无鉴权 —— 小程序要支持「未登录也能演示」，收紧会破坏既有行为。
    // 但加了频率限制，防止脚本刷爆磁盘。
    @PostMapping("/upload")
    public ApiResponse<Map<String,Object>> upload(@RequestParam("file") MultipartFile file,
                                                  HttpServletRequest request) {
        limiter.acquire(clientKey(request));
        return ApiResponse.ok(service.store(file));
    }

    // 管理端上传：路径落在 /api/admin/** 下，由 AdminInterceptor 强制校验 token（无 token 直接 401）。
    // 管理端一律用这个接口，不要用上面那个公开接口。
    @PostMapping("/admin/upload")
    public ApiResponse<Map<String,Object>> adminUpload(@RequestParam("file") MultipartFile file,
                                                       HttpServletRequest request) {
        limiter.acquire(adminKey(request));
        return ApiResponse.ok(service.store(file));
    }

    // 私有上传：学生证等敏感图片。存到 uploads/private/，不走 /uploads/** 静态映射，
    // 因此无法被直接访问；需要时由带鉴权的接口读取。
    @PostMapping("/admin/upload/private")
    public ApiResponse<Map<String,Object>> adminUploadPrivate(@RequestParam("file") MultipartFile file,
                                                              HttpServletRequest request) {
        limiter.acquire(adminKey(request));
        return ApiResponse.ok(service.storePrivate(file));
    }

    // 孤儿文件清理：默认只预览，传 dryRun=false 才真的删。
    @GetMapping("/admin/uploads/orphans")
    public ApiResponse<Map<String,Object>> orphans(@RequestParam(defaultValue = "true") boolean dryRun,
                                                   @RequestParam(defaultValue = "7") int olderThanDays,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.cleanOrphans(dryRun, olderThanDays));
    }

    /**
     * 频率限制的来源标识：
     * 管理端用 adminId（同一账号共享配额），游客用 IP + 可选的客户端标识头。
     * 允许客户端自带标识是为了让不同来源互不影响（也便于测试）。
     */
    private static String adminKey(HttpServletRequest request) {
        Object adminId = request.getAttribute(AdminInterceptor.ADMIN_ID);
        String who = request.getHeader("X-Upload-Client");
        if (adminId != null) return "admin:" + adminId + (who == null ? "" : ":" + who);
        return "admin-ip:" + ip(request) + (who == null ? "" : ":" + who);
    }

    private static String clientKey(HttpServletRequest request) {
        String who = request.getHeader("X-Upload-Client");
        return "guest:" + ip(request) + (who == null ? "" : ":" + who);
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
