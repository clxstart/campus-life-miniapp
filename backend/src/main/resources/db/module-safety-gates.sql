-- 安全闸门开关：校外岗位、有偿接单、替课、自由抢单、通用钱包、自助认证。
-- 全部默认关闭（enabled=0），必须由管理员在后台显式开启后才生效。
-- 可重复执行：INSERT IGNORE 不会覆盖运营已经调整过的值。
USE campus_life;

INSERT IGNORE INTO feature_flag(flag_key, enabled, remark, updated_by) VALUES
  ('wallet.enabled', 0, '通用钱包/充值/提现尚未取得支付机构书面能力确认，保持关闭', 'system'),
  ('job.offsite.enabled', 0, '校外招聘资质未核验，校外岗位暂不开放发布与报名', 'system'),
  ('job.paid.enabled', 0, '有偿接单需学生身份核验流程确认后开启', 'system'),
  ('job.category.takeover_class.enabled', 0, '替课需校方规则确认，默认关闭', 'system'),
  ('rider.grab.enabled', 0, '默认使用楼栋责任区自动派单，不开放全校自由抢单', 'system'),
  ('verification.student.enabled', 0, '学生身份自助申请入口未接入可信核验源，保持关闭', 'system');
