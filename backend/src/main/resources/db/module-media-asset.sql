USE campus_life;

-- 图片资源表（DB 存储方案，落库而非落磁盘）。
--
-- 为什么先用数据库：本机没有对象存储，域名也还在备案，先把资源集中到已有依赖（MySQL）里，
-- 接口形态与 /uploads/** 一致（一个稳定 URL 直接给 <image src>），后续要换对象存储 / CDN
-- 只需要换 utils/remote-assets.js 的基址，小程序页面一行都不用改。
--
-- 注意：这是临时方案。LONGBLOB 会撑大数据库（备份体积、主从复制带宽都会跟着涨），
-- 图片上量后必须迁到对象存储，SQL 里保留 asset_key / sha256 两列就是为了按原路径迁移与去重。
CREATE TABLE IF NOT EXISTS media_asset (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  asset_key    VARCHAR(160)    NOT NULL COMMENT '逻辑键，来自原仓库路径，如 covers/shop/food-snacks',
  asset_name   VARCHAR(160)    NOT NULL COMMENT '对外名字，访问路径 /api/asset/{asset_name}，如 food-snacks.webp',
  content_type VARCHAR(80)     NOT NULL COMMENT 'image/webp、image/jpeg 等，直接作为响应头',
  byte_size    BIGINT          NOT NULL COMMENT '字节数，用于 Content-Length 与容量统计',
  sha256       CHAR(64)        NOT NULL COMMENT '内容摘要：去重 + ETag',
  body         LONGBLOB        NOT NULL COMMENT '图片二进制',
  public_flag  TINYINT         NOT NULL DEFAULT 1 COMMENT '1 公开可访问（/api/asset 直接返回），0 仅内部读取',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_media_asset_key (asset_key),
  UNIQUE KEY uk_media_asset_name (asset_name),
  KEY idx_media_asset_sha256 (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '图片资源（DB 存储，临时方案，后续迁对象存储）';