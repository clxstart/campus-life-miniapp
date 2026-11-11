package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 图片资源（DB 存储临时方案，见 db/module-media-asset.sql）。
 *
 * 元信息与二进制分开查：列表/统计只取元信息，避免把 LONGBLOB 拖进内存；
 * 只有真正要回图片内容时才走 {@link #body(String)}。所有参数均绑定。
 */
@Mapper
public interface MediaAssetMapper {

    /** 单个资源的元信息（不含 body） */
    @Select("""
        SELECT asset_name AS assetName, asset_key AS assetKey, content_type AS contentType,
               byte_size AS byteSize, sha256, public_flag AS publicFlag, created_at AS createdAt
        FROM media_asset WHERE asset_name = #{assetName}
        """)
    Map<String, Object> meta(@Param("assetName") String assetName);

    /** 图片二进制；不存在返回 null */
    @Select("SELECT body FROM media_asset WHERE asset_name = #{assetName}")
    byte[] body(@Param("assetName") String assetName);

    /** 全量元信息（不含 body），用于资产盘点与前端映射表核对 */
    @Select("""
        SELECT asset_name AS assetName, asset_key AS assetKey, content_type AS contentType,
               byte_size AS byteSize, sha256, created_at AS createdAt
        FROM media_asset ORDER BY asset_key
        """)
    List<Map<String, Object>> listMeta();

    /** 容量统计：条数与总字节 */
    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(byte_size), 0) AS bytes FROM media_asset")
    Map<String, Object> stats();

    /** 写入或覆盖（asset_key / asset_name 各自唯一） */
    @Insert("""
        INSERT INTO media_asset(asset_key, asset_name, content_type, byte_size, sha256, body, public_flag)
        VALUES(#{assetKey}, #{assetName}, #{contentType}, #{byteSize}, #{sha256}, #{body}, #{publicFlag})
        ON DUPLICATE KEY UPDATE
            content_type = VALUES(content_type),
            byte_size    = VALUES(byte_size),
            sha256       = VALUES(sha256),
            body         = VALUES(body),
            public_flag  = VALUES(public_flag)
        """)
    int upsert(@Param("assetKey") String assetKey,
               @Param("assetName") String assetName,
               @Param("contentType") String contentType,
               @Param("byteSize") long byteSize,
               @Param("sha256") String sha256,
               @Param("body") byte[] body,
               @Param("publicFlag") int publicFlag);

    @Delete("DELETE FROM media_asset WHERE asset_name = #{assetName}")
    int delete(@Param("assetName") String assetName);
}