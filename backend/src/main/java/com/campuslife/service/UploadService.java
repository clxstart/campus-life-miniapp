package com.campuslife.service;

import com.campuslife.ha.LocalObjectStorage;
import com.campuslife.ha.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

/**
 * 图片上传。校验逻辑与响应字段保持原样，存储后端由 campus.ha 决定（finding-10）：
 *   单实例 → 本机目录；集群 → S3 兼容对象存储，任意节点都能读到同一张图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ObjectStorage storage;

    @Value("${campus.upload.public-prefix:/uploads}") private String publicPrefix;
    @Value("${campus.upload.private-prefix:/private}") private String privatePrefix;

    /**
     * 本机上传目录；对象存储模式下返回一个不参与读写的占位路径，
     * 调用方需要通过 {@link #localDisk()} 判断是否真的在本机落盘。
     */
    public Path root() {
        Path local = localDisk();
        if (local != null) return local;
        return Paths.get(System.getProperty("java.io.tmpdir"), "campus-upload-not-used").toAbsolutePath().normalize();
    }

    /** 真正用于磁盘读写的目录；对象存储模式返回 null */
    public Path localDisk() { return storage.localRoot(); }

    /** 私有目录（仅本机模式有意义） */
    public Path privateRoot() {
        Path local = localDisk();
        if (local == null) return Paths.get(System.getProperty("java.io.tmpdir"), "campus-upload-not-used").toAbsolutePath().normalize();
        Path dir = local.resolve("private");
        try { Files.createDirectories(dir); } catch (IOException error) { throw new IllegalStateException("私有上传目录不可用：" + error.getMessage()); }
        return dir;
    }

    public Map<String,Object> store(MultipartFile file) {
        return storeInternal(file, false);
    }

    /** 私有存储：返回 /private/xxx 路径，不对外公开可访问 */
    public Map<String,Object> storePrivate(MultipartFile file) {
        return storeInternal(file, true);
    }

    private Map<String,Object> storeInternal(MultipartFile file, boolean isPrivate) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 5MB");

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("仅支持 jpg/png/gif/webp 格式");

        // 文件头校验：只认扩展名不够，把 .php 改名成 .jpg 也能过。
        // 这里读前 12 字节比对真实图片签名，挡住伪装文件。
        String actual = detectImageType(file);
        if (actual == null) throw new IllegalArgumentException("文件内容不是有效图片，请重新选择");

        // 文件名由服务端生成，不使用客户端提供的名字，避免路径穿越
        String name = LocalObjectStorage.randomName(ext);
        try (InputStream in = file.getInputStream()) {
            if (isPrivate) storage.putPrivate(name, in, file.getSize(), file.getContentType());
            else storage.put(name, in, file.getSize(), file.getContentType());
        } catch (IOException error) {
            log.warn("图片保存失败 name={}", name, error);
            throw new IllegalStateException("图片保存失败");
        }

        String prefix = isPrivate ? privatePrefix : publicPrefix;
        Map<String,Object> out = new LinkedHashMap<>();
        if (isPrivate) {
            // 私有文件不给完整 URL，避免被直接访问；调用方存站内路径，读取时再走鉴权接口
            out.put("path", prefix + "/" + name);
            out.put("url", "");
        } else {
            // url 返回完整地址：小程序把以 / 开头的路径当本地资源，只有 http(s) 才会走网络请求。
            out.put("url", storage.publicUrl(name));
            out.put("path", publicPrefix + "/" + name);
        }
        out.put("name", name);
        out.put("size", file.getSize());
        out.put("contentType", actual);
        out.put("private", isPrivate);
        out.put("uploadedAt", LocalDate.now().toString());
        return out;
    }

    public byte[] readPrivate(String name) throws IOException { return storage.readPrivate(name); }

    public byte[] readPublic(String name) throws IOException { return storage.readPublic(name); }

    /**
     * 读文件头判断真实图片类型。
     * 支持 PNG / JPEG / GIF / WEBP（与 ALLOWED 扩展名一致），识别不出返回 null。
     */
    private static String detectImageType(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException error) {
            return null;
        }
        if (read < 4) return null;

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "png";
        // JPEG: FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "jpeg";
        // GIF: "GIF8"
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return "gif";
        // WEBP: "RIFF" .... "WEBP"
        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
            && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "webp";
        return null;
    }

    /**
     * 清理上传目录里的老文件（dryRun 只预览）。仅本机存储有意义。
     *
     * 没有跨服务查全库引用关系，所以按「文件修改时间超过 N 天」做保守清理 ——
     * 宁可少删，不能误删正在使用的图片。
     *
     * @param dryRun true 只统计不删除
     * @param olderThanDays 只清理超过这么多天的文件（默认 7 天）
     */
    public Map<String,Object> cleanOrphans(boolean dryRun, int olderThanDays) {
        int days = Math.max(1, olderThanDays);
        long cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        List<String> removed = new ArrayList<>();
        int total;
        int orphans = 0;

        Path root = localDisk();
        if (root == null) {
            Map<String,Object> skipped = new LinkedHashMap<>();
            skipped.put("total", 0); skipped.put("orphans", 0); skipped.put("removed", 0);
            skipped.put("dryRun", dryRun); skipped.put("olderThanDays", days);
            skipped.put("skipped", true);
            skipped.put("reason", "当前为对象存储模式，请在对象存储侧配置生命周期规则清理，勿用本接口");
            return skipped;
        }

        try (Stream<Path> stream = Files.list(root)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            total = files.size();
            for (Path file : files) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime >= cutoff) continue;
                orphans += 1;
                if (!dryRun) {
                    Files.deleteIfExists(file);
                    removed.add(file.getFileName().toString());
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("清理失败：" + error.getMessage());
        }

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("orphans", orphans);
        out.put("removed", removed.size());
        out.put("dryRun", dryRun);
        out.put("olderThanDays", days);
        return out;
    }
}
