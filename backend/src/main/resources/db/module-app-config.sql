-- 通用运营配置（键值）
--
-- 为什么单独建表而不是复用 ai_chat_config：那张表语义是「AI 话术」，
-- 把客服二维码塞进去以后会越用越乱。这里做一张真正通用的配置表。
--
-- 首批用途：论文指导的咨询入口（微信二维码 + 微信号 + 文案）。
--
-- 幂等脚本。

CREATE TABLE IF NOT EXISTS app_config (
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(500) NOT NULL DEFAULT '',
  remark       VARCHAR(200) NOT NULL DEFAULT '' COMMENT '给人看的说明',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用运营配置';

INSERT INTO app_config (config_key, config_value, remark) VALUES
  ('contact.wechat_qr',  '', '咨询二维码图片地址（管理端上传后自动写入）'),
  ('contact.wechat_id',  '', '微信号，展示给学生手动添加'),
  ('contact.title',      '论文指导咨询', '页面标题'),
  ('contact.hint',       '扫码或搜索微信号添加，备注「年级+专业」通过更快', '二维码下方的提示文案'),
  ('contact.enabled',    '1', '咨询入口开关：1=开启 0=收起入口')
ON DUPLICATE KEY UPDATE config_key = config_key;
