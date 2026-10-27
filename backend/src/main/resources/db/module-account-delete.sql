-- 账号注销支持：user 表增加 status
--
-- 法规要求（个人信息保护法第 47 条）与小程序审核都要求提供账号注销。
-- 注销采用「软删除 + 匿名化」，而不是物理删除：
--   1. 订单、支付、审计记录需要按法规保留，不能因为注销就凭空消失；
--   2. 物理删除会让这些记录变成孤儿，对账时就说不清了。
-- 所以把资料清空、openid 置空、status 置 0，并让 token 立即失效。
-- openid 置空后，用户下次用同一个微信登录会被当成新用户重新建号 —— 相当于真的重新开始。
--
-- 幂等脚本。

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE `user` ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT ''1=正常 0=已注销''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'deleted_at') = 0,
  'ALTER TABLE `user` ADD COLUMN deleted_at DATETIME NULL COMMENT ''注销时间''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
