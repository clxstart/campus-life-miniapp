-- ============================================================
-- F10 交易任务埋点 · 二手成交流程（V3.1）
--
-- 背景：二手市场原来只有「私聊卖家」（goods.contact_count +1），没有「成交」这个事件，
-- 也就没有积分回路。本脚本新增 market_trade：买家发起 → 卖家确认的两步确认，
-- 确认后双方各得积分（market_trade 任务，5P/12G，日限 2 笔，见 module-points-v31.sql）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 只对本地 dev 库 campus_life 执行；不涉及资金流（没有担保、没有支付）。
--
-- 商品状态取值（实施前已核实）：
--   0 = 下架 / 未过审（发布时审核未通过即写 0）
--   1 = 在售（列表查询按 status=1 过滤）
--   2 = 已售出（本次新增，confirm 时写入）
-- 因为现有列表/详情查询都带 status=1，已售商品会自动从市场列表消失，无需改前端筛选逻辑。
-- ============================================================
USE campus_life;

CREATE TABLE IF NOT EXISTS market_trade (
  id           BIGINT      PRIMARY KEY AUTO_INCREMENT,
  goods_id     BIGINT      NOT NULL,
  buyer_id     BIGINT      NOT NULL,
  seller_id    BIGINT      NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending|confirmed|cancelled',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmed_at DATETIME    NULL,
  -- 取消时间：卖家拒绝 / 买家中途取消时写入；同一商品再次发起时清空并复用同一行
  cancelled_at DATETIME    NULL,
  -- 一件商品只有一笔成交记录：已确认的不能重复发起。
  -- 被取消的那笔不新插一行，而是复用本行（uk 兜底，并发下只有一条 UPDATE 能成功）。
  UNIQUE KEY uk_trade_goods (goods_id) COMMENT '一件商品只能有一笔成交',
  -- 7 天互刷判定：同一对用户（买/卖可互换）最近一次确认时间
  KEY idx_trade_pair (buyer_id, seller_id, status, confirmed_at),
  -- 卖家「待确认成交」列表
  KEY idx_trade_seller (seller_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='二手成交确认；不涉及资金流';