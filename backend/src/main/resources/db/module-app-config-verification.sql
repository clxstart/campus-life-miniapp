-- V3.1 学生认证：认证细则配置（平台一套，存 app_config）。
--
-- 为什么放 app_config 而不是 school.verify_config：
--   现在只有一所学校，就是一套规则，没必要先建一套多校数据模型；
--   AppConfigService 已有 30 秒缓存 + 写后主动失效，后台改完立即生效；
--   将来多校时键名不变，只把数据源换成 school.verify_config，GET /api/verification/config 结构一个字都不用改。
--
-- 注意：能改这些键的只有超管（AppConfigController 已补 super 角色校验）。
-- 以前 AppConfigController 没有任何角色校验，校管也能写全局 app_config —— 那只是二维码和文案；
-- 一旦装上「学号正则」和「审核方式」，任何一个校管都能改掉全平台的认证规则。
--
-- 幂等：已存在的不覆盖（后台改过就不能被重跑脚本重置回去）。

USE campus_life;

INSERT INTO app_config (config_key, config_value, remark) VALUES
  ('verification.require.real_name',  '1', '真实姓名是否必填：1=必填 0=选填'),
  ('verification.require.student_no', '1', '学号是否必填：1=必填 0=选填'),
  ('verification.require.college',    '0', '学院是否必填：1=必填 0=选填（学院打开后抽查才有「学号与学院是否匹配」的依据）'),
  ('verification.require.phone',      '0', '手机号是否必填：1=必填 0=选填（本期无对应表单字段，仅预留）'),
  ('verification.student_no_pattern', '^(20[0-9]{2})[0-9]{6,10}$', '学号正则（auto 判定 + 前端格式提示）；为空时禁止 auto 模式，系统会强制回落 manual'),
  ('verification.review_mode',        'manual', 'manual=全部人工审核；auto=正则命中直接通过。注意：auto 仅适合当前单校阶段，第二所学校进来时学号规则必然冲突，届时要么迁到 school.verify_config，要么关掉 auto 全走人工'),
  ('verification.daily_limit',        '3', '每人每天最多提交次数（1-10，越界按边界截断）')
ON DUPLICATE KEY UPDATE config_key = config_key;

SELECT '认证细则配置就绪' AS result;