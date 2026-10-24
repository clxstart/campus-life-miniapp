-- Merchant onboarding and ownership. Safe to run repeatedly on campus_life.
USE campus_life;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='admin_user' AND column_name='shop_id')=0,
  'ALTER TABLE admin_user ADD COLUMN shop_id BIGINT NULL', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='contact_phone')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN contact_phone VARCHAR(20) NOT NULL DEFAULT ', CHAR(39), CHAR(39)), 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='address')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN address VARCHAR(128) NOT NULL DEFAULT ', CHAR(39), CHAR(39)), 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='owner_name')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN owner_name VARCHAR(32) NOT NULL DEFAULT ', CHAR(39), CHAR(39)), 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='intro')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN intro VARCHAR(300) NOT NULL DEFAULT ', CHAR(39), CHAR(39)), 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='open_time')=0,
  'ALTER TABLE shop ADD COLUMN open_time VARCHAR(5) NOT NULL DEFAULT ''09:00''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='close_time')=0,
  'ALTER TABLE shop ADD COLUMN close_time VARCHAR(5) NOT NULL DEFAULT ''21:00''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='is_open')=0,
  'ALTER TABLE shop ADD COLUMN is_open TINYINT NOT NULL DEFAULT 1', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS merchant_apply (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shop_name VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  contact_name VARCHAR(32) NOT NULL,
  contact_phone VARCHAR(20) NOT NULL,
  address VARCHAR(128) NOT NULL,
  school_id BIGINT NOT NULL,
  license_no VARCHAR(64) NOT NULL,
  license_image VARCHAR(255) NOT NULL,
  shop_image VARCHAR(255) NOT NULL,
  intro VARCHAR(300),
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  reject_reason VARCHAR(200),
  audit_by BIGINT,
  audit_at DATETIME,
  created_shop_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_merchant_apply_status (status, created_at),
  KEY idx_merchant_apply_school (school_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refund_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  out_refund_no VARCHAR(64) NOT NULL,
  refund_id VARCHAR(64) NOT NULL DEFAULT '',
  amount_cents INT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_refund_order (order_id),
  KEY idx_refund_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
