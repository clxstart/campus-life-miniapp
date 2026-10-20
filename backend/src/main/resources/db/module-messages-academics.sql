-- 手动执行；后端不会自动建表。可重复执行，种子唯一键不会覆盖已有消息/已读状态。
USE campus_life;
CREATE TABLE IF NOT EXISTS chat_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 sender_id BIGINT NOT NULL, receiver_id BIGINT NOT NULL,
 type VARCHAR(8) NOT NULL DEFAULT 'text', content VARCHAR(1000) NOT NULL DEFAULT '', image VARCHAR(255),
 client_id VARCHAR(64) NOT NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_client(sender_id,client_id),
 KEY idx_chat_pair(sender_id,receiver_id,id), KEY idx_chat_receiver(receiver_id,read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS chat_preference (
 user_id BIGINT NOT NULL, peer_id BIGINT NOT NULL,
 cleared_before BIGINT NOT NULL DEFAULT 0, muted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL,
 title VARCHAR(64) NOT NULL, content VARCHAR(1000) NOT NULL,
 category VARCHAR(16) NOT NULL DEFAULT 'system', tone VARCHAR(16) NOT NULL DEFAULT 'blue',
 post_id BIGINT NULL, source_key VARCHAR(64) NULL, read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notification_seed(user_id,source_key), KEY idx_notification_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS academic_term (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, school VARCHAR(64) NOT NULL, term_label VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, midterm_start DATE NOT NULL, midterm_end DATE NOT NULL, final_start DATE NOT NULL,
 active TINYINT NOT NULL DEFAULT 1, UNIQUE KEY uk_term(school,term_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS course_schedule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 name VARCHAR(64) NOT NULL, room VARCHAR(64) NOT NULL, day INT NOT NULL, period INT NOT NULL,
 theme VARCHAR(16) NOT NULL DEFAULT 'blue', week_start INT NOT NULL DEFAULT 1, week_end INT NOT NULL DEFAULT 21,
 UNIQUE KEY uk_course(term_id,user_id,day,period,name), KEY idx_course_week(user_id,term_id,week_start,week_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS calendar_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, term_id BIGINT NOT NULL, title VARCHAR(64) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, note VARCHAR(255) NOT NULL DEFAULT '',
 UNIQUE KEY uk_calendar(term_id,title,start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 以下均为演示种子；正式课表/校历由管理员按学校、用户、学期维护，未对接外部教务平台。
INSERT IGNORE INTO chat_message(sender_id,receiver_id,type,content,image,client_id) VALUES
 (2,1,'text','你好，看到你收藏了我的台灯～',NULL,'seed-lamp-1'),
 (1,2,'text','你好，请问台灯还在吗？晚上方便自提吗？',NULL,'seed-lamp-2'),
 (2,1,'text','还在的，今晚可以宿舍楼下自提。',NULL,'seed-lamp-3'),
 (2,1,'image','','/assets/home/recommend-lamp.jpg','seed-lamp-4'),
 (1,2,'text','好的，那晚上七点见！',NULL,'seed-lamp-5'),
 (4,1,'text','欢迎加入！本周四晚上第一次排练',NULL,'seed-music-1'),
 (3,1,'text','好的，东区二号场见',NULL,'seed-basketball-1');
INSERT IGNORE INTO notification(user_id,title,content,category,tone,source_key) VALUES
 (1,'校园市集提醒','你收藏的校园市集将在明天下午开始','system','green','seed-market'),
 (1,'内容审核通过','你发布的“几乎全新护眼台灯”已经展示','system','blue','seed-review'),
 (1,'系统维护通知','本周日凌晨将进行短时服务升级','system','yellow','seed-maintenance');
INSERT IGNORE INTO academic_term(school,term_label,start_date,end_date,midterm_start,midterm_end,final_start)
 VALUES('内蒙古大学','2026-2027学年 第一学期','2026-08-31','2027-01-22','2026-11-02','2026-11-06','2027-01-04');
SET @campus_term_id=(SELECT id FROM academic_term WHERE school='内蒙古大学' AND term_label='2026-2027学年 第一学期');
INSERT IGNORE INTO course_schedule(term_id,user_id,name,room,day,period,theme) VALUES
 (@campus_term_id,1,'新闻学概论','博学楼 B203',0,0,'blue'),
 (@campus_term_id,1,'大学英语','外国语楼 305',0,1,'green'),
 (@campus_term_id,1,'传播学原理','博学楼 A110',1,1,'amber'),
 (@campus_term_id,1,'新闻摄影','实验楼 402',2,0,'pink'),
 (@campus_term_id,1,'体育·羽毛球','体育馆',2,3,'purple'),
 (@campus_term_id,1,'新媒体运营','博学楼 B305',3,1,'blue'),
 (@campus_term_id,1,'形势与政策','图书馆报告厅',4,2,'amber');
INSERT IGNORE INTO calendar_event(term_id,title,start_date,end_date,note) VALUES
 (@campus_term_id,'秋季学期开学','2026-08-31','2026-08-31','第一教学周开始，记得注册报到'),
 (@campus_term_id,'国庆假期','2026-10-01','2026-10-07','10月8日（周四）正常上课'),
 (@campus_term_id,'秋季运动会','2026-10-24','2026-10-25','东区田径场，全校停课两天'),
 (@campus_term_id,'期中考试周','2026-11-02','2026-11-06','具体安排以教务系统通知为准'),
 (@campus_term_id,'校园歌手大赛决赛','2026-12-05','2026-12-05','大礼堂，晚上 19:00'),
 (@campus_term_id,'期末考试周','2027-01-04','2027-01-15','考试周图书馆延长开放至 23:00'),
 (@campus_term_id,'寒假开始','2027-01-23','2027-01-23','演示日期，与学期结束日期保持一致，实际以教务通知为准');
