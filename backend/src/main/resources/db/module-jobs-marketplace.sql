-- 兼职岗位域（§11）：校外岗位 / 替课类目 / 有偿接单 三处闸门，校内原子抢占，履约工时与履约账单。
--
-- 改这个脚本前请先读这几条约束：
--   1. 金额一律用「人民币分」的 INT，不出现 float/double —— 0.01 的误差累积到最后就是对不上账。
--   2. 费率（fee_mode / fee_value）**只能由管理端逐岗位配置**，发布接口一律忽略请求值。
--   3. 校内岗位的「先到先接」由一条带条件的 UPDATE 保证（见 JobMapper.takeCampus），不靠先查后写。
--   4. earnings_ledger 是**履约账单**，不是钱包余额、不可提现；本脚本不创建任何 wallet/balance 表。
--   5. 本脚本幂等：可重复执行；加列前先查 information_schema，照 module-audit.sql 的写法。
USE campus_life;

-- ============================ job 扩列 ============================

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='scope');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN `scope` VARCHAR(16) NOT NULL DEFAULT ''campus'' COMMENT ''campus|offsite''',
  'SELECT "job.scope exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='employer_kind');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN employer_kind VARCHAR(16) NOT NULL DEFAULT ''student'' COMMENT ''student|partner''',
  'SELECT "job.employer_kind exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='partner_id');
SET @ddl = IF(@missing, 'ALTER TABLE job ADD COLUMN partner_id BIGINT NULL', 'SELECT "job.partner_id exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='take_mode');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN take_mode VARCHAR(16) NOT NULL DEFAULT ''first_come'' COMMENT ''first_come|apply''',
  'SELECT "job.take_mode exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='category_key');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN category_key VARCHAR(32) NOT NULL DEFAULT ''other'' COMMENT ''other|takeover_class|tutor|promotion|service|delivery''',
  'SELECT "job.category_key exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='pay_cents');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN pay_cents INT NOT NULL DEFAULT 0 COMMENT ''报酬，人民币分''',
  'SELECT "job.pay_cents exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;
-- 老数据把「元/天」搬到分：只在列刚添加时搬一次，之后重跑不会覆盖人工改过的值
SET @ddl = IF(@missing,
  'UPDATE job SET pay_cents = day_salary * 100 WHERE pay_cents = 0 AND day_salary > 0',
  'SELECT "job.pay_cents backfill skipped"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='pay_unit');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN pay_unit VARCHAR(16) NOT NULL DEFAULT ''day'' COMMENT ''hour|order|shift|day|custom''',
  'SELECT "job.pay_unit exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='fee_mode');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN fee_mode VARCHAR(16) NOT NULL DEFAULT ''none'' COMMENT ''none|amount|rate（仅管理端可配）''',
  'SELECT "job.fee_mode exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='fee_value');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN fee_value INT NOT NULL DEFAULT 0 COMMENT ''amount=分/单位；rate=万分比(500=5%)''',
  'SELECT "job.fee_value exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='headcount');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN headcount INT NOT NULL DEFAULT 1 COMMENT ''招聘人数''',
  'SELECT "job.headcount exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='deadline_at');
SET @ddl = IF(@missing, 'ALTER TABLE job ADD COLUMN deadline_at DATETIME NULL COMMENT ''报名/接单截止''',
  'SELECT "job.deadline_at exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='workflow_status');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN workflow_status VARCHAR(24) NOT NULL DEFAULT ''open'' COMMENT ''open|taken|recruiting|working|pending_confirm|settled|disputed|refunded|cancelled''',
  'SELECT "job.workflow_status exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;
-- 历史岗位：审核通过的可接，未通过的视为不可接（cancelled），避免一加列就把待审岗位放出去
SET @ddl = IF(@missing,
  'UPDATE job SET workflow_status = IF(audit_status = ''passed'', ''open'', ''cancelled'')',
  'SELECT "job.workflow_status backfill skipped"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='taken_by');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN taken_by BIGINT NULL COMMENT ''校内先到先接的承接人（抢占列）''',
  'SELECT "job.taken_by exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='accepted_count');
SET @ddl = IF(@missing,
  'ALTER TABLE job ADD COLUMN accepted_count INT NOT NULL DEFAULT 0 COMMENT ''已录用人数（校外报名）''',
  'SELECT "job.accepted_count exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

-- 列表按 scope + workflow_status 过滤，没索引会全表扫
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='campus_life' AND table_name='job' AND index_name='idx_job_scope_workflow') = 0,
  'ALTER TABLE job ADD INDEX idx_job_scope_workflow(`scope`, workflow_status)',
  'SELECT "idx_job_scope_workflow exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='campus_life' AND table_name='job' AND index_name='idx_job_taken_by') = 0,
  'ALTER TABLE job ADD INDEX idx_job_taken_by(taken_by)',
  'SELECT "idx_job_taken_by exists"');
PREPARE m FROM @ddl; EXECUTE m; DEALLOCATE PREPARE m;

-- ============================ 校外报名 ============================
CREATE TABLE IF NOT EXISTS job_application (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  applicant_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending|accepted|rejected|withdrawn',
  message VARCHAR(200) NOT NULL DEFAULT '',
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME NULL,
  decided_by BIGINT NULL,
  UNIQUE KEY uk_job_applicant(job_id, applicant_id),
  KEY idx_applicant(applicant_id, status),
  KEY idx_job_status(job_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校外岗位报名（唯一键即幂等兜底）';

-- ============================ 履约单 ============================
-- 费率/报酬全部是**接单时的快照**：之后管理端改 job 的费率，已接的单不受影响。
CREATE TABLE IF NOT EXISTS job_contract (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  worker_id BIGINT NOT NULL COMMENT '承接方',
  employer_id BIGINT NOT NULL COMMENT '发布方（付款方）',
  status VARCHAR(24) NOT NULL DEFAULT 'created' COMMENT 'created|working|pending_confirm|settled|disputed|cancelled|refunded',
  fee_mode_snapshot VARCHAR(16) NOT NULL DEFAULT 'none',
  fee_value_snapshot INT NOT NULL DEFAULT 0,
  pay_cents_snapshot INT NOT NULL DEFAULT 0,
  pay_unit_snapshot VARCHAR(16) NOT NULL DEFAULT 'day',
  worker_hours DECIMAL(8,2) NULL COMMENT '承接方提交的工时',
  employer_hours DECIMAL(8,2) NULL COMMENT '发布方确认的工时',
  agreed_hours DECIMAL(8,2) NULL COMMENT '双方一致后的工时，结算以此为准',
  cancel_reason VARCHAR(200) NOT NULL DEFAULT '',
  started_at DATETIME NULL,
  submitted_at DATETIME NULL,
  both_confirmed_at DATETIME NULL,
  settled_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_job_worker(job_id, worker_id),
  KEY idx_worker(worker_id, status),
  KEY idx_employer(employer_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位履约单（报酬与费率均为接单时快照）';

-- ============================ 履约争议 ============================
CREATE TABLE IF NOT EXISTS job_dispute (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_id BIGINT NOT NULL,
  raised_by BIGINT NOT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'open' COMMENT 'open|resolved',
  decided_by BIGINT NULL,
  decision VARCHAR(16) NULL COMMENT 'settled|refunded',
  decided_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_contract(contract_id),
  KEY idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约争议（裁定必须写审计）';

SELECT 'module-jobs-marketplace applied' AS result;
