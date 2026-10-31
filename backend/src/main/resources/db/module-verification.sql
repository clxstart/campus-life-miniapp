-- 学生/实名核验状态。
-- 只保存核验结论与审计字段，不保存证件号、证件影像等原始材料。
-- 可重复执行。
USE campus_life;

CREATE TABLE IF NOT EXISTS user_verification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  real_name_status VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT 'none|pending|verified|rejected',
  student_status VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT 'none|pending|verified|rejected',
  provider VARCHAR(32) NOT NULL DEFAULT '' COMMENT '核验来源，如 manual_admin',
  reject_reason VARCHAR(200) NOT NULL DEFAULT '',
  reviewer_id BIGINT DEFAULT NULL COMMENT '管理端审核人',
  verified_at DATETIME DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_verification_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生与实名核验状态（不含证件原文）';
