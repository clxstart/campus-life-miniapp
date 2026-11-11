-- 商业关键链路一致性整改（审计 2026-10-31-commerce-critical-path）
-- 可重复执行：每条 DDL 先查 information_schema 再决定是否执行。
-- 执行顺序：在 module-payment.sql、module-rider*.sql 之后执行。
-- 回滚：见 docs/高可用部署与故障切换.md「数据库迁移与回滚」一节（只删本文件新增的表/列/索引，不动原有数据）。
--
-- 2026-11-01 follow-up：新增 refund_record 每订单唯一约束（第 6 节）。
-- 该约束是「同一订单并发退款最多一条可提交渠道的退款单」的数据库兜底；
-- 执行前请先跑第 6 节的重复数据核对查询。若历史数据同一订单已有多条退款记录，
-- ALTER 会因重复键失败（不会静默跳过），需人工核对保留真实流水后再执行；不要为了迁移删除业务流水。

-- ------------------------------------------------------------
-- 1. 支付异常单（finding-04）：已在微信侧扣款、但本地无法正常入账的交易
--    kind: PAID_AFTER_CLOSE 关单后到账 / AMOUNT_MISMATCH 金额不符 /
--          UNKNOWN_ORDER 本地无此订单 / DUPLICATE_PAYMENT 同单二次扣款
--    status: open 待处理 / refund_submitted 已提交退款 / resolved 已解决 / manual 需人工
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_exception (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NULL,
  out_trade_no VARCHAR(64) NOT NULL,
  transaction_id VARCHAR(64) NOT NULL,
  amount_cents INT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'open',
  out_refund_no VARCHAR(64) NULL,
  note VARCHAR(255) NOT NULL DEFAULT '',
  attempts INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payexc_txn_kind (transaction_id, kind),
  KEY idx_payexc_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 2. 对账可重试状态（finding-11）：记录最近一次渠道核验时间、次数与错误，异常不再被静默吞掉
-- ------------------------------------------------------------
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='pay_check_at');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN pay_check_at DATETIME NULL COMMENT ''最近一次渠道对账时间''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='pay_check_attempts');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN pay_check_attempts INT NOT NULL DEFAULT 0 COMMENT ''渠道对账尝试次数''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='pay_check_error');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN pay_check_error VARCHAR(255) NULL COMMENT ''最近一次对账错误''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 3. 下单幂等键（finding-05）：同一用户同一幂等键只能对应一笔订单
-- ------------------------------------------------------------
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND COLUMN_NAME='idempotency_key');
SET @ddl = IF(@c=0, 'ALTER TABLE user_order ADD COLUMN idempotency_key VARCHAR(64) NULL COMMENT ''客户端下单幂等键''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_order' AND INDEX_NAME='uk_order_user_idem');
SET @ddl = IF(@i=0, 'ALTER TABLE user_order ADD UNIQUE KEY uk_order_user_idem (user_id, idempotency_key)', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 4. 管理端令牌版本（finding-03）：停用/角色变更时 +1，旧令牌立即失效
-- ------------------------------------------------------------
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_user' AND COLUMN_NAME='token_version');
SET @ddl = IF(@c=0, 'ALTER TABLE admin_user ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT ''令牌版本，停用/改权限时递增''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 首次登录强制改密标记（finding-account）：一次性初始密码只能用一次，改密后清零。
-- 存量账号默认 0，避免上线迁移后把现有管理员/商家/骑手全部锁在改密页。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_user' AND COLUMN_NAME='must_change_password');
SET @ddl = IF(@c=0, 'ALTER TABLE admin_user ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT ''1=首次登录必须改密''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 凭据轮换时间：存量账号为 NULL。生产启动守卫会要求所有启用中的商家/骑手都先轮换，
-- 防止历史「手机号后 6 位」等可预测初始密码未经处理就上线。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_user' AND COLUMN_NAME='credential_rotated_at');
SET @ddl = IF(@c=0, 'ALTER TABLE admin_user ADD COLUMN credential_rotated_at DATETIME NULL COMMENT ''最近一次初始密码生成/重置/本人改密时间；NULL=旧凭据未轮换''', 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 5. 周期任务租约锁（finding-10/11）：多实例时同一任务同一时刻只有一个节点执行。
--    以数据库时钟判断过期，避免节点间时钟漂移；锁只用于减少重复，资金副作用另有唯一键兜底。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduler_lock (
  name VARCHAR(64) PRIMARY KEY,
  owner VARCHAR(128) NOT NULL DEFAULT '',
  locked_until DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 6. 退款账本每订单唯一（2026-11-01 follow-up：并发退款重复提交）
--
--    业务模型：一次外卖订单最多产生一次有效退款。用户退款、商家拒单退款、关单后到账补偿
--    互斥地发生在不同订单状态下，因此每订单一条 refund_record 与业务一致。
--    历史数据核对（执行 ALTER 前必须为空；有结果说明同一订单存在多条退款记录）：
--
--      SELECT order_id, COUNT(*) AS cnt, GROUP_CONCAT(out_refund_no ORDER BY id) AS refund_nos
--      FROM refund_record GROUP BY order_id HAVING COUNT(*) > 1;
--
--    若查询有结果：先人工确认哪一条是真实渠道流水（可对照 payment_record / 微信后台），
--    其余记录改写为 status='failed' 并保留（不要 DELETE 业务流水），再执行本脚本。
--    回滚：DROP INDEX uk_refund_order ON refund_record;（只删索引，不删数据）
-- ------------------------------------------------------------
SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='refund_record' AND INDEX_NAME='uk_refund_order');
SET @ddl = IF(@i=0, 'ALTER TABLE refund_record ADD UNIQUE KEY uk_refund_order (order_id)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
