package com.campuslife.controller;

import com.campuslife.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 图片资源读取（DB 存储）。
 *
 * 一个资源一个稳定 URL：{@code GET /api/asset/{name}}。
 * 没有这个接口，方案就不成立 —— 小程序 {@code <image src>} 只认 http(s)，不认 base64 也不认站内路径。
 *
 * 三个刻意的设计：
 *  1. 公开读：<image> 请求带不上 Authorization 头，必须匿名可达，和 /uploads/** 的地位一致；
 *  2. 强缓存 + ETag：图片内容不变，URL 也不会变，1 年 immutable 能把回源压到接近零；
 *  3. 只允许安全文件名：路径变量直接拼进查询条件，先做字符白名单校验（不依赖 SQL 绑定兜底）。
 */
@RestController
@RequiredArgsConstructor
public class AssetController {

    private final MediaAssetService assets;

    @GetMapping("/api/asset/{name}")
    public ResponseEntity<byte[]> get(@PathVariable String name,
                                      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        MediaAssetService.Asset meta;
        try {
            meta = assets.meta(name);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
        if (meta == null) return ResponseEntity.notFound().build();

        // 内容摘要没变 → 304，省掉整张图的下行
        if (meta.etag() != null && meta.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(meta.etag())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .build();
        }

        byte[] body = assets.body(name);
        if (body == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.contentType()))
            .contentLength(body.length)
            .eTag(meta.etag() == null ? "" : meta.etag())
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(body);
    }
}