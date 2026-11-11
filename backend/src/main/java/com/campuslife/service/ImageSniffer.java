package com.campuslife.service;

/**
 * 图片真实类型识别（按文件头魔数，不看扩展名）。
 *
 * 为什么需要它：把 .php 改名成 .jpg 也能过扩展名校验，所以上传与入库两条路径都必须校验真实内容。
 * 抽成独立工具是为了让「落磁盘」的 UploadService 与「落数据库」的 MediaAssetService
 * 用同一套魔数判断，避免两处各写一份、改一处漏一处。
 */
public final class ImageSniffer {

    private ImageSniffer() {}

    /**
     * @return "png" / "jpeg" / "gif" / "webp"，识别不出返回 null
     */
    public static String detect(byte[] head, int read) {
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

    /** 便捷重载：直接从完整字节里取前 12 字节判断 */
    public static String detect(byte[] data) {
        if (data == null || data.length < 4) return null;
        byte[] head = new byte[12];
        int read = Math.min(data.length, 12);
        System.arraycopy(data, 0, head, 0, read);
        return detect(head, read);
    }

    /** 魔数结果 → HTTP Content-Type */
    public static String contentTypeOf(String type) {
        if (type == null) return null;
        return switch (type) {
            case "png" -> "image/png";
            case "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> null;
        };
    }
}