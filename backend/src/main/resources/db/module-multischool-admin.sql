-- 多学校架构 + Web 管理端基础表
-- 手动执行；可重复执行。
USE campus_life;

-- ============ 1. 学校表 ============
CREATE TABLE IF NOT EXISTS school (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(64)  NOT NULL,
  city          VARCHAR(32)  NOT NULL DEFAULT '',
  logo          VARCHAR(255) NOT NULL DEFAULT '',
  -- 作息配置（JSON）：课表节次默认时间，学生可在此基础上自己改
  period_config TEXT         NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_school_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 2. 管理员表（独立于 user，避免普通用户提权）============
CREATE TABLE IF NOT EXISTS admin_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(32)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  real_name     VARCHAR(32)  NOT NULL DEFAULT '',
  -- super=超管（全平台） / school=校管（本校）
  role          VARCHAR(16)  NOT NULL DEFAULT 'school',
  school_id     BIGINT       NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  fail_count    INT          NOT NULL DEFAULT 0,
  locked_until  DATETIME     NULL,
  last_login_at DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_admin_username (username),
  KEY idx_admin_school (school_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 3. 操作日志 ============
CREATE TABLE IF NOT EXISTS admin_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id    BIGINT       NOT NULL,
  admin_name  VARCHAR(32)  NOT NULL DEFAULT '',
  action      VARCHAR(32)  NOT NULL,
  target_type VARCHAR(32)  NOT NULL DEFAULT '',
  target_id   VARCHAR(64)  NOT NULL DEFAULT '',
  detail      VARCHAR(500) NOT NULL DEFAULT '',
  ip          VARCHAR(64)  NOT NULL DEFAULT '',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_log_admin (admin_id, created_at),
  KEY idx_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 4. 各业务表加 school_id ============
-- 说明：post.school_id 允许 NULL，NULL 表示【跨校可见】，进全校广场。
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user' AND column_name='school_id')=0,
  'ALTER TABLE `user` ADD COLUMN school_id BIGINT NULL', 'SELECT "user.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='post' AND column_name='school_id')=0,
  'ALTER TABLE post ADD COLUMN school_id BIGINT NULL', 'SELECT "post.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='circle' AND column_name='school_id')=0,
  'ALTER TABLE circle ADD COLUMN school_id BIGINT NULL', 'SELECT "circle.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='goods' AND column_name='school_id')=0,
  'ALTER TABLE goods ADD COLUMN school_id BIGINT NULL', 'SELECT "goods.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='school_id')=0,
  'ALTER TABLE job ADD COLUMN school_id BIGINT NULL', 'SELECT "job.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND column_name='school_id')=0,
  'ALTER TABLE campus_activity ADD COLUMN school_id BIGINT NULL', 'SELECT "activity.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='school_id')=0,
  'ALTER TABLE shop ADD COLUMN school_id BIGINT NULL', 'SELECT "shop.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='banner' AND column_name='school_id')=0,
  'ALTER TABLE banner ADD COLUMN school_id BIGINT NULL', 'SELECT "banner.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='academic_term' AND column_name='school_id')=0,
  'ALTER TABLE academic_term ADD COLUMN school_id BIGINT NULL', 'SELECT "term.school_id exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 5. 学校种子：从现有 user.school 提取 ============
INSERT IGNORE INTO school(name, city)
SELECT DISTINCT school, '' FROM `user` WHERE school IS NOT NULL AND school <> '';

-- ============ 6. 回填 school_id ============
UPDATE `user` u SET u.school_id = (SELECT s.id FROM school s WHERE s.name = u.school)
  WHERE u.school_id IS NULL AND u.school IS NOT NULL AND u.school <> '';
UPDATE academic_term t SET t.school_id = (SELECT s.id FROM school s WHERE s.name = t.school)
  WHERE t.school_id IS NULL AND t.school IS NOT NULL AND t.school <> '';
-- 业务数据：演示阶段统一归到 1 号学校，避免出现无主内容
UPDATE circle SET school_id = 1 WHERE school_id IS NULL;
UPDATE goods SET school_id = 1 WHERE school_id IS NULL;
UPDATE job SET school_id = 1 WHERE school_id IS NULL;
UPDATE campus_activity SET school_id = 1 WHERE school_id IS NULL;
UPDATE shop SET school_id = 1 WHERE school_id IS NULL;
UPDATE post SET school_id = 1 WHERE school_id IS NULL;

-- ============ 7. 索引 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='post' AND index_name='idx_post_school')=0,
  'ALTER TABLE post ADD INDEX idx_post_school (school_id, status, created_at)',
  'SELECT "idx_post_school exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='circle' AND index_name='idx_circle_school')=0,
  'ALTER TABLE circle ADD INDEX idx_circle_school (school_id, status)',
  'SELECT "idx_circle_school exists"'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 8. 结果核对 ============
SELECT '学校' AS 项, id, name, city, status FROM school;
SELECT '用户归属' AS 项, id, nickname, school, school_id FROM `user`;
