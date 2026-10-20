-- AI 机器人配置：对话日志 + 可配置话术
-- 手动执行；可重复执行。
--
-- 背景：
--   1. ai_chat_rule 已有 24 条规则，但管理端只有只读接口，运营改不了话术。
--   2. 没有日志，运营不知道「哪些问题没被接住」—— 这是最该有的数据。
--   3. 欢迎语写死在小程序 ai_chat.js 里，和兜底话术散在两个地方。
USE campus_life;

-- ============ 1. 对话日志（未命中统计的基础）============
CREATE TABLE IF NOT EXISTS ai_chat_log (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT      NULL,
  school_id  BIGINT      NULL,
  message    VARCHAR(200) NOT NULL COMMENT '用户原话（截断到 200 字）',
  matched    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=命中规则 0=未命中',
  category   VARCHAR(32)  NULL COMMENT '命中的规则分类；未命中为 fallback',
  rule_id    BIGINT       NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ai_log_matched (matched, created_at),
  KEY idx_ai_log_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 对话日志';

-- ============ 2. 可配置话术（欢迎语 / 兜底语）============
CREATE TABLE IF NOT EXISTS ai_chat_config (
  config_key   VARCHAR(32) PRIMARY KEY,
  config_value VARCHAR(500) NOT NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 话术配置';

-- 欢迎语：小程序进入对话页的第一句话
INSERT INTO ai_chat_config (config_key, config_value) VALUES
('welcome_reply', '你好呀，我是你的校园小助手。可以问我今天吃什么、有什么活动、有兼职吗，也可以随便聊聊。'),
('welcome_quick', '今天吃什么,有什么活动,我该学习了吗,陪我聊聊')
ON DUPLICATE KEY UPDATE config_key = config_key;

SELECT config_key, LEFT(config_value, 40) AS 内容 FROM ai_chat_config;
