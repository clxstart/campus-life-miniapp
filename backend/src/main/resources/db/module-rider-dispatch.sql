-- 楼栋责任区与自动派单。
-- 可重复执行：建表用 IF NOT EXISTS，加列前查 information_schema。
USE campus_life;

-- 每栋楼配置一个默认责任骑手；一名骑手可覆盖多个楼栋（多对一）
CREATE TABLE IF NOT EXISTS building (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL COMMENT '楼栋名，如 学生公寓6号楼',
  match_keywords VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址匹配关键词，逗号分隔',
  default_rider_id BIGINT DEFAULT NULL COMMENT '默认责任骑手（admin_user.id, role=rider）',
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_building_school_name (school_id, name),
  KEY idx_building_rider (default_rider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='楼栋责任区（默认骑手）';

-- 订单上的派单结果
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='building_id');
SET @ddl = IF(@missing,
  'ALTER TABLE user_order ADD COLUMN building_id BIGINT DEFAULT NULL, ADD COLUMN dispatch_status VARCHAR(16) NOT NULL DEFAULT ''none'', ADD COLUMN actual_rider_id BIGINT DEFAULT NULL, ADD COLUMN dispatch_note VARCHAR(200) NOT NULL DEFAULT '''', ADD COLUMN delivery_pay_cents INT NOT NULL DEFAULT 0, ADD COLUMN delivery_pay_recorded TINYINT NOT NULL DEFAULT 0',
  'SELECT "user_order dispatch columns exist"');
PREPARE dispatch_stmt FROM @ddl; EXECUTE dispatch_stmt; DEALLOCATE PREPARE dispatch_stmt;

-- 接管时负责人可以逐单选择是否把配送费计为骑手报酬（方案 §7.2），这里存这个选择
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='delivery_pay_counted');
SET @ddl = IF(@missing,
  'ALTER TABLE user_order ADD COLUMN delivery_pay_counted TINYINT NOT NULL DEFAULT 1 COMMENT ''1=记报酬 0=不计酬''',
  'SELECT "user_order delivery_pay_counted exists"');
PREPARE dispatch_cnt_stmt FROM @ddl; EXECUTE dispatch_cnt_stmt; DEALLOCATE PREPARE dispatch_cnt_stmt;

-- 改派 / 接管 / 退回的操作留痕
CREATE TABLE IF NOT EXISTS order_dispatch_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL COMMENT 'auto|reassign|takeover|queue|release',
  from_rider_id BIGINT DEFAULT NULL,
  to_rider_id BIGINT DEFAULT NULL,
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'system' COMMENT 'system|admin',
  actor_id BIGINT DEFAULT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_dispatch_order (order_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送派单与改派记录';
