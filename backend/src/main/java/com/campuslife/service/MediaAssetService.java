package com.campuslife.service;

import com.campuslife.mapper.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图片资源（存 MySQL，不落磁盘）。
 *
 * 为什么不是 UploadService：那套是「用户上传 → 落 uploads 目录 → /uploads/** 静态映射」，
 * 本机没有对象存储、域名又还在备案，先让资源跟着数据库走；对外仍然只是一个稳定 URL，
 * 页面侧只认 {@code ASSET_BASE_URL + /api/asset/<name>}，所以以后迁到 OSS / CDN
 * 改的是前端一处基址，不是几十个页面。
 *
 * 代价必须写清楚（不要假装没有）：LONGBLOB 会让库和备份一起变大，
 * 单表几万张图之后备份/主从复制都会变慢，届时按 asset_key 原样迁出即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    /** 单张图上限，与 UploadService 的 5MB 保持一致 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int PUBLIC = 1;

    private final MediaAssetMapper mapper;

    /** 对外可访问基址；小程序只能显示 http(s) 开头的图片地址 */
    @Value("${campus.upload.base-url:http://127.0.0.1:8080}")
    private String baseUrl;

    /** 资源对外名字只允许安全字符，且不允许 .. —— 拒绝而不是静默清洗（清洗会得到意外名字） */
    public static void assertSafeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("资源名无效");
        if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..")) throw new IllegalArgumentException("资源名无效");
    }

    /** 元信息（不含二进制） */
    public record Asset(String assetName, String assetKey, String contentType, long byteSize,
                        String sha256, String publicUrl, String etag) {}

    /** 按名字取元信息；不存在返回 null */
    public Asset meta(String assetName) {
        assertSafeName(assetName);
        Map<String, Object> row = mapper.meta(assetName);
        return row == null ? null : toAsset(row);
    }

    /** 按名字取二进制；不存在返回 null */
    public byte[] body(String assetName) {
        assertSafeName(assetName);
        return mapper.body(assetName);
    }

    public List<Map<String, Object>> listMeta() { return mapper.listMeta(); }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(mapper.stats() == null ? Map.of() : mapper.stats());
        Object bytes = out.get("bytes");
        long b = bytes instanceof Number n ? n.longValue() : 0L;
        out.put("megabytes", Math.round(b / 1024.0 / 1024.0 * 100.0) / 100.0);
        return out;
    }

    /**
     * 入库（幂等：同一 asset_name / asset_key 覆盖）。
     *
     * @param assetKey  逻辑键，来自原仓库路径，如 covers/shop/food-snacks
     * @param assetName 对外名字，如 food-snacks.webp；不传则用 assetKey 的最后一段
     */
    public Asset put(String assetKey, String assetName, byte[] data) {
        if (assetKey == null || assetKey.isBlank()) throw new IllegalArgumentException("assetKey 不能为空");
        String name = (assetName == null || assetName.isBlank())
            ? assetKey.substring(assetKey.lastIndexOf('/') + 1)
            : assetName;
        assertSafeName(name);
        if (data == null || data.length == 0) throw new IllegalArgumentException("图片内容为空");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 5MB");

        String type = ImageSniffer.detect(data);
        String contentType = ImageSniffer.contentTypeOf(type);
        if (contentType == null) throw new IllegalArgumentException("文件内容不是有效图片");

        String sha = sha256(data);
        mapper.upsert(assetKey, name, contentType, data.length, sha, data, PUBLIC);
        log.info("[asset] 入库 {}（{}，{} 字节，sha256={}…）", name, contentType, data.length, sha.substring(0, 12));
        return new Asset(name, assetKey, contentType, data.length, sha, publicUrl(name), etagOf(sha));
    }

    /** 管理端上传：名字由服务端生成（扩展名按真实类型给，不信客户端文件名） */
    public Asset storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 5MB");
        byte[] data = readAll(file);
        String type = ImageSniffer.detect(data);
        if (type == null) throw new IllegalArgumentException("文件内容不是有效图片，请重新选择");
        String name = LocalObjectName.random(type.equals("jpeg") ? "jpg" : type);
        return put(name, name, data);
    }

    public boolean delete(String assetName) {
        assertSafeName(assetName);
        return mapper.delete(assetName) > 0;
    }

    public String publicUrl(String assetName) {
        return baseUrl + "/api/asset/" + assetName;
    }

    /** ETag 用内容摘要，内容一变自动失效；配合 immutable 长缓存 */
    public static String etagOf(String sha256) {
        return "\"" + sha256.substring(0, 32) + "\"";
    }

    private Asset toAsset(Map<String, Object> row) {
        String name = String.valueOf(row.get("assetName"));
        String sha = row.get("sha256") == null ? "" : String.valueOf(row.get("sha256"));
        Object size = row.get("byteSize");
        return new Asset(
            name,
            String.valueOf(row.get("assetKey")),
            String.valueOf(row.get("contentType")),
            size instanceof Number n ? n.longValue() : 0L,
            sha,
            publicUrl(name),
            sha.isEmpty() ? null : etagOf(sha)
        );
    }

    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException error) {
            throw new IllegalStateException("图片读取失败");
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    /** 服务端生成的文件名：随机 + 真实扩展名，避免客户端文件名带来的路径穿越 */
    static final class LocalObjectName {
        private LocalObjectName() {}

        static String random(String ext) {
            return java.util.UUID.randomUUID().toString().replace("-", "")
                + "." + ext.toLowerCase(Locale.ROOT);
        }
    }
}