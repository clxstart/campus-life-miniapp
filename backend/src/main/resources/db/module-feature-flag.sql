-- 功能开关：线上出问题时能一键关掉某个功能，不用改代码、不用重启、不用发版。
--
-- 为什么需要：以前某个功能（比如支付通道、AI、上传）出问题，只能改代码 + 重新部署，
-- 期间用户一直在报错。有了开关，管理端点一下就能止血，恢复后再打开。
--
-- 本脚本幂等，可重复执行。

CREATE TABLE IF NOT EXISTS feature_flag (
  flag_key   VARCHAR(64)  NOT NULL COMMENT '开关标识，如 pay.enabled',
  enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=开启 0=关闭',
  remark     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '给学生看的提示或运营备注',
  updated_by VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人',
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='功能开关（按 key 控制功能可见性）';

-- 初始开关：全部默认开启（1），保证行为与上线前一致
INSERT INTO feature_flag (flag_key, enabled, remark) VALUES
  ('ai_chat.enabled',        1, 'AI 陪聊'),
  ('upload.enabled',         1, '图片/材料上传'),
  ('pay.enabled',            1, '在线支付（关闭后订单只能下不能付）'),
  ('enroll.material.enabled',1, '活动材料报名（关闭后降级为一键报名）'),
  ('circle_chat.enabled',    1, '圈子群聊'),
  ('post.publish.enabled',   1, '发帖与评论')
ON DUPLICATE KEY UPDATE flag_key = flag_key;
