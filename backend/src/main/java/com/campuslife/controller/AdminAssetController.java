package com.campuslife.controller;

import com.campuslife.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端图片资源（DB 存储）。
 *
 * 走 /api/admin/** 前缀，所以 AdminInterceptor 已经强制校验 admin token，这里不再重复鉴权。
 * 上传用同一个 ImageSniffer 校验真实内容，名字由服务端生成。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/assets")
public class AdminAssetController {

    private final MediaAssetService assets;

    /** 资源清单（不含二进制），用于运营核对「库里有哪些图」 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = assets.listMeta();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("msg", "ok");
        out.put("data", rows);
        return out;
    }

    /** 容量统计 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("msg", "ok");
        out.put("data", assets.stats());
        return out;
    }

    /** 上传一张图进库 */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        MediaAssetService.Asset asset = assets.storeUpload(file);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", asset.assetName());
        data.put("path", "/api/asset/" + asset.assetName());
        data.put("url", asset.publicUrl());
        data.put("contentType", asset.contentType());
        data.put("size", asset.byteSize());
        data.put("sha256", asset.sha256());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("msg", "ok");
        out.put("data", data);
        return out;
    }

    /** 删除一张图 */
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("msg", assets.delete(name) ? "ok" : "不存在");
        return out;
    }
}