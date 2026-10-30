-- 评论点赞明细表（评论点赞的「谁点过哪条」记录）。
-- 可重复执行：建表用 IF NOT EXISTS，补列前先查 information_schema。
-- 已有的历史点赞数没有明细可回溯，保持 like_count 原值不动。
USE campus_life;

CREATE TABLE IF NOT EXISTS comment_like (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  comment_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comment_user (comment_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 老库的 comment 表可能还没有 like_count
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='comment' AND column_name='like_count');
SET @ddl = IF(@missing, 'ALTER TABLE `comment` ADD COLUMN like_count INT NOT NULL DEFAULT 0', 'SELECT "comment.like_count exists"');
PREPARE cl_stmt FROM @ddl; EXECUTE cl_stmt; DEALLOCATE PREPARE cl_stmt;
