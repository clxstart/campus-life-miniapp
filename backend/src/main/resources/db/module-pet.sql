-- 宠物功能：用户宠物、互动记录、积分
-- 可重复执行（先判断表/字段是否存在）
USE campus_life;

-- A. 宠物表
SET @pet_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_pet');
SET @pet_ddl = IF(@pet_exists=0,
'CREATE TABLE user_pet (
    id          bigint PRIMARY KEY AUTO_INCREMENT,
    user_id     bigint NOT NULL,
    name        varchar(20) DEFAULT ''小星'',
    level       int DEFAULT 1,
    exp         int DEFAULT 0,
    hunger      int DEFAULT 80,
    mood        int DEFAULT 80,
    last_feed_at      datetime,
    last_touch_at     datetime,
    last_decay_at     datetime,
    created_at  datetime DEFAULT CURRENT_TIMESTAMP,
    updated_at  datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id)
)', 'SELECT 1');
PREPARE s FROM @pet_ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- B. 互动记录表
SET @log_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pet_interaction_log');
SET @log_ddl = IF(@log_exists=0,
'CREATE TABLE pet_interaction_log (
    id          bigint PRIMARY KEY AUTO_INCREMENT,
    user_id     bigint NOT NULL,
    action      varchar(16) NOT NULL,
    exp_gain    int DEFAULT 0,
    cost_points int DEFAULT 0,
    created_at  datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, created_at)
)', 'SELECT 1');
PREPARE s FROM @log_ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- C. 积分表
SET @pts_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_points');
SET @pts_ddl = IF(@pts_exists=0,
'CREATE TABLE user_points (
    user_id      bigint PRIMARY KEY,
    balance      int DEFAULT 0,
    total_earned int DEFAULT 0,
    updated_at   datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)', 'SELECT 1');
PREPARE s FROM @pts_ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @plog_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_log');
SET @plog_ddl = IF(@plog_exists=0,
'CREATE TABLE point_log (
    id         bigint PRIMARY KEY AUTO_INCREMENT,
    user_id    bigint NOT NULL,
    amount     int NOT NULL,
    type       varchar(32) NOT NULL,
    ref_id     bigint,
    remark     varchar(100),
    created_at datetime DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, created_at)
)', 'SELECT 1');
PREPARE s FROM @plog_ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- D. 测试数据：给 user 1-5 各一只宠物和积分
INSERT IGNORE INTO user_pet (user_id, name, level, exp, hunger, mood, last_decay_at)
SELECT id, '小星', 3, 50, 60, 70, NOW() FROM `user` WHERE id <= 5;
INSERT IGNORE INTO user_points (user_id, balance, total_earned) VALUES (1, 320, 320), (2, 100, 100), (3, 50, 50), (4, 200, 200), (5, 80, 80);
