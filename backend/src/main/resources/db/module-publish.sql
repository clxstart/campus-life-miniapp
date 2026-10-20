-- 让「发布」页的四个类型都变成真实流程：
-- 兼职（job）与活动（campus_activity）补上发布者字段，才能归属内容、支持「我的发布」。
-- 二手（goods）与帖子（post）已有 user_id，无需改动。
-- 手动执行；可重复执行。
USE campus_life;

-- ============ 1. job 增加发布者与发布时间 ============
SET @j1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='user_id')=0,
  'ALTER TABLE job ADD COLUMN user_id BIGINT NULL', 'SELECT "job.user_id exists"'));
PREPARE s FROM @j1; EXECUTE s; DEALLOCATE PREPARE s;

SET @j2 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='created_at')=0,
  'ALTER TABLE job ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP', 'SELECT "job.created_at exists"'));
PREPARE s FROM @j2; EXECUTE s; DEALLOCATE PREPARE s;

SET @j3 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='job' AND column_name='description')=0,
  'ALTER TABLE job ADD COLUMN description VARCHAR(200) NOT NULL DEFAULT ""', 'SELECT "job.description exists"'));
PREPARE s FROM @j3; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 2. campus_activity 增加发布者与详情 ============
SET @a1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND column_name='user_id')=0,
  'ALTER TABLE campus_activity ADD COLUMN user_id BIGINT NULL', 'SELECT "activity.user_id exists"'));
PREPARE s FROM @a1; EXECUTE s; DEALLOCATE PREPARE s;

SET @a2 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND column_name='description')=0,
  'ALTER TABLE campus_activity ADD COLUMN description VARCHAR(200) NOT NULL DEFAULT ""', 'SELECT "activity.description exists"'));
PREPARE s FROM @a2; EXECUTE s; DEALLOCATE PREPARE s;

SET @a3 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND column_name='created_at')=0,
  'ALTER TABLE campus_activity ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP', 'SELECT "activity.created_at exists"'));
PREPARE s FROM @a3; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 3. 索引：按发布者与时间查询 ============
SET @i1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='job' AND index_name='idx_job_user')=0,
  'ALTER TABLE job ADD INDEX idx_job_user (user_id, created_at)', 'SELECT "idx_job_user exists"'));
PREPARE s FROM @i1; EXECUTE s; DEALLOCATE PREPARE s;

SET @i2 = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='campus_activity' AND index_name='idx_activity_user')=0,
  'ALTER TABLE campus_activity ADD INDEX idx_activity_user (user_id, created_at)', 'SELECT "idx_activity_user exists"'));
PREPARE s FROM @i2; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 4. 回填：演示数据归到 1 号用户，避免出现无主内容 ============
UPDATE job SET user_id = 1 WHERE user_id IS NULL;
UPDATE campus_activity SET user_id = 1 WHERE user_id IS NULL;

-- ============ 5. 结果核对 ============
SELECT '兼职' AS 类型, id, title, user_id, day_salary, settle FROM job;
SELECT '活动' AS 类型, id, title, user_id, enrolled FROM campus_activity;
