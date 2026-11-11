-- V3.1 签到与连签：每日签到事实表。
--
-- 为什么独立一张表：连签要回答「昨天签没签」，task_log 里混着几十种任务，
-- 每次扫表推算既慢又容易错；一天一行最直接。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

CREATE TABLE IF NOT EXISTS sign_roll (
  user_id     BIGINT   NOT NULL,
  biz_date    DATE     NOT NULL COMMENT '北京时间自然日',
  streak_days INT      NOT NULL DEFAULT 1 COMMENT '截至当日的连续签到天数',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, biz_date),
  KEY idx_sign_streak (biz_date, streak_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日签到；一天一行，连签靠昨天那行推算';

-- 签到相关任务目录：已存在的 P/G 不覆盖（后台可能调过）。
INSERT INTO task_catalog (task_key, module, name, p_reward, g_reward, daily_cap, enabled, sort) VALUES
  ('daily_sign',      'points', '每日签到',   1,  1, 1, 1, 10),
  ('sign_streak_3',   'points', '连签 3 天',  5,  5, 1, 1, 21),
  ('sign_streak_5',   'points', '连签 5 天', 10, 10, 1, 1, 22),
  ('sign_streak_7',   'points', '连签 7 天', 15, 15, 1, 1, 23),
  ('sign_streak_15',  'points', '连签 15 天',20, 20, 1, 1, 24),
  ('sign_streak_20',  'points', '连签 20 天',30, 30, 1, 1, 25),
  ('sign_streak_30',  'points', '连签 30 天',50, 50, 1, 1, 26)
ON DUPLICATE KEY UPDATE task_key = task_key;

SELECT '签到与连签表结构就绪' AS result;