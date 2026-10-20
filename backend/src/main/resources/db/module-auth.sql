-- 登录鉴权：user 表增加 openid（微信唯一标识），并建立唯一索引。
-- 手动执行；可重复执行。
USE campus_life;

SET @ddl = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='campus_life' AND table_name='user' AND column_name='openid') = 0,
  'ALTER TABLE `user` ADD COLUMN openid VARCHAR(64) NULL AFTER nickname',
  'SELECT "openid already exists"'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一索引：同一微信用户只对应一个账号（NULL 不参与唯一约束，不影响演示数据）
SET @idx = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='campus_life' AND table_name='user' AND index_name='uk_user_openid') = 0,
  'ALTER TABLE `user` ADD UNIQUE KEY uk_user_openid (openid)',
  'SELECT "uk_user_openid already exists"'));
PREPARE stmt2 FROM @idx; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- 给演示用户补上稳定的 openid，便于开发登录反复使用同一账号
UPDATE `user` SET openid = CONCAT('dev-', id) WHERE openid IS NULL;
