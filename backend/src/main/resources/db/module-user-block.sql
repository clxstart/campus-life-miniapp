-- 用户拉黑
--
-- 为什么需要：举报是"事后"，拉黑是"事前"——被骚扰的用户需要能主动屏蔽对方，
-- 而不是每次都去举报等处理。UGC 平台的标准能力。
--
-- 语义（按主流社交产品做法）：**双向不可见**
--   A 拉黑 B 后：A 看不到 B 的帖子和评论；B 也看不到 A 的；
--   双方都无法给对方发私信。
-- 为什么双向：单向屏蔽会让被拉黑方继续单向骚扰（能发消息、能看到内容），
-- 起不到保护作用。
--
-- 幂等脚本。

CREATE TABLE IF NOT EXISTS user_block (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  blocker_id BIGINT   NOT NULL COMMENT '发起拉黑的人',
  blocked_id BIGINT   NOT NULL COMMENT '被拉黑的人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_block_pair (blocker_id, blocked_id),
  KEY idx_block_blocked (blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户拉黑关系';
