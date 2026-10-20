-- 给已有 campus_life 库单独执行本文件；仅包含二手市场、兼职、季节活动三个模块。
USE campus_life;

CREATE TABLE IF NOT EXISTS goods (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  mode TINYINT NOT NULL DEFAULT 1,
  title VARCHAR(64) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  condition_level VARCHAR(8) DEFAULT '九成新',
  category VARCHAR(16) DEFAULT '其他',
  image VARCHAR(255),
  images TEXT,
  description VARCHAR(200) DEFAULT '',
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_goods_mode(mode,status),
  CONSTRAINT fk_goods_user FOREIGN KEY (user_id) REFERENCES `user`(id),
  CHECK (price >= 0 AND price <= 99999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO goods(id,user_id,mode,title,price,condition_level,category,image,description) VALUES
 (1,1,1,'九成新护眼台灯',45,'九成新','数码','/assets/home/recommend-lamp.jpg','宿舍自用半年，亮度可调，晚上看书很舒服。'),
 (2,2,1,'高数教材全套',25,'九成新','图书','/assets/home/thesis-books.webp','课本和习题册一套，笔记很少，适合期中复习。'),
 (3,3,1,'羽毛球拍',60,'九成新','运动','/assets/social/basketball.webp','轻量拍，打过几次，送一只备用手胶。'),
 (4,1,1,'联想小新笔记本',3200,'七成新及以下','数码','/assets/home/laptop-silver.webp','正常使用痕迹，功能完好，校园内当面验机。'),
 (5,4,1,'春日野餐垫',15,'九成新','生活','/assets/social/spring-picnic.webp','折叠方便，适合草坪活动，欢迎自提。'),
 (6,2,2,'求购二手吉他',300,'九成新','其他','/assets/social/flea-market.webp','预算300元左右，民谣吉他即可，校内面交。')
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),mode=VALUES(mode),title=VALUES(title),price=VALUES(price),condition_level=VALUES(condition_level),category=VALUES(category),image=VALUES(image),description=VALUES(description),status=1;

CREATE TABLE IF NOT EXISTS job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(64) NOT NULL,
  day_salary INT NOT NULL,
  settle VARCHAR(8) DEFAULT '日结',
  location VARCHAR(64),
  time_desc VARCHAR(64),
  status VARCHAR(8) DEFAULT '招聘中'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO job(id,title,day_salary,settle,location,time_desc,status) VALUES
 (1,'图书馆图书整理员',100,'日结','逸夫图书馆','周一到周五 14:00-17:00','招聘中'),
 (2,'奶茶店店员',90,'日结','紫荆食堂旁','周末两班轮换','招聘中'),
 (3,'家教·初二数学',150,'周结','校外·学生家','每周六上午','招聘中'),
 (4,'运动会志愿者',80,'日结','东区田径场','10.24-10.25','招聘中')
ON DUPLICATE KEY UPDATE title=VALUES(title),day_salary=VALUES(day_salary),settle=VALUES(settle),location=VALUES(location),time_desc=VALUES(time_desc),status=VALUES(status);

CREATE TABLE IF NOT EXISTS campus_activity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(64) NOT NULL,
  poster VARCHAR(255),
  time_desc VARCHAR(32),
  location VARCHAR(64),
  enrolled INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity_enroll (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_enroll(activity_id,user_id),
  CONSTRAINT fk_activity_enroll_activity FOREIGN KEY (activity_id) REFERENCES campus_activity(id),
  CONSTRAINT fk_activity_enroll_user FOREIGN KEY (user_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO campus_activity(id,title,poster,time_desc,location,enrolled,status) VALUES
 (1,'樱花草坪音乐会','/assets/social/spring-picnic.webp','本周六 15:00','大礼堂草坪',268,1),
 (2,'秋季运动会','/assets/home/hero-campus.webp','10.24-10.25','东区田径场',512,1),
 (3,'校园歌手大赛决赛','/assets/home/spring-event.webp','12.05 19:00','大礼堂',341,1)
ON DUPLICATE KEY UPDATE title=VALUES(title),poster=VALUES(poster),time_desc=VALUES(time_desc),location=VALUES(location),enrolled=VALUES(enrolled),status=1;
