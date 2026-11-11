package com.campuslife.ha;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 单实例模式：写本机目录（与原实现一致，路径与 URL 格式不变） */
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;
    private final String publicPrefix;
    private final String privatePrefix;
    private final String baseUrl;

    public LocalObjectStorage(String dir, String publicPrefix, String privatePrefix, String baseUrl) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.publicPrefix = publicPrefix;
        this.privatePrefix = privatePrefix;
        this.baseUrl = baseUrl;
        try { Files.createDirectories(root); Files.createDirectories(privateDir()); }
        catch (IOException e) { throw new IllegalStateException("上传目录不可用：" + e.getMessage(), e); }
    }

    private Path privateDir() { return root.resolve("private"); }

    @Override
    public Stored put(String name, InputStream in, long size, String contentType) throws IOException {
        Files.copy(in, root.resolve(safeName(name)), StandardCopyOption.REPLACE_EXISTING);
        return new Stored(baseUrl + publicPrefix + "/" + name, publicPrefix + "/" + name);
    }

    @Override
    public void putPrivate(String name, InputStream in, long size, String contentType) throws IOException {
        Files.copy(in, privateDir().resolve(safeName(name)), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public byte[] readPrivate(String name) throws IOException { return Files.readAllBytes(privateDir().resolve(safeName(name))); }

    @Override
    public byte[] readPublic(String name) throws IOException { return Files.readAllBytes(root.resolve(safeName(name))); }

    @Override
    public String publicUrl(String name) { return baseUrl + publicPrefix + "/" + name; }

    @Override
    public Path localRoot() { return root; }

    @Override
    public void healthCheck() throws IOException {
        Files.createDirectories(root);
        java.io.File probe = Files.createTempFile(root, "health-", ".probe").toFile();
        if (!probe.delete()) throw new IOException("上传目录不可删除临时文件");
    }

    /**
     * 文件名由服务端生成，这里做严格校验：只允许安全字符、不允许路径分隔符或 ..（防止路径穿越）。
     * 是「拒绝」而不是「静默清洗」——清洗会把攻击输入变成合法但意料之外的文件名。
     */
    static String safeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("文件名无效");
        if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..")) throw new IllegalArgumentException("文件名无效");
        return name;
    }

    public static String randomName(String ext) { return UUID.randomUUID().toString().replace("-", "") + "." + ext; }
}
