-- 学习专区：先执行已有学校/帖子模块迁移，再手动执行本脚本。
-- 可重复执行；复用 post、post_like、comment，不改数据库连接。
USE campus_life;
SET @learn_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='post' AND COLUMN_NAME='learn_category');
SET @learn_ddl = IF(@learn_column_exists=0, 'ALTER TABLE post ADD COLUMN learn_category VARCHAR(16) NULL COMMENT \'学习专区子分类：daike/paper/job/kaoyan/kaogong\'', 'SELECT 1');
PREPARE learn_stmt FROM @learn_ddl;
EXECUTE learn_stmt;
DEALLOCATE PREPARE learn_stmt;
SET @learn_index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='post' AND INDEX_NAME='idx_post_learn');
SET @learn_ddl = IF(@learn_index_exists=0, 'CREATE INDEX idx_post_learn ON post (school_id, learn_category, status, id)', 'SELECT 1');
PREPARE learn_stmt FROM @learn_ddl;
EXECUTE learn_stmt;
DEALLOCATE PREPARE learn_stmt;

-- 此脚本不插入帖子。空库可直接发布；需要演示内容时，另行执行 module-learn-demo.sql。
