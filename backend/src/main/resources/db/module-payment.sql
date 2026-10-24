-- 外卖支付模块迁移。脚本可以重复执行。
USE campus_life;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='pay_amount_cents');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN pay_amount_cents INT NOT NULL DEFAULT 0 COMMENT ''应付金额（分）''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='out_trade_no');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN out_trade_no VARCHAR(64) NULL COMMENT ''平台订单号''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='transaction_id');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN transaction_id VARCHAR(64) NULL COMMENT ''微信交易号''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='prepay_id');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN prepay_id VARCHAR(64) NULL COMMENT ''预支付ID''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='pay_status');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN pay_status VARCHAR(16) NOT NULL DEFAULT ''unpaid'' COMMENT ''支付状态''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='paid_at');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN paid_at DATETIME NULL', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='cancelled_at');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN cancelled_at DATETIME NULL', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='cancel_reason');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN cancel_reason VARCHAR(100) NULL', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE user_order SET pay_amount_cents = ROUND(total * 100) WHERE pay_amount_cents = 0 AND total > 0;
UPDATE user_order SET pay_status = CASE WHEN status IN ('paid','accepted','delivering','completed') THEN 'paid' WHEN status = 'refunding' THEN 'refunding' WHEN status = 'refunded' THEN 'refunded' WHEN status IN ('cancelled','closed') THEN 'closed' ELSE 'unpaid' END WHERE pay_status IS NULL OR pay_status = '' OR (pay_status = 'unpaid' AND status <> 'created');

SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND INDEX_NAME='uk_order_out_trade_no');
SET @ddl = IF(@i=0, 'ALTER TABLE user_order ADD UNIQUE KEY uk_order_out_trade_no (out_trade_no)', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND INDEX_NAME='uk_order_transaction_id');
SET @ddl = IF(@i=0, 'ALTER TABLE user_order ADD UNIQUE KEY uk_order_transaction_id (transaction_id)', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND INDEX_NAME='idx_order_pay_status');
SET @ddl = IF(@i=0, 'ALTER TABLE user_order ADD KEY idx_order_pay_status (pay_status, created_at)', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS payment_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NULL,
  out_trade_no VARCHAR(64) NOT NULL,
  transaction_id VARCHAR(64) NOT NULL,
  amount_cents INT NOT NULL,
  trade_state VARCHAR(32) NOT NULL,
  raw_body TEXT NOT NULL,
  verified TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payment_transaction (transaction_id),
  KEY idx_payment_order (order_id),
  KEY idx_payment_verified (verified, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refund_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  out_refund_no VARCHAR(64) NOT NULL,
  refund_id VARCHAR(64) NULL,
  amount_cents INT NOT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_refund_out_no (out_refund_no),
  KEY idx_refund_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
