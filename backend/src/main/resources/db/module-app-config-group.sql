-- 微信群聊入口配置（在 app_config 基础上追加）
--
-- 为什么和 contact.* 分开：一对一咨询（论文指导）和群聊是两码事，
-- 二维码不同、文案不同、开关也应该能各自独立控制。
--
-- 幂等脚本。

INSERT INTO app_config (config_key, config_value, remark) VALUES
  ('group.wechat_qr', '', '微信群二维码图片地址（管理端上传后自动写入）'),
  ('group.title',     '校园微信群', '页面标题'),
  ('group.hint',      '扫码进群，和同学一起交流；如二维码过期请找管理员要新的', '二维码下方提示文案'),
  ('group.enabled',   '1', '微信入口开关：1=开启 0=收起')
ON DUPLICATE KEY UPDATE config_key = config_key;
