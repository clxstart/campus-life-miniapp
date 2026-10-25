-- 多学校场景性能优化：补齐 school_id 索引
--
-- 背景：
--   本项目为多学校架构，13 张表带 school_id 做数据隔离，
--   但只有 5 张建了索引。学校数量增多后，
--   「查本校数据」会退化为全表扫描，是主要性能瓶颈。
--
-- 说明：
--   每个索引都做成「school_id + 常用过滤列」的复合索引，
--   既支持按校过滤，也支持按状态/时间排序，避免回表。
--   全部使用 IF NOT EXISTS 逻辑，可重复执行。

USE campus_life;

-- ============ 1. user：按校查用户 / 用户列表分页 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='user' AND index_name='idx_user_school')=0,
  'ALTER TABLE `user` ADD INDEX idx_user_school (school_id, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 2. goods：二手市场按校+状态列表 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='goods' AND index_name='idx_goods_school')=0,
  'ALTER TABLE goods ADD INDEX idx_goods_school (school_id, status, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 3. job：兼职按校+状态列表 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='job' AND index_name='idx_job_school')=0,
  'ALTER TABLE job ADD INDEX idx_job_school (school_id, status, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 4. shop：外卖按校+营业状态 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='shop' AND index_name='idx_shop_school')=0,
  'ALTER TABLE shop ADD INDEX idx_shop_school (school_id, status, category)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 5. campus_activity：活动按校+状态+时间 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND index_name='idx_activity_school')=0,
  'ALTER TABLE campus_activity ADD INDEX idx_activity_school (school_id, status, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 6. banner：按校+槽位+排序 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='banner' AND index_name='idx_banner_school')=0,
  'ALTER TABLE banner ADD INDEX idx_banner_school (school_id, slot, sort)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 7. academic_term：按校查学期 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='academic_term' AND index_name='idx_term_school')=0,
  'ALTER TABLE academic_term ADD INDEX idx_term_school (school_id, active)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 8. ai_chat_log：按校+时间统计（超管看板）============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='ai_chat_log' AND index_name='idx_ai_log_school')=0,
  'ALTER TABLE ai_chat_log ADD INDEX idx_ai_log_school (school_id, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 9. 订单表：多校场景下按店铺+状态查订单 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='user_order' AND index_name='idx_order_created')=0,
  'ALTER TABLE user_order ADD INDEX idx_order_created (created_at, status)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 10. 聊天消息：按接收者+时间（未读数统计）============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='chat_message' AND index_name='idx_chat_receiver')=0,
  'ALTER TABLE chat_message ADD INDEX idx_chat_receiver (receiver_id, created_at)', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT '索引迁移完成' AS result;

