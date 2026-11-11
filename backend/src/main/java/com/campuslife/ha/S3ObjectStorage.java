package com.campuslife.ha;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 集群模式：S3 兼容对象存储。多个节点共享同一 bucket，任意节点都能读写。
 *
 * URL 规则：public-base-url 非空时用它拼（便于挂 CDN 域名），否则用 {endpoint}/{bucket}/{key}。
 * 私有文件（证件类）只给站内路径，读取走已鉴权的管理端接口，从不返回对象存储直链。
 */
public class S3ObjectStorage implements ObjectStorage {
    static final String PUBLIC_DIR = "public/";
    static final String PRIVATE_DIR = "private/";

    private final MinioClient client;
    private final String bucket;
    private final String publicPrefix;
    private final String privatePrefix;
    private final String publicBaseUrl;

    public S3ObjectStorage(HaProperties.Storage storage, String publicPrefix, String privatePrefix) {
        this.bucket = storage.getBucket();
        this.publicPrefix = publicPrefix;
        this.privatePrefix = privatePrefix;
        this.client = MinioClient.builder()
            .endpoint(storage.getEndpoint())
            .region(storage.getRegion() == null || storage.getRegion().isBlank() ? null : storage.getRegion())
            .credentials(storage.getAccessKey(), storage.getSecretKey())
            .build();
        // 公开文件基址：优先用配置的自定义域名/CDN，否则退回 endpoint/bucket/
        String base = storage.getPublicBaseUrl() == null ? "" : storage.getPublicBaseUrl().trim();
        if (base.isEmpty()) base = storage.getEndpoint().replaceAll("/+$", "") + "/" + bucket + "/";
        this.publicBaseUrl = base.endsWith("/") ? base : base + "/";
    }

    @Override
    public Stored put(String name, InputStream in, long size, String contentType) throws IOException {
        upload(PUBLIC_DIR + name, in, size, contentType);
        return new Stored(publicBaseUrl + PUBLIC_DIR + name, publicPrefix + "/" + name);
    }

    @Override
    public void putPrivate(String name, InputStream in, long size, String contentType) throws IOException {
        upload(PRIVATE_DIR + name, in, size, contentType);
    }

    private void upload(String key, InputStream in, long size, String contentType) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(key).stream(in, size, -1)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .build());
        } catch (Exception e) {
            throw new IOException("图片上传到对象存储失败：" + e.getMessage(), e);
        }
    }

    @Override
    public byte[] readPrivate(String name) throws IOException { return read(PRIVATE_DIR + name); }

    @Override
    public byte[] readPublic(String name) throws IOException { return read(PUBLIC_DIR + name); }

    @Override
    public String publicUrl(String name) { return publicBaseUrl + PUBLIC_DIR + name; }

    private byte[] read(String key) throws IOException {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IOException("对象存储读取失败：" + key + "：" + e.getMessage(), e);
        }
    }

    /** 对象存储没有本机目录：返回 null，调用方跳过磁盘相关逻辑 */
    @Override
    public Path localRoot() { return null; }

    @Override
    public void healthCheck() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            throw new IllegalStateException("对象存储 bucket 不存在：" + bucket);
        }
        // 写一个探针对象再读回，确认凭证具备读写权限
        String probe = PRIVATE_DIR + ".health-probe";
        byte[] payload = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(probe)
            .stream(new ByteArrayInputStream(payload), payload.length, -1).build());
        client.statObject(StatObjectArgs.builder().bucket(bucket).object(probe).build());
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(probe).build())) {
            if (in.readAllBytes().length != payload.length) throw new IllegalStateException("对象存储读写校验失败");
        }
    }
}
