-- 圈子连续活跃（火花）。
-- 一天一个圈子最多一行，(circle_id, active_date) 唯一键是"同日只记一次"的硬保障，
-- 并发消息靠数据库唯一键去重，不依赖应用层判断。
-- 可重复执行。
USE campus_life;

CREATE TABLE IF NOT EXISTS circle_activity_day (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  circle_id BIGINT NOT NULL,
  active_date DATE NOT NULL COMMENT '北京时间自然日',
  first_message_id BIGINT DEFAULT NULL,
  actor_id BIGINT DEFAULT NULL COMMENT '当天第一条有效消息的发送人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_day (circle_id, active_date),
  KEY idx_activity_date (active_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈子每日活跃（同日最多一条）';

-- circle 冗余存连续天数，列表页不必每次聚合
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='circle' AND column_name='streak_days');
SET @ddl = IF(@missing,
  'ALTER TABLE circle ADD COLUMN streak_days INT NOT NULL DEFAULT 0, ADD COLUMN last_active_date DATE NULL, ADD COLUMN spark_on TINYINT NOT NULL DEFAULT 0',
  'SELECT "circle streak columns exist"');
PREPARE streak_stmt FROM @ddl; EXECUTE streak_stmt; DEALLOCATE PREPARE streak_stmt;
