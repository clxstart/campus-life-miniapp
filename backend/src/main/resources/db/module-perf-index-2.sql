-- 性能索引补充（第二版）
--
-- 背景：用 EXPLAIN 实测发现 comment 表按 post_id 查是 type=index（全索引扫描），
-- 因为只有 PRIMARY 和 audit_status 索引。评论列表是最高频的查询之一，
-- 评论量涨起来后每次看评论都要扫全表。
--
-- 本脚本幂等，可重复执行。

-- 评论列表：WHERE post_id=? ORDER BY id
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_post') = 0,
  'ALTER TABLE `comment` ADD INDEX idx_comment_post (post_id, id)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 我的收藏：按 user_id 列出我收藏的帖子（uk 是 post_id,user_id，前缀用不上 user_id）
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'favorite' AND INDEX_NAME = 'idx_favorite_user') = 0,
  'ALTER TABLE favorite ADD INDEX idx_favorite_user (user_id, id)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 支付统计按时间范围聚合：pay_status + paid_at
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_order' AND INDEX_NAME = 'idx_order_paid_at') = 0,
  'ALTER TABLE user_order ADD INDEX idx_order_paid_at (pay_status, paid_at)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
