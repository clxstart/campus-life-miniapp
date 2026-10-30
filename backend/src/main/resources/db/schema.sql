CREATE DATABASE IF NOT EXISTS campus_life DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campus_life;
CREATE TABLE IF NOT EXISTS `user` (id BIGINT PRIMARY KEY AUTO_INCREMENT,nickname VARCHAR(32) NOT NULL,avatar VARCHAR(255),school VARCHAR(64),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS post (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,title VARCHAR(64) NOT NULL,content TEXT NOT NULL,image VARCHAR(255),tag VARCHAR(16),location VARCHAR(64),like_count INT NOT NULL DEFAULT 0,comment_count INT NOT NULL DEFAULT 0,view_count INT NOT NULL DEFAULT 0,status TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,INDEX idx_post_created(created_at),INDEX idx_post_tag(tag)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS post_like (id BIGINT PRIMARY KEY AUTO_INCREMENT,post_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_post_user(post_id,user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 评论点赞明细：与 post_like 同一套模型，(comment_id,user_id) 唯一键保证幂等
CREATE TABLE IF NOT EXISTS comment_like (id BIGINT PRIMARY KEY AUTO_INCREMENT,comment_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_comment_user(comment_id,user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `comment` (id BIGINT PRIMARY KEY AUTO_INCREMENT,post_id BIGINT NOT NULL,user_id BIGINT NOT NULL,content VARCHAR(255) NOT NULL,like_count INT NOT NULL DEFAULT 0,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS follow (id BIGINT PRIMARY KEY AUTO_INCREMENT,follower_id BIGINT NOT NULL,followee_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_follower_followee(follower_id,followee_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 活动轮播位：发现页顶部 banner，运营改表即可上线活动，无需发版
CREATE TABLE IF NOT EXISTS banner (id BIGINT PRIMARY KEY AUTO_INCREMENT,slot VARCHAR(32) NOT NULL DEFAULT 'discover',eyebrow VARCHAR(32),title VARCHAR(64) NOT NULL,subtitle VARCHAR(128),badge VARCHAR(16),theme VARCHAR(16) NOT NULL DEFAULT 'blue',link_type VARCHAR(16) NOT NULL DEFAULT 'none',link_value VARCHAR(128),sort INT NOT NULL DEFAULT 0,status TINYINT NOT NULL DEFAULT 1,UNIQUE KEY uk_banner_slot_title(slot,title)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 校草校花评选：选手与投票（gender 1=校草组 2=校花组；status 0=待审核 1=展示）
CREATE TABLE IF NOT EXISTS idol_contestant (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,name VARCHAR(32) NOT NULL,gender TINYINT NOT NULL DEFAULT 1,college VARCHAR(64) DEFAULT '',photo VARCHAR(255) NOT NULL,slogan VARCHAR(128) DEFAULT '',vote_count INT NOT NULL DEFAULT 0,status TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,KEY idx_idol_gender(gender,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS idol_vote (id BIGINT PRIMARY KEY AUTO_INCREMENT,contestant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_idol_vote(contestant_id,user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO idol_contestant(user_id,name,gender,college,photo,slogan,vote_count) VALUES
 (2,'苏晚',2,'新闻与传播学院','/assets/social/spring-picnic.webp','把春天穿在身上',862),
 (3,'林小满',2,'外国语学院','/assets/social/library-study.webp','图书馆常驻选手',645),
 (4,'陈知意',2,'经济管理学院','/assets/social/flea-market.webp','市集淘物专家',531),
 (1,'白若曦',2,'艺术学院','/assets/social/profile-avatar.webp','画室里的小太阳',418),
 (3,'陆知远',1,'计算机学院','/assets/social/basketball.webp','球场见真章',774),
 (2,'沈亦辰',1,'体育学院','/assets/social/hero-campus.webp','七点东操等我',590),
 (4,'顾言',1,'文学院','/assets/social/thesis-books.webp','会写诗的投手',456),
 (1,'周叙白',1,'建筑学院','/assets/social/spring-event.webp','画图到天亮',387);
INSERT INTO `user`(id,nickname,avatar,school) VALUES (1,'林知夏','/assets/social/profile-avatar.webp','内蒙古大学'),(2,'周予安',NULL,'内蒙古大学'),(3,'陈一川',NULL,'内蒙古大学'),(4,'春日音乐社',NULL,'内蒙古大学') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname);
INSERT INTO post(user_id,title,content,image,tag,location,like_count,comment_count) VALUES (1,'图书馆靠窗的位置，原来下午这么治愈','阳光、绿树和刚刚写完的课堂笔记。期中周也可以有一点松弛感。','/assets/social/library-study.webp','校园日常','逸夫图书馆',236,28),(2,'周末草坪市集，好多宝藏旧物','淘到了一盏很有氛围感的小台灯，还有绝版社团纪念册。','/assets/social/flea-market.webp','二手闲置','紫荆操场',188,42),(3,'今晚七点，东操篮球场缺一位控卫','友谊局，不限年级。打完一起去吃夜宵。','/assets/social/basketball.webp','运动组队','东区篮球场',96,31),(4,'樱花草坪音乐会，本周六见','带上野餐垫和好心情，现场有不插电演出。','/assets/social/spring-picnic.webp','社团活动','大礼堂草坪',521,67),(1,'有没有人一起准备高数期中？','求一位同学周三晚一起在图书馆刷题。',NULL,'学习','逸夫图书馆',34,8),(2,'求助：校园卡突然无法充值','今天中午开始就提示系统繁忙，有同学知道怎么处理吗？',NULL,'求助','学生事务大厅',21,12),(3,'出一台九成新台灯','亮度可调，宿舍自用半年，期末价出。','/assets/home/recommend-lamp.jpg','二手闲置','南区宿舍',63,14),(4,'社团招新｜春日音乐社','欢迎会乐器或想学乐器的同学加入。',NULL,'社团活动','大学生活动中心',74,16),(1,'校园日常：雨后的操场','晚风和水汽都刚刚好。',NULL,'校园日常','西操场',52,6),(2,'学习资料互助共享','整理了一份考试周资料清单，需要的同学留言。',NULL,'学习','线上',39,10);
-- 评论/关注/点赞种子：comment_count 校正为实际评论行数，保证前端「评论 N」与列表一致
INSERT INTO `comment`(post_id,user_id,content) VALUES
 (1,2,'这个位置确实出片，下午三点后基本有座'),(1,3,'求坐标！几楼啊'),
 (2,1,'台灯有缘我还想要'),(2,3,'纪念册还在吗？下午过去'),
 (3,1,'带我一个，我打组织'),(3,4,'算我一个，七点前到'),
 (4,2,'野餐垫已备好'),(4,1,'能带吉他即兴吗'),
 (5,2,'周三我在三楼自习，一起'),(5,4,'拉个群一起刷题吧'),
 (6,3,'去学生事务大厅三号窗口处理了'),(6,1,'重启校园卡 App 后好了'),
 (7,2,'期末价多少？私聊'),(7,4,'求图看看成色'),
 (8,1,'会一点吉他，报名了'),(8,3,'零基础可以进吗'),
 (9,2,'这张构图很好看'),
 (10,1,'求清单！正好考前复习用'),(10,3,'已留言，谢谢楼主整理'),(10,4,'收藏了，回头来取资料');
UPDATE post SET comment_count=(SELECT COUNT(*) FROM `comment` WHERE `comment`.post_id=post.id);
INSERT INTO follow(follower_id,followee_id) VALUES (1,2),(1,4) ON DUPLICATE KEY UPDATE follower_id=VALUES(follower_id);
INSERT INTO post_like(post_id,user_id) VALUES (2,1),(10,1),(1,2),(3,2),(10,3) ON DUPLICATE KEY UPDATE post_id=VALUES(post_id);
-- 「我的」页用户域：收藏 / 浏览记录 / 地址 / 优惠 / 反馈
CREATE TABLE IF NOT EXISTS favorite (id BIGINT PRIMARY KEY AUTO_INCREMENT,post_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_favorite(post_id,user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS post_view (id BIGINT PRIMARY KEY AUTO_INCREMENT,post_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,KEY idx_view_user(user_id,post_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_address (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,contact VARCHAR(32) NOT NULL,phone VARCHAR(20) DEFAULT '',detail VARCHAR(128) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,title VARCHAR(64) NOT NULL,amount VARCHAR(16) DEFAULT '',expire_at VARCHAR(16) DEFAULT '',status TINYINT NOT NULL DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS feedback (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,content VARCHAR(255) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO follow(follower_id,followee_id) VALUES (2,1),(3,1),(4,1) ON DUPLICATE KEY UPDATE follower_id=VALUES(follower_id);
INSERT INTO favorite(post_id,user_id) VALUES (4,1),(10,1) ON DUPLICATE KEY UPDATE post_id=VALUES(post_id);
INSERT INTO user_address(user_id,contact,phone,detail) VALUES (1,'林知夏','138****1024','学生公寓 6 号楼 312'),(1,'林知夏','138****1024','南区宿舍 2 栋 508');
INSERT INTO coupon(user_id,title,amount,expire_at) VALUES (1,'奶茶第二杯半价券','减8元','2026-11-30'),(1,'打印店8折券','8折','2026-12-31'),(1,'二手市集免摊位费','免50元','2026-10-31');
-- 浏览量种子：按点赞/评论量推一个确定值，保证发现页热度排序合理
UPDATE post SET view_count=like_count*7+comment_count*13+11;
INSERT INTO banner(slot,eyebrow,title,subtitle,badge,theme,link_type,link_value,sort) VALUES ('discover','CAMPUS IDOL','校草校花评选','投票进行中 · 上传照片赢校园人气王','投票中','pink','page','/pages/activity-idol/activity-idol',1),('discover','CAMPUS LIVE','校园歌手大赛','决赛夜 · 大礼堂 19:00 · 现场投票','12.05','blue','page','/pages/calendar/calendar',2) ON DUPLICATE KEY UPDATE subtitle=VALUES(subtitle),badge=VALUES(badge),theme=VALUES(theme),link_type=VALUES(link_type),link_value=VALUES(link_value),sort=VALUES(sort),status=1;

-- 模块一：校园外卖。已有数据库只执行本段，避免重复插入旧模块种子。
USE campus_life;
-- 店铺：满减数值单独存储，展示文案与收费规则保持一致。
CREATE TABLE IF NOT EXISTS shop (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NOT NULL, cover VARCHAR(255) NOT NULL, category VARCHAR(32) NOT NULL,
 rating DECIMAL(2,1) NOT NULL, monthly_sales INT NOT NULL DEFAULT 0, min_price DECIMAL(10,2) NOT NULL DEFAULT 0,
 delivery_fee DECIMAL(10,2) NOT NULL DEFAULT 0, delivery_time INT NOT NULL DEFAULT 30, discount VARCHAR(32) NOT NULL DEFAULT '',
 notice VARCHAR(200) NOT NULL DEFAULT '', discount_threshold DECIMAL(10,2) NOT NULL DEFAULT 0, discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
 status TINYINT NOT NULL DEFAULT 1, KEY idx_shop_category(category,status), CHECK (min_price>=0 AND delivery_fee>=0 AND discount_threshold>=0 AND discount_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 菜单商品：仅同店铺、上架的商品允许加入订单。
CREATE TABLE IF NOT EXISTS shop_goods (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, shop_id BIGINT NOT NULL, category VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL,
 price DECIMAL(10,2) NOT NULL, monthly_sales INT NOT NULL DEFAULT 0, image VARCHAR(255) NOT NULL, sort_num INT NOT NULL DEFAULT 0,
 status TINYINT NOT NULL DEFAULT 1, KEY idx_shop_goods(shop_id,status,sort_num), CONSTRAINT fk_shop_goods_shop FOREIGN KEY(shop_id) REFERENCES shop(id), CHECK (price>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 订单：保存服务端定价快照，费用按商品合计 - 满减 + 打包费 + 配送费计算。
CREATE TABLE IF NOT EXISTS user_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, items TEXT NOT NULL,
 address VARCHAR(128) NOT NULL, remark VARCHAR(200) NOT NULL DEFAULT '', subtotal DECIMAL(10,2) NOT NULL, packing_fee DECIMAL(10,2) NOT NULL,
 delivery_fee DECIMAL(10,2) NOT NULL, discount_amount DECIMAL(10,2) NOT NULL, total DECIMAL(10,2) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'created',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_order_user(user_id,created_at),
 CONSTRAINT fk_order_user FOREIGN KEY(user_id) REFERENCES `user`(id), CONSTRAINT fk_order_shop FOREIGN KEY(shop_id) REFERENCES shop(id), CHECK (total>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO shop(id,name,cover,category,rating,monthly_sales,min_price,delivery_fee,delivery_time,discount,notice,discount_threshold,discount_amount) VALUES
 (1,'茶言观色奶茶','/assets/home/food-milktea.webp','奶茶饮品',4.9,1268,12,2,30,'满20减8','紫荆食堂旁 · 茶底每日现泡，甜度需求请写在备注里。',20,8),
 (2,'二食堂快餐','/assets/home/food-fresh.webp','快餐便当',4.8,2386,15,1,25,'满30减5','二食堂一楼 · 热饭热菜现做，午高峰请提前下单。',30,5),
 (3,'果然鲜果切','/assets/home/food-fruit.webp','水果轻食',4.9,856,10,2,30,'满25减4','南区生活广场 · 当日鲜切，不添加糖浆。',25,4),
 (4,'逸夫小超市','/assets/home/food-snacks.webp','零食',4.7,1680,8,1,20,'满20减3','逸夫图书馆西侧 · 自习间隙的小补给，配送至楼下。',20,3)
ON DUPLICATE KEY UPDATE name=VALUES(name),cover=VALUES(cover),category=VALUES(category),rating=VALUES(rating),monthly_sales=VALUES(monthly_sales),min_price=VALUES(min_price),delivery_fee=VALUES(delivery_fee),delivery_time=VALUES(delivery_time),discount=VALUES(discount),notice=VALUES(notice),discount_threshold=VALUES(discount_threshold),discount_amount=VALUES(discount_amount);
INSERT INTO shop_goods(id,shop_id,category,name,price,monthly_sales,image,sort_num) VALUES
 (101,1,'招牌奶茶','招牌珍珠奶茶',12,628,'/assets/home/food-milktea.webp',0),
 (102,1,'招牌奶茶','茉莉鲜奶茶',14,436,'/assets/home/food-milktea.webp',0),
 (103,1,'清爽果茶','柠檬绿茶',10,312,'/assets/home/food-milktea.webp',1),
 (104,1,'清爽果茶','桃桃乌龙',16,286,'/assets/home/food-milktea.webp',1),
 (201,2,'人气便当','照烧鸡腿饭',18,812,'/assets/home/food-fresh.webp',0),
 (202,2,'人气便当','番茄鸡蛋饭',15,526,'/assets/home/food-fresh.webp',0),
 (203,2,'加餐小菜','时蔬小碗',6,318,'/assets/home/food-fresh.webp',1),
 (204,2,'加餐小菜','米饭',2,452,'/assets/home/food-fresh.webp',1),
 (301,3,'每日鲜切','四季缤纷果切',16,428,'/assets/home/food-fruit.webp',0),
 (302,3,'每日鲜切','清甜西瓜杯',10,366,'/assets/home/food-fruit.webp',0),
 (303,3,'轻食搭配','酸奶水果碗',18,216,'/assets/home/food-fruit.webp',1),
 (401,4,'自习补给','香脆薯片',8,586,'/assets/home/food-snacks.webp',0),
 (402,4,'自习补给','黄油可颂',6,348,'/assets/home/food-snacks.webp',0),
 (403,4,'清凉饮料','柠檬气泡水',5,416,'/assets/home/food-snacks.webp',1)
ON DUPLICATE KEY UPDATE category=VALUES(category),name=VALUES(name),price=VALUES(price),monthly_sales=VALUES(monthly_sales),image=VALUES(image),sort_num=VALUES(sort_num);
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
