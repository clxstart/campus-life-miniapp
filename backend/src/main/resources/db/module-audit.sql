-- Content moderation schema. Run after the module tables have been installed.
USE campus_life;

-- New content defaults to pending. Existing rows are promoted only when a status
-- column is first added, so rerunning this script never approves new submissions.
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='post' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE post ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "post audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE post SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "post legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='comment' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE `comment` ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "comment audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE `comment` SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "comment legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='goods' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE goods ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "goods audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE goods SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "goods legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE job ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "job audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE job SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "job legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='chat_message' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE chat_message ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "chat_message audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE chat_message SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "chat legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='circle' AND column_name='audit_status');
SET @ddl = IF(@missing, 'ALTER TABLE circle ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT ''pending'', ADD COLUMN audit_reason VARCHAR(200) NULL, ADD COLUMN audit_at DATETIME NULL', 'SELECT "circle audit columns exist"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF(@missing, 'UPDATE circle SET audit_status=''passed'' WHERE audit_status=''pending''', 'SELECT "circle legacy rows already migrated"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

SET @tables = 'post,comment,goods,job,chat_message,circle';
SET @table = 'post';
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name=@table AND index_name='idx_post_audit_status')=0, 'ALTER TABLE post ADD INDEX idx_post_audit_status(audit_status)', 'SELECT "post audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='comment' AND index_name='idx_comment_audit_status')=0, 'ALTER TABLE `comment` ADD INDEX idx_comment_audit_status(audit_status)', 'SELECT "comment audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='goods' AND index_name='idx_goods_audit_status')=0, 'ALTER TABLE goods ADD INDEX idx_goods_audit_status(audit_status)', 'SELECT "goods audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='job' AND index_name='idx_job_audit_status')=0, 'ALTER TABLE job ADD INDEX idx_job_audit_status(audit_status)', 'SELECT "job audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='chat_message' AND index_name='idx_chat_audit_status')=0, 'ALTER TABLE chat_message ADD INDEX idx_chat_audit_status(audit_status)', 'SELECT "chat audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='campus_life' AND table_name='circle' AND index_name='idx_circle_audit_status')=0, 'ALTER TABLE circle ADD INDEX idx_circle_audit_status(audit_status)', 'SELECT "circle audit index exists"');
PREPARE audit_stmt FROM @ddl; EXECUTE audit_stmt; DEALLOCATE PREPARE audit_stmt;

CREATE TABLE IF NOT EXISTS sensitive_word (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  word VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL DEFAULT 'custom',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sensitive_word(word), KEY idx_sensitive_enabled(enabled,category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO sensitive_word(word,category) VALUES
 ('刷单','fraud'),('押金','fraud'),('保证金','fraud'),('先交钱','fraud'),('垫付','fraud'),('返利','fraud'),
 ('日赚','fraud'),('月入过万','fraud'),('代写','fraud'),('代考','fraud'),('代课','fraud'),('包过','fraud'),
 ('内部渠道','fraud'),('包录取','fraud'),('兼职日结高薪','fraud'),('无需经验高薪','fraud'),('加微信私聊','fraud'),
 ('加QQ','fraud'),('刷信誉','fraud'),('点赞返现','fraud'),('任务佣金','fraud'),('垫付返现','fraud');

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  content_snippet VARCHAR(200) NULL,
  source VARCHAR(16) NOT NULL,
  result VARCHAR(16) NOT NULL,
  matched_words VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_target(target_type,target_id), KEY idx_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
