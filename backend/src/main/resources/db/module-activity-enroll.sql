-- 活动报名：支持「材料 + 审核」模式（管理端按活动单独开启）
--
-- 背景：原来报名是一键写入 (activity_id, user_id)，主办方拿不到任何材料，
-- 像「校园歌手大赛」这种需要作品/伴奏的活动没法筛选。
-- 现在每个活动可以单独选择报名模式：
--   simple   = 一键报名（原行为，默认值，老活动不受影响）
--   material = 填表 + 上传材料 + 管理员审核
--
-- 脚本可重复执行（幂等）。

-- ---------- 1. 活动表：报名模式配置 ----------
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campus_activity' AND COLUMN_NAME = 'enroll_mode') = 0,
  'ALTER TABLE campus_activity ADD COLUMN enroll_mode VARCHAR(16) NOT NULL DEFAULT ''simple'' COMMENT ''simple=一键报名 material=填表+材料+审核''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campus_activity' AND COLUMN_NAME = 'enroll_hint') = 0,
  'ALTER TABLE campus_activity ADD COLUMN enroll_hint VARCHAR(200) NOT NULL DEFAULT '''' COMMENT ''报名须知，展示给学生''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campus_activity' AND COLUMN_NAME = 'enroll_max_files') = 0,
  'ALTER TABLE campus_activity ADD COLUMN enroll_max_files INT NOT NULL DEFAULT 3 COMMENT ''材料最多几个文件''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 报名表：审核状态 + 表单字段 ----------
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''approved'' COMMENT ''approved=已通过 pending=待审核 rejected=已驳回；老数据默认通过''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'real_name') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN real_name VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''报名人姓名''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'student_no') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN student_no VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''学号''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'phone') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN phone VARCHAR(20) NOT NULL DEFAULT '''' COMMENT ''联系电话''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'remark') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN remark VARCHAR(200) NOT NULL DEFAULT '''' COMMENT ''自我介绍/备注''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'audit_reason') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN audit_reason VARCHAR(200) NOT NULL DEFAULT '''' COMMENT ''驳回原因''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'audited_at') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN audited_at DATETIME NULL COMMENT ''审核时间''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND COLUMN_NAME = 'audited_by') = 0,
  'ALTER TABLE activity_enroll ADD COLUMN audited_by BIGINT NULL COMMENT ''审核人（admin_user.id）''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 报名材料表（多文件）----------
CREATE TABLE IF NOT EXISTS activity_enroll_material (
  id BIGINT NOT NULL AUTO_INCREMENT,
  enroll_id BIGINT NOT NULL COMMENT 'activity_enroll.id',
  url VARCHAR(255) NOT NULL COMMENT '上传后的文件地址',
  file_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '原始文件名（便于识别）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_material_enroll (enroll_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动报名上传的材料（一个报名可多条）';

-- ---------- 4. 审核列表常用索引 ----------
SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_enroll' AND INDEX_NAME = 'idx_enroll_status') = 0,
  'ALTER TABLE activity_enroll ADD INDEX idx_enroll_status (activity_id, status)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
