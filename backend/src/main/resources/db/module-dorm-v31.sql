-- V3.1 宿舍域：宿舍 / 舍友关系 / 当日签到汇总三张表。
--
-- 为什么是这三张（设计稿「四、数据表与字段」）：
--   dorm        一个宿舍一行。邀请码全站唯一（uk_dorm_code）；解散只改 status=0，不物理删除，
--               历史签到记录因此还在，管理员误解散也能恢复。
--   dorm_member 舍友关系。uk_dorm_user(user_id) 是「一人一宿舍」的**数据库级**保证 ——
--               多人多宿舍会出现"跨宿舍刷全员签到"，服务层的「请先退出当前宿舍」只是友好文案，
--               真正兜底的是这个唯一键。
--   dorm_sign   一天一个宿舍一行。(dorm_id, biz_date) 唯一键 + 条件更新
--               （signed_count >= member_count 且 all_done=0）保证并发下全员奖励只发一次。
--
-- 成员数固化（对应测试手册第 11 条「中途有人退出，当日不再补发」）：
--   member_count 在当日第一行创建时写入，之后不再变。判定基数一旦跟着成员数变，
--   「最后一刻退出」就能把 7/8 变成 7/7 白拿一次奖励 —— 判定基数必须冻结。
--
-- 时区：biz_date 一律北京时间自然日，与 sign_roll / PointsService.SHANGHAI 同一口径，
--       不依赖 MySQL 的 CURDATE()，否则换一台 UTC 服务器整个判定会错位一天。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

-- ============ 1. 宿舍 ============
CREATE TABLE IF NOT EXISTS dorm (
  id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
  school_id      BIGINT       NULL COMMENT '所属学校；NULL=用户未绑定学校。本期不做跨校互通',
  name           VARCHAR(32)  NOT NULL COMMENT '如 6号楼312',
  building       VARCHAR(32)  NOT NULL DEFAULT '',
  room           VARCHAR(16)  NOT NULL DEFAULT '',
  leader_id      BIGINT       NOT NULL COMMENT '舍长；舍长不能直接退出（除非宿舍里只剩他一人）',
  invite_code    CHAR(6)      NOT NULL COMMENT '6 位邀请码，全站唯一',
  member_limit   TINYINT      NOT NULL DEFAULT 8 COMMENT '成员上限；全员判定按实际成员数，不按上限',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=已解散',
  dismissed_by   BIGINT       NULL COMMENT '解散操作人（管理员 id）',
  dismissed_at   DATETIME     NULL COMMENT '解散时间',
  dismiss_reason VARCHAR(200) NOT NULL DEFAULT '' COMMENT '解散原因，管理端必填',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dorm_code (invite_code),
  KEY idx_dorm_school (school_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宿舍；一人最多属于一个';

-- ============ 2. 舍友关系 ============
CREATE TABLE IF NOT EXISTS dorm_member (
  id        BIGINT      PRIMARY KEY AUTO_INCREMENT,
  dorm_id   BIGINT      NOT NULL,
  user_id   BIGINT      NOT NULL,
  role      VARCHAR(16) NOT NULL DEFAULT 'member' COMMENT 'leader|member',
  joined_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dorm_user (user_id) COMMENT '一人一宿舍',
  KEY idx_member_dorm (dorm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宿舍成员；uk_dorm_user 是"一人一宿舍"的数据库级保证';

-- ============ 3. 当日签到汇总 ============
CREATE TABLE IF NOT EXISTS dorm_sign (
  id           BIGINT   PRIMARY KEY AUTO_INCREMENT,
  dorm_id      BIGINT   NOT NULL,
  biz_date     DATE     NOT NULL COMMENT '北京时间自然日',
  signed_count INT      NOT NULL DEFAULT 0 COMMENT '当日已签到的成员数（按成员集合实时重算，不是盲加）',
  member_count INT      NOT NULL DEFAULT 0 COMMENT '当日首次签到时固化的成员数，当日不再变动',
  all_done     TINYINT  NOT NULL DEFAULT 0 COMMENT '1=当日已达成全员且已发分',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dorm_sign (dorm_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宿舍当日签到汇总；全员达成时 all_done=1';

-- ============ 4. 任务目录：宿舍两个奖励 ============
-- P/G 与设计稿 A-34/A-35 一致（全员签到 5P/8G，周榜第一 0P/30G）。
-- daily_cap=1：一天最多发一次；幂等另有 biz_key（dorm:{id}:{date} / week:{periodKey}）兜底。
-- 已存在的不覆盖：后台改过的数值不能因为重跑脚本被重置回去。
INSERT INTO task_catalog (task_key, module, name, p_reward, g_reward, daily_cap, enabled, sort) VALUES
  ('dorm_all_sign',   'dorm',   '宿舍全员签到',  5,  8, 1, 1, 70),
  ('dorm_week_first', 'points', '宿舍周榜第一',  0, 30, 1, 1, 71)
ON DUPLICATE KEY UPDATE task_key = task_key;

-- ============ 5. 宿舍冠军奶茶券（降级用）============
-- 设计稿：周榜第一每人一杯奶茶，但"有商户券库存才发"，没谈成商户就不承诺实物。
-- 这里只准备商品行（库存默认 0，由运营/商户库存写入后才生效）：
--   reward_item 属于「新人礼包与积分兑换」功能，可能还没建表 —— 用 information_schema 守卫，
--   保证本脚本在任何执行顺序下都能重复跑。
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reward_item')>0,
  'INSERT INTO reward_item (item_key, name, zone, price_p, cash_cents, stock, per_user_limit,
                            coupon_title, coupon_amount, coupon_expire, threshold_cents, expire_days, enabled, sort) VALUES
     (''milk_tea'', ''宿舍冠军奶茶券'', ''limited'', 0, 0, 0, 1, ''奶茶券（中杯）'', ''1 杯'', ''30天'', 0, 30, 1, 90)
   ON DUPLICATE KEY UPDATE item_key = item_key',
  'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT '宿舍域表结构就绪' AS result;