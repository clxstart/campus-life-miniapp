-- V3.1 学生认证：user_verification 扩列。
--
-- 现状：这张表只存「结论」（real_name_status / student_status / provider / reject_reason），
-- 没有学校、学院、姓名、学号、提交时间 —— 管理员根本不知道屏幕上的「待审核用户 7」是谁，
-- 整条自助认证链路是断的。
--
-- 本次扩列：
--   school_id / college / real_name / student_no  审核核对的四项材料（纯文本，不收证件影像）
--   phone                                         核对联系方式（选填；开启 verification.require.phone 后必填）
--   submitted_at                                  这一份申请的提交时间（审核页按它排序）
--   source                                        self=用户自己提交，admin=管理员直接核验
--   submit_date / submit_count                    当日提交次数（限频依据）
--
-- 为什么限频要独立计数而不是数 submitted_at：
--   submitted_at 每次提交都会被覆盖（重提必须能改材料），数它永远只能数到 1，
--   而「每天最多 3 次」要数的正是历史提交次数。所以单独存 submit_date + submit_count。
--
-- 隐私边界：本表仍然不存证件号、证件影像。学号不是证件号；姓名与学院是人工核对所必需，
-- 且按最小必要原则只用于审核与身份标识。
--
-- 幂等：整份脚本可重复执行（information_schema + PREPARE，字段已存在则 SELECT 1）。

USE campus_life;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='school_id')=0,
  'ALTER TABLE user_verification ADD COLUMN school_id BIGINT NULL COMMENT ''申请所填学校，用于校管按校审核''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='college')=0,
  'ALTER TABLE user_verification ADD COLUMN college VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''学院，默认选填''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='real_name')=0,
  'ALTER TABLE user_verification ADD COLUMN real_name VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''真实姓名''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='student_no')=0,
  'ALTER TABLE user_verification ADD COLUMN student_no VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''学号（非证件号）''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 手机号：核对联系方式。默认选填；后台把 verification.require.phone 打开后提交时必填。
-- 接口只下发脱敏值（138****1234），原文只用于审核联系。
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='phone')=0,
  'ALTER TABLE user_verification ADD COLUMN phone VARCHAR(20) NOT NULL DEFAULT '''' COMMENT ''核对用手机号（接口下发时脱敏）''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='submitted_at')=0,
  'ALTER TABLE user_verification ADD COLUMN submitted_at DATETIME NULL COMMENT ''本次申请的提交时间''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='source')=0,
  'ALTER TABLE user_verification ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''self'' COMMENT ''self=用户提交，admin=管理员直接核验''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 限频计数：submit_date 是「哪个自然日（Asia/Shanghai）」，submit_count 是那天已提交次数。
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='submit_date')=0,
  'ALTER TABLE user_verification ADD COLUMN submit_date DATE NULL COMMENT ''计数所属自然日''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND COLUMN_NAME='submit_count')=0,
  'ALTER TABLE user_verification ADD COLUMN submit_count INT NOT NULL DEFAULT 0 COMMENT ''当日已提交次数''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 审核页默认按「待审 + 提交时间」翻页
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_verification' AND INDEX_NAME='idx_verif_pending')=0,
  'ALTER TABLE user_verification ADD KEY idx_verif_pending (student_status, submitted_at)',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT '学生认证表结构就绪' AS result;