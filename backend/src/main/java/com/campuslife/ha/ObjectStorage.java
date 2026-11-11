package com.campuslife.ha;

import java.io.IOException;
import java.io.InputStream;

/**
 * 上传文件存储（finding-10）。
 *
 * 单实例模式写本机目录，集群模式写 S3 兼容对象存储（MinIO / AWS S3 / 阿里云 OSS / 腾讯云 COS），
 * 这样图片请求落到任意节点都能读到。
 */
public interface ObjectStorage {
    /** 写入公开文件（头像、商品图等），返回可直接访问的 URL 与站内路径 */
    Stored put(String name, InputStream in, long size, String contentType) throws IOException;

    /** 写入私有文件（学生证、营业执照等），不做任何公开暴露 */
    void putPrivate(String name, InputStream in, long size, String contentType) throws IOException;

    /** 读取私有文件内容（供已鉴权的管理端下载） */
    byte[] readPrivate(String name) throws IOException;

    /** 读取公开文件内容（供内容审核把图片回传给微信校验） */
    byte[] readPublic(String name) throws IOException;

    /** 公开文件的可访问地址（本机模式拼 base-url，对象存储模式拼对象地址或 CDN 域名） */
    String publicUrl(String name);

    /**
     * 本机目录根路径；对象存储模式返回 null（调用方应据此跳过磁盘清理/磁盘容量统计）。
     */
    java.nio.file.Path localRoot();

    /** 存储健康探测：失败抛异常 */
    void healthCheck() throws Exception;

    record Stored(String url, String path) {}
}
