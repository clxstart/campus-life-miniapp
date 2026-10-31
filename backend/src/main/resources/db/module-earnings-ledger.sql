-- 履约账单（**不是钱包余额、不是已付款、不可提现**）。
--
-- 用途：记录"谁因为哪一单应该拿到多少钱"，以及平台服务费。
-- 之所以现在只做账单不做余额：通用钱包与提现需要支付机构就本商户号与业务形态
-- 出具书面能力确认（见方案 §1.1 / §6.4），未取得前不得创建可被理解为真实资金的余额。
--
-- 幂等：biz_no 唯一。同一笔业务重复结算/重复回调只会命中已有记录，不会重复记账。
USE campus_life;

CREATE TABLE IF NOT EXISTS earnings_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_no VARCHAR(80) NOT NULL COMMENT '业务唯一号，如 delivery:123:income、job:45:income',
  biz_type VARCHAR(16) NOT NULL COMMENT 'delivery|job',
  ref_id BIGINT NOT NULL COMMENT '业务主键（订单 id / 履约单 id）',
  user_id BIGINT DEFAULT NULL COMMENT '受益人或付费方',
  entry_type VARCHAR(24) NOT NULL COMMENT 'delivery_income|job_income|platform_fee|job_payable|job_refund',
  amount_cents INT NOT NULL COMMENT '人民币分，正数',
  status VARCHAR(16) NOT NULL DEFAULT 'recorded' COMMENT 'recorded|frozen|settled|refunded|void',
  note VARCHAR(200) NOT NULL DEFAULT '',
  operator_id BIGINT DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ledger_biz_no (biz_no),
  KEY idx_ledger_ref (biz_type, ref_id),
  KEY idx_ledger_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约账单（非资金余额，不可提现）';
