-- 圈子化改造：circle / circle_member 建表 + post 加列 + 种子数据
-- 手动执行；后端不会自动建表。可重复执行。
USE campus_life;

-- ============ 1. 圈子表 ============
CREATE TABLE IF NOT EXISTS circle (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  name         VARCHAR(32)  NOT NULL,
  slug         VARCHAR(32)  NOT NULL,
  category     VARCHAR(16)  NOT NULL DEFAULT '兴趣',
  description  VARCHAR(128) NOT NULL DEFAULT '',
  cover        VARCHAR(255) NOT NULL DEFAULT '',
  theme        VARCHAR(16)  NOT NULL DEFAULT 'blue',
  member_count INT          NOT NULL DEFAULT 0,
  post_count   INT          NOT NULL DEFAULT 0,
  hot          TINYINT      NOT NULL DEFAULT 0,
  sort         INT          NOT NULL DEFAULT 0,
  status       TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_slug (slug),
  KEY idx_circle_hot (hot, sort),
  KEY idx_circle_category (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 2. 圈子成员表 ============
CREATE TABLE IF NOT EXISTS circle_member (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  circle_id  BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  role       VARCHAR(16) NOT NULL DEFAULT 'member',
  joined_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_circle_user (circle_id, user_id),
  KEY idx_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 3. post 表加 circle_id ============
-- 兼容老数据：允许 NULL，无圈子的帖子仍能在全校流看到。
SET @ddl = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='campus_life' AND table_name='post' AND column_name='circle_id') = 0,
  'ALTER TABLE post ADD COLUMN circle_id BIGINT NULL AFTER tag',
  'SELECT "circle_id already exists"'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='campus_life' AND table_name='post' AND index_name='idx_post_circle') = 0,
  'ALTER TABLE post ADD INDEX idx_post_circle (circle_id, status, created_at)',
  'SELECT "idx_post_circle already exists"'));
PREPARE stmt2 FROM @idx; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- ============ 4. 圈子种子数据 ============
INSERT IGNORE INTO circle(id,name,slug,category,description,cover,theme,hot,sort) VALUES
 (1,'王者荣耀','wzry','游戏','开黑上分、英雄攻略、段位交流','/assets/social/basketball.webp','blue',1,1),
 (2,'考研上岸','kaoyan','学习','备考经验、资料共享、院校信息','/assets/social/library-study.webp','green',1,2),
 (3,'篮球场','basketball','运动','约球组队、球技交流、赛事讨论','/assets/social/basketball.webp','amber',1,3),
 (4,'二手闲置','flea','生活','闲置转让、求购、以物换物','/assets/social/flea-market.webp','pink',1,4),
 (5,'摄影社','photo','兴趣','校园写真、约拍、后期交流','/assets/social/spring-picnic.webp','purple',1,5),
 (6,'编程学习','code','学习','算法刷题、项目实战、技术分享','/assets/social/library-study.webp','blue',0,6),
 (7,'美食探店','food','生活','食堂测评、周边美食、深夜放毒','/assets/social/spring-picnic.webp','amber',0,7),
 (8,'日语角','japanese','学习','五十音、考级、日剧动漫交流','/assets/social/library-study.webp','green',0,8),
 (9,'羽毛球','badminton','运动','约场、装备、技巧','/assets/social/basketball.webp','amber',0,9),
 (10,'桌游剧本杀','boardgame','兴趣','组局、剧本推荐、约本','/assets/social/flea-market.webp','purple',0,10);

-- ============ 5. 迁移现有帖子到圈子 ============
-- 按 tag 归类，让老帖子也能在圈子里看到。
-- 条件里的 circle_id IS NULL 保证幂等：重复执行不会覆盖已有的归属。
UPDATE post SET circle_id=1 WHERE circle_id IS NULL AND tag LIKE '%游戏%';
UPDATE post SET circle_id=2 WHERE circle_id IS NULL AND tag LIKE '%学习%';
UPDATE post SET circle_id=3 WHERE circle_id IS NULL AND tag LIKE '%运动%';
UPDATE post SET circle_id=4 WHERE circle_id IS NULL AND tag LIKE '%市集%';
UPDATE post SET circle_id=5 WHERE circle_id IS NULL AND tag LIKE '%社团%';
-- 二手/闲置类归到「二手闲置」圈
UPDATE post SET circle_id=4 WHERE circle_id IS NULL AND (tag LIKE '%闲置%' OR tag LIKE '%二手%');
-- 校园日常/求助类归到「摄影社」（校内日常分享）之外更合适的归属：
-- 这类内容不专属某个兴趣圈，保持 NULL 进全校流，不做强行归类。
-- 但如果标题明确指向摄影/风景，归到摄影社
UPDATE post SET circle_id=5 WHERE circle_id IS NULL AND (title LIKE '%摄影%' OR title LIKE '%银杏%' OR title LIKE '%操场%');

-- ============ 6. 演示成员关系（用户1加入前5个圈子）============
INSERT IGNORE INTO circle_member(circle_id,user_id,role) VALUES
 (1,1,'member'),(2,1,'member'),(3,1,'member'),(4,1,'member'),(5,1,'member'),
 (1,2,'member'),(1,3,'member'),(1,4,'member'),
 (2,2,'member'),(2,3,'member'),
 (3,2,'member'),(3,4,'member'),
 (4,3,'member'),(5,4,'member');

-- ============ 7. 重算冗余计数（幂等）============
UPDATE circle c SET
  member_count = (SELECT COUNT(*) FROM circle_member m WHERE m.circle_id = c.id),
  post_count   = (SELECT COUNT(*) FROM post p WHERE p.circle_id = c.id AND p.status = 1);
