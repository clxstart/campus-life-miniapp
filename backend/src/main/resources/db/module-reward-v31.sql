-- V3.1 新人礼包与积分兑换：商品池、兑换记录、券的完整生命周期字段、订单券金额。
--
-- 关键口径：
--   * 券只抵平台内订单（takeout），平台补贴、商家按原价结算；
--   * 100 积分 = 1 元，券面额 value_cents 严格等于 price_p（分），不允许自定义折扣；
--   * coupon.status 复用为状态机：1=可用 2=锁定 3=已核销 0=作废。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

-- ============ 1. 兑换商品池 ============
CREATE TABLE IF NOT EXISTS reward_item (
  item_key       VARCHAR(32) PRIMARY KEY,
  name           VARCHAR(32)  NOT NULL,
  zone           VARCHAR(16)  NOT NULL DEFAULT 'virtual' COMMENT 'virtual|mixed|limited',
  price_p        INT          NOT NULL,
  cash_cents     INT          NOT NULL DEFAULT 0 COMMENT '混合兑现金部分，V3.1 恒为 0',
  stock          INT          NOT NULL DEFAULT 0 COMMENT '0=售罄（不用负数表示无限）',
  per_user_limit INT          NOT NULL DEFAULT 1,
  coupon_title   VARCHAR(64)  NOT NULL,
  coupon_amount  VARCHAR(16)  NOT NULL DEFAULT '',
  coupon_expire  VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '展示用有效期文案',
  threshold_cents INT         NOT NULL DEFAULT 0 COMMENT '券使用门槛（分），0=无门槛',
  expire_days    INT          NOT NULL DEFAULT 30 COMMENT '兑换后券有效天数',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  sort           INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兑换商品池';

INSERT INTO reward_item (item_key, name, zone, price_p, cash_cents, stock, per_user_limit,
                         coupon_title, coupon_amount, coupon_expire, threshold_cents, expire_days, enabled, sort) VALUES
  ('takeout_1',    '外卖立减 1 元',    'virtual', 100, 0, 200, 3, '外卖立减券', '1.00 元', '30天', 2000, 30, 1, 10),
  ('takeout_0_5',  '外卖立减 0.5 元',  'virtual',  50, 0, 300, 6, '外卖立减券', '0.50 元', '30天', 1500, 30, 1, 20),
  ('takeout_0_25', '外卖立减 0.25 元', 'virtual',  25, 0, 400, 8, '外卖立减券', '0.25 元', '30天', 1500, 30, 1, 30),
  ('takeout_0_1',  '外卖立减 0.1 元',  'virtual',  10, 0, 500, 10,'外卖立减券', '0.10 元', '30天', 1000, 30, 1, 40)
ON DUPLICATE KEY UPDATE item_key = item_key;

-- ============ 2. 兑换记录 ============
CREATE TABLE IF NOT EXISTS redeem_record (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  item_key   VARCHAR(32) NOT NULL,
  price_p    INT         NOT NULL,
  coupon_id  BIGINT      NOT NULL COMMENT '生成的 coupon.id',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_redeem_user (user_id, created_at),
  KEY idx_redeem_item (item_key, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兑换记录；限购次数按 idx_redeem_item 统计';

-- ============ 3. coupon 扩列 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='value_cents')=0,
  'ALTER TABLE coupon ADD COLUMN value_cents INT NOT NULL DEFAULT 0 COMMENT ''抵扣额（分）；amount 只是展示文案，算钱只认这个''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='threshold_cents')=0,
  'ALTER TABLE coupon ADD COLUMN threshold_cents INT NOT NULL DEFAULT 0 COMMENT ''使用门槛（分），0=无门槛''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='scope')=0,
  'ALTER TABLE coupon ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT ''takeout'' COMMENT ''适用品类，本期只有 takeout''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='expire_on')=0,
  'ALTER TABLE coupon ADD COLUMN expire_on DATE NULL COMMENT ''过期日；旧 expire_at 保留一版兼容''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='locked_at')=0,
  'ALTER TABLE coupon ADD COLUMN locked_at DATETIME NULL COMMENT ''下单锁定时间''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='used_at')=0,
  'ALTER TABLE coupon ADD COLUMN used_at DATETIME NULL COMMENT ''核销时间''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='order_id')=0,
  'ALTER TABLE coupon ADD COLUMN order_id BIGINT NULL COMMENT ''锁定/核销订单''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND COLUMN_NAME='source')=0,
  'ALTER TABLE coupon ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''seed'' COMMENT ''seed|newbie|redeem|admin''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon' AND INDEX_NAME='idx_coupon_user_state')=0,
  'ALTER TABLE coupon ADD KEY idx_coupon_user_state (user_id, status, expire_on)',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 老数据不自动回填：历史券只有展示文案（amount），没有面额，按 0 元处理；
-- 需要使用的券一律由兑换/新人礼包重新发放，避免"按文案猜面额"猜到错误金额。

-- ============ 4. 订单券字段 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='coupon_id')=0,
  'ALTER TABLE user_order ADD COLUMN coupon_id BIGINT NULL COMMENT ''本单使用的券''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='coupon_discount')=0,
  'ALTER TABLE user_order ADD COLUMN coupon_discount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''券抵扣额（元），平台补贴，商家收入不减''',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT '新人礼包与兑换表结构就绪' AS result;