-- V3.1 段位与成长值 / 段位特权：配置表、体验特权表、圈子创建者字段、特权开关。
--
-- 设计要点：
--   * 段位不落库，读时由 user_growth.g_total 推导，避免「成长值到了段位没升」；
--   * user_privilege 只放限时特权（新人白银体验），永久特权由段位实时判定；
--   * circle.creator_id 用于「每人最多创建 1 个圈子」与审核页展示创建者。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

-- ============ 1. 段位配置 ============
CREATE TABLE IF NOT EXISTS tier_config (
  tier_code   VARCHAR(16) PRIMARY KEY,
  name        VARCHAR(32) NOT NULL,
  g_threshold INT         NOT NULL,
  s_threshold INT         NOT NULL DEFAULT 0 COMMENT '当季活跃值门槛，V3.1 不启用',
  honor_label VARCHAR(32) NOT NULL DEFAULT '',
  enabled     TINYINT     NOT NULL DEFAULT 1,
  sort        INT         NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='段位配置；V3.1 仅前三段 enabled=1';

INSERT INTO tier_config (tier_code, name, g_threshold, s_threshold, honor_label, enabled, sort) VALUES
  ('bronze',   '青铜·萌新',       0,    0, '青铜铭牌',    1, 10),
  ('silver',   '白银·圈内熟人',   60,   0, '白银铭牌',    1, 20),
  ('gold',     '黄金·活跃分子',   200,  0, '黄金铭牌',    1, 30),
  ('platinum', '铂金·风云人物',   600,  0, '铂金铭牌',    0, 40),
  ('diamond',  '钻石·一校红人',   1500, 0, '钻石铭牌',    0, 50),
  ('star',     '星耀·校园顶流',   3500, 0, '星耀铭牌',    0, 60),
  ('king',     '王者·城市之星',   8000, 0, '王者铭牌',    0, 70),
  ('legend',   '传奇·校宝名人堂', 18000,0, '传奇铭牌',    0, 80)
ON DUPLICATE KEY UPDATE tier_code = tier_code;

-- ============ 2. 限时特权（体验段位） ============
CREATE TABLE IF NOT EXISTS user_privilege (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT      NOT NULL,
  priv_key     VARCHAR(32) NOT NULL COMMENT 'tier_trial_silver / priority_reply / post_fastlane / circle_create',
  active       TINYINT     NOT NULL DEFAULT 1,
  activated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   DATETIME    NULL COMMENT 'NULL=永久，按段位实时判定',
  UNIQUE KEY uk_priv (user_id, priv_key),
  KEY idx_priv_active (user_id, active, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时特权；永久特权不入表，由段位实时判定';

-- ============ 3. 圈子创建者 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circle' AND COLUMN_NAME='creator_id')=0,
  'ALTER TABLE circle ADD COLUMN creator_id BIGINT NULL COMMENT ''用户创建时记录，管理端建的为 NULL''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circle' AND INDEX_NAME='idx_circle_creator')=0,
  'ALTER TABLE circle ADD KEY idx_circle_creator (creator_id)',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 4. 特权开关 ============
-- isEnabled() 对缺失 key 默认开启；这里落库是为了让运营能一键关停。
INSERT INTO feature_flag (flag_key, enabled, remark, updated_by) VALUES
  ('post.fastlane.enabled', 1, '黄金发帖免审：关闭后所有用户都走普通审核', 'v3.1'),
  ('circle.create.enabled', 1, '用户创建圈子：关闭后黄金用户也不能建圈', 'v3.1')
ON DUPLICATE KEY UPDATE flag_key = flag_key;

SELECT '段位与特权表结构就绪' AS result;