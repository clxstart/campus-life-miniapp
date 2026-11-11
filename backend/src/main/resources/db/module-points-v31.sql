-- V3.1 积分体系：统一发放口 PointsService 所需的表结构。
--
-- 现状：积分只有 user_points（余额）与 point_log（流水）两张表，
-- 写入分散在 PetMapper / PetCompanionMapper，没有日上限、没有幂等键、没有成长值。
--
-- 本次新增：
--   user_growth   成长值 G（只涨不降，段位推导的唯一依据）
--   task_catalog  任务目录（管理端可改，30 秒缓存）
--   task_log      任务完成事实（幂等 + 日上限的唯一依据）
-- 并给 point_log 补 4 列 + 1 个唯一键。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

-- ============ 1. point_log 扩列 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log' AND COLUMN_NAME='module')=0,
  'ALTER TABLE point_log ADD COLUMN module VARCHAR(16) NOT NULL DEFAULT ''pet'' COMMENT ''业务模块''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log' AND COLUMN_NAME='biz_key')=0,
  'ALTER TABLE point_log ADD COLUMN biz_key VARCHAR(64) NULL COMMENT ''业务幂等键，服务端拼接 taskKey:业务号''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log' AND COLUMN_NAME='g_amount')=0,
  'ALTER TABLE point_log ADD COLUMN g_amount INT NOT NULL DEFAULT 0 COMMENT ''同笔的成长值变动''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log' AND COLUMN_NAME='balance_after')=0,
  'ALTER TABLE point_log ADD COLUMN balance_after INT NULL COMMENT ''这笔之后的余额，对账用''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 幂等键：含 user_id，可空列不影响宠物历史行（MySQL 唯一索引允许多个 NULL）
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log' AND INDEX_NAME='uk_user_biz')=0,
  'ALTER TABLE point_log ADD UNIQUE KEY uk_user_biz (user_id, biz_key)',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 2. 成长值 ============
CREATE TABLE IF NOT EXISTS user_growth (
  user_id    BIGINT   PRIMARY KEY,
  g_total    INT      NOT NULL DEFAULT 0 COMMENT '成长值，只涨不降',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成长值，只涨不降';

-- ============ 3. 任务目录 ============
CREATE TABLE IF NOT EXISTS task_catalog (
  task_key  VARCHAR(32) PRIMARY KEY,
  module    VARCHAR(16) NOT NULL COMMENT 'points|forum|commerce|jobs|market|dorm|pet',
  name      VARCHAR(32) NOT NULL,
  p_reward  INT         NOT NULL DEFAULT 0,
  g_reward  INT         NOT NULL DEFAULT 0,
  daily_cap INT         NOT NULL DEFAULT 1 COMMENT '当日最多发放次数，0=不限',
  enabled   TINYINT     NOT NULL DEFAULT 1,
  sort      INT         NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务目录，管理端可改，30 秒缓存生效';

-- ============ 4. 任务完成事实 ============
CREATE TABLE IF NOT EXISTS task_log (
  id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  task_key   VARCHAR(32) NOT NULL,
  biz_date   DATE        NOT NULL COMMENT '服务端按 Asia/Shanghai 计算',
  biz_key    VARCHAR(64) NOT NULL,
  p          INT         NOT NULL DEFAULT 0,
  g          INT         NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_idem (user_id, task_key, biz_key),
  KEY idx_task_day (user_id, task_key, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务完成事实，幂等与日上限的唯一依据';

-- ============ 5. 任务种子（V3.1 首批）============
-- 已存在的不覆盖：后台改过的 P/G 不能因为重跑脚本被重置回去。
INSERT INTO task_catalog (task_key, module, name, p_reward, g_reward, daily_cap, enabled, sort) VALUES
  ('daily_sign',       'points',   '每日签到',      1,  1,  1, 1, 10),
  ('sign_streak_3',    'points',   '连签 3 天',     5,  5,  1, 1, 21),
  ('sign_streak_5',    'points',   '连签 5 天',    10, 10,  1, 1, 22),
  ('sign_streak_7',    'points',   '连签 7 天',    15, 15,  1, 1, 23),
  ('sign_streak_15',   'points',   '连签 15 天',   20, 20,  1, 1, 24),
  ('sign_streak_20',   'points',   '连签 20 天',   30, 30,  1, 1, 25),
  ('sign_streak_30',   'points',   '连签 30 天',   50, 50,  1, 1, 26),
  ('post_create',      'forum',    '发论坛帖',      5,  5,  3, 1, 30),
  ('post_comment',     'forum',    '评论',          5,  5,  3, 1, 31),
  ('food_order',       'commerce', '外卖点单完成', 10, 20,  2, 1, 40),
  ('job_settle',       'jobs',     '兼职结算',      5, 15,  2, 1, 50),
  ('market_trade',     'market',   '二手成交',      5, 12,  2, 1, 60),
  ('dorm_all_sign',    'dorm',     '宿舍全员签到',  5,  8,  1, 1, 70),
  ('dorm_week_first',  'points',   '宿舍周榜第一',  0, 30,  1, 1, 71),
  ('newbie_gift',      'points',   '新人礼包',     30,  0,  1, 1, 5),
  ('pet_checkin',      'pet',      '宠物签到',      5,  0,  1, 1, 80),
  -- 派遣奖励金额是变额（按任务时长随机），P/G 以调用方传入为准，
  -- 目录只负责开关与日上限；daily_cap=0 表示不限次。
  ('pet_dispatch',     'pet',      '宠物派遣奖励',  0,  0,  0, 1, 81)
ON DUPLICATE KEY UPDATE task_key = task_key;

SELECT '积分体系表结构就绪' AS result;