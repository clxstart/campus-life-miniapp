-- 前端错误上报
--
-- 为什么需要：小程序的 wx.onError、H5 的 window.onerror 目前都没接。
-- 用户在真机上遇到白屏/点击无反应时，服务端日志里一条记录都没有 ——
-- 这类问题只能等用户主动投诉，而且往往复现不了。
--
-- 设计取舍：
--   - 只存"能定位问题"的最小信息，不存用户内容；
--   - 按 (端 + 页面 + 错误摘要) 聚合查询，避免同一个错误刷满列表；
--   - 保留 30 天，由定时任务或手工清理。
--
-- 幂等脚本。

CREATE TABLE IF NOT EXISTS client_error (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  client     VARCHAR(16)  NOT NULL COMMENT 'miniapp / web',
  page       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '页面路径或路由',
  message    VARCHAR(500) NOT NULL COMMENT '错误摘要',
  stack      VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '堆栈（截断保存）',
  user_agent VARCHAR(300) NOT NULL DEFAULT '',
  user_id    BIGINT       NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_client_error_time (created_at),
  KEY idx_client_error_page (client, page)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='前端运行时错误上报';
