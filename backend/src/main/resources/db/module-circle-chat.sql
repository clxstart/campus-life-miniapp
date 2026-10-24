-- 圈子群聊模块：消息、已读位置和圈子摘要字段。
-- 本文件可重复执行，已有字段/索引会被跳过。
USE campus_life;

CREATE TABLE IF NOT EXISTS circle_message (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  circle_id  BIGINT NOT NULL,
  sender_id  BIGINT NOT NULL,
  type       VARCHAR(8) NOT NULL DEFAULT 'text',
  content    VARCHAR(1000) NOT NULL DEFAULT '',
  image      VARCHAR(255),
  client_id  VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_message_client (sender_id, client_id),
  KEY idx_circle_time (circle_id, id DESC),
  KEY idx_sender (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS circle_read (
  circle_id    BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  last_read_id BIGINT NOT NULL DEFAULT 0,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (circle_id, user_id),
  KEY idx_circle_read_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl_circle_chat_last_at = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='circle' AND column_name='last_message_at') = 0,
  'ALTER TABLE circle ADD COLUMN last_message_at DATETIME NULL',
  'SELECT 1'));
PREPARE stmt_circle_chat_last_at FROM @ddl_circle_chat_last_at;
EXECUTE stmt_circle_chat_last_at;
DEALLOCATE PREPARE stmt_circle_chat_last_at;

SET @ddl_circle_chat_last = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='circle' AND column_name='last_message') = 0,
  'ALTER TABLE circle ADD COLUMN last_message VARCHAR(100) NULL',
  'SELECT 1'));
PREPARE stmt_circle_chat_last FROM @ddl_circle_chat_last;
EXECUTE stmt_circle_chat_last;
DEALLOCATE PREPARE stmt_circle_chat_last;
