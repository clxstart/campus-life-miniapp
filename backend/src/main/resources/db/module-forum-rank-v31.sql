-- V3.1 论坛日榜 / 周榜。
--
-- 为什么要有快照表：实时算排名会随互动不断抖动，用户看到自己第 3 名转眼变第 4 名会投诉；
-- 落 forum_rank 快照后，"当日第几名、发多少分"就是可复核的最终结果，重结算也只覆盖分数、
-- 不动已发放的奖励。
--
-- 设计要点：
--   1. 一行 = 一个 (榜单类型, 周期, 用户)，uk_rank 保证重复结算不会插出第二行。
--   2. awarded 是「奖励是否已发」的开关，重结算只补 awarded=0 的行，不会重复发分。
--   3. 周期用字符串存（2026-11-08 / 2026-W45）：日期与 ISO 周都能字典序比较，
--      管理端的「最近一期」用 MAX(period_key) 就能拿到，不必再按类型写两套日期解析。
--   4. 时区一律 Asia/Shanghai（与 CircleStreakService.BEIJING / PointsService.SHANGHAI 同一口径）。
--
-- 幂等：整份脚本可重复执行。

USE campus_life;

-- ============ 1. 榜单快照 ============
CREATE TABLE IF NOT EXISTS forum_rank (
  id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
  board      VARCHAR(8)  NOT NULL COMMENT 'daily|weekly',
  period_key VARCHAR(16) NOT NULL COMMENT '2026-11-08 / 2026-W45',
  user_id    BIGINT      NOT NULL,
  score      INT         NOT NULL COMMENT '发帖3 + 评论2(≥5字) + 被赞1',
  rank_no    INT         NOT NULL,
  awarded    TINYINT     NOT NULL DEFAULT 0 COMMENT '1=奖励已发放，重结算不再重复发',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rank (board, period_key, user_id),
  KEY idx_rank_period (board, period_key, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='榜单快照；结算后不再变动，作为发奖依据与用户可见的最终结果';

-- ============ 2. 任务目录：上榜奖励 ============
-- P/G 与设计稿 A-9 一致（日榜 10P/15G，周榜 15P/25G）。
-- daily_cap=1：同一个人同一个榜一天只发一次；幂等另有 biz_key=periodKey 兜底（跨周期不冲突）。
-- 已存在的不覆盖：后台改过的数值不能因为重跑脚本被重置回去。
INSERT INTO task_catalog (task_key, module, name, p_reward, g_reward, daily_cap, enabled, sort) VALUES
  ('rank_daily',  'points', '论坛日榜上榜', 10, 15, 1, 1, 90),
  ('rank_weekly', 'points', '论坛周榜上榜', 15, 25, 1, 1, 91)
ON DUPLICATE KEY UPDATE task_key = task_key;

SELECT '论坛榜单表结构就绪' AS result;