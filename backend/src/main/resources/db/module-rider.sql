-- Rider workflow and delivery contact snapshots. Safe to apply repeatedly.
USE campus_life;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='rider_id')=0,
  'ALTER TABLE user_order ADD COLUMN rider_id BIGINT NULL', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='rider_name')=0,
  CONCAT('ALTER TABLE user_order ADD COLUMN rider_name VARCHAR(32) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='accepted_at')=0,
  'ALTER TABLE user_order ADD COLUMN accepted_at DATETIME NULL', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='picked_at')=0,
  'ALTER TABLE user_order ADD COLUMN picked_at DATETIME NULL', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='delivered_at')=0,
  'ALTER TABLE user_order ADD COLUMN delivered_at DATETIME NULL', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='contact_name')=0,
  CONCAT('ALTER TABLE user_order ADD COLUMN contact_name VARCHAR(32) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='user_order' AND column_name='contact_phone')=0,
  CONCAT('ALTER TABLE user_order ADD COLUMN contact_phone VARCHAR(20) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='admin_user' AND column_name='rider_status')=0,
  'ALTER TABLE admin_user ADD COLUMN rider_status VARCHAR(16) NOT NULL DEFAULT ''idle''', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='admin_user' AND column_name='phone')=0,
  CONCAT('ALTER TABLE admin_user ADD COLUMN phone VARCHAR(20) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='shop' AND column_name='address')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN address VARCHAR(128) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campus_life' AND table_name='shop' AND column_name='contact_phone')=0,
  CONCAT('ALTER TABLE shop ADD COLUMN contact_phone VARCHAR(20) NOT NULL DEFAULT ', QUOTE('')), 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='user_order' AND index_name='idx_rider')=0,
  'ALTER TABLE user_order ADD INDEX idx_rider (rider_id,status)', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='user_order' AND index_name='idx_order_status_shop_created')=0,
  'ALTER TABLE user_order ADD INDEX idx_order_status_shop_created (status,shop_id,created_at)', 'SELECT 1');
PREPARE rider_stmt FROM @ddl; EXECUTE rider_stmt; DEALLOCATE PREPARE rider_stmt;

-- Optional local demo rider: rider01 / rider123 (BCrypt), school 1.
INSERT IGNORE INTO admin_user(username,password_hash,real_name,role,school_id,phone,rider_status)
VALUES ('rider01','$2a$10$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6','校园骑手','rider',1,'','idle');
