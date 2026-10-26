-- 可选演示数据；真实环境无需执行。先运行 module-learn.sql。
USE campus_life;
-- 示例只写入已存在的学校和用户；真实互动从零开始。
INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 1,'高数卡住了？一起把思路理清','想找同学讨论导数与极限，带上你已经尝试的步骤，我们一起梳理。','/assets/learn/desk.jpg','学习','daike',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=1)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='daike' AND title='高数卡住了？一起把思路理清');

INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 2,'从选题到提纲：我的论文起步笔记','先把研究问题写清楚，再做文献阅读记录。欢迎交流自己的选题和提纲经验。','/assets/learn/desk.jpg','学习','paper',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=2)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='paper' AND title='从选题到提纲：我的论文起步笔记');

INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 1,'文献阅读怎么做记录？分享我的小方法','按研究问题、方法、结论和自己的疑问整理笔记，引用时记录原文出处。',NULL,'学习','paper',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=1)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='paper' AND title='文献阅读怎么做记录？分享我的小方法');

INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 3,'实习简历交流：把项目经历说清楚','正在整理第一份简历，欢迎一起讨论如何用真实的行动和成果展示项目经历。',NULL,'学习','job',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=3)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='job' AND title='实习简历交流：把项目经历说清楚');

INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 2,'找个一起复盘的考研伙伴','希望每周交流学习进展和错题思路，找到适合自己的节奏。','/assets/learn/desk.jpg','学习','kaoyan',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=2)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='kaoyan' AND title='找个一起复盘的考研伙伴');

INSERT INTO post (user_id,title,content,image,tag,learn_category,school_id,status,like_count,comment_count,view_count,created_at,updated_at)
SELECT 3,'备考交流：从整理错题开始','按照题型记录卡点和解题过程，定期回看。欢迎分享自己的复盘习惯。',NULL,'学习','kaogong',1,1,0,0,0,NOW(),NOW()
WHERE EXISTS (SELECT 1 FROM school WHERE id=1) AND EXISTS (SELECT 1 FROM `user` WHERE id=3)
AND NOT EXISTS (SELECT 1 FROM post WHERE school_id=1 AND learn_category='kaogong' AND title='备考交流：从整理错题开始');

SELECT learn_category, COUNT(*) AS total FROM post WHERE status=1 AND school_id=1 AND learn_category IS NOT NULL GROUP BY learn_category;
