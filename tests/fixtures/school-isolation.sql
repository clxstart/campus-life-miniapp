-- 按校隔离的测试夹具：第二所学校 + 用户 + 各类内容
-- 用途：证明 schoolScope=local 时看不到外校内容；可重复执行。
USE campus_life;

-- 先清掉上一轮集成测试留下的临时数据（E2E- 前缀），保证可重复执行
DELETE FROM goods WHERE title LIKE 'E2E%';
DELETE FROM job WHERE title LIKE 'E2E%';
DELETE FROM campus_activity WHERE title LIKE 'E2E%';
DELETE FROM post WHERE title LIKE 'E2E%';

-- 第二所学校
INSERT IGNORE INTO school(id, name, city) VALUES (2, '北京大学', '北京');

-- 第二所学校的用户
INSERT IGNORE INTO `user`(id, nickname, school, school_id) VALUES (5, '北大同学', '北京大学', 2);

-- 第二所学校的二手商品
INSERT IGNORE INTO goods(id, user_id, mode, title, price, condition_level, category, description, status, school_id)
VALUES (7, 5, 1, '北大二手教材·经济学原理', 30.00, '九成新', '图书', '北大校内自提', 1, 2);

-- 第二所学校的圈子
INSERT IGNORE INTO circle(id, name, slug, category, description, cover, theme, member_count, post_count, hot, sort, status, school_id)
VALUES (11, '北大未名湖跑团', 'pku-run', '运动', '北大跑步爱好者聚集地', '/assets/social/basketball.webp', 'green', 0, 0, 1, 11, 1, 2);

-- 第二所学校的兼职
INSERT IGNORE INTO job(id, title, day_salary, settle, location, time_desc, status, user_id, description, school_id)
VALUES (5, '北大图书馆助理', 120, '日结', '北大图书馆', '周一至周五 14:00-17:00', '招聘中', 5, '整理书架与借还登记', 2);

-- 第二所学校的外卖店铺
INSERT IGNORE INTO shop(id, name, cover, category, rating, monthly_sales, min_price, delivery_fee, delivery_time, discount, notice, status, school_id)
VALUES (5, '北大食堂窗口', '/assets/home/food-fresh.webp', '快餐便当', 4.8, 1200, 12.00, 2.00, 25, '满20减5', '北大校内食堂', 1, 2);

-- 本校数据兜底归属（幂等）
UPDATE goods SET school_id = 1 WHERE school_id IS NULL;
UPDATE circle SET school_id = 1 WHERE school_id IS NULL;
UPDATE job SET school_id = 1 WHERE school_id IS NULL;
UPDATE shop SET school_id = 1 WHERE school_id IS NULL;

SELECT '圈子' AS 类型, id, name, school_id FROM circle WHERE school_id = 2
UNION ALL SELECT '兼职', id, title, school_id FROM job WHERE school_id = 2
UNION ALL SELECT '店铺', id, name, school_id FROM shop WHERE school_id = 2
UNION ALL SELECT '商品', id, title, school_id FROM goods WHERE school_id = 2;
