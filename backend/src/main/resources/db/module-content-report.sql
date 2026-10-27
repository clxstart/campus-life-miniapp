-- 内容举报
--
-- 为什么必须有：UGC 平台如果用户没法举报，出问题时平台要担责；
-- 小程序审核也要求提供「用户可举报」的路径。
--
-- 设计取舍：
--   - 一个人对同一条内容只能举报一次（唯一键约束），避免刷举报把内容顶下去；
--   - 举报不自动下架，先进管理端待处理队列，由人判断 —— 自动下架容易被恶意利用；
--   - target_type 用字符串而不是外键，因为要覆盖帖子/评论/商品/群消息/用户等多种对象。
--
-- 幂等脚本。

CREATE TABLE IF NOT EXISTS content_report (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(16)  NOT NULL COMMENT 'post/comment/goods/message/user',
  target_id   BIGINT       NOT NULL COMMENT '被举报对象 id',
  reporter_id BIGINT       NOT NULL COMMENT '举报人 user.id',
  reason      VARCHAR(32)  NOT NULL COMMENT '广告/辱骂/色情/诈骗/侵权/其他',
  detail      VARCHAR(200) NOT NULL DEFAULT '' COMMENT '补充说明',
  status      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/handled/ignored',
  handled_by  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '处理人',
  handled_at  DATETIME     NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_once (target_type, target_id, reporter_id),
  KEY idx_report_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容举报';
