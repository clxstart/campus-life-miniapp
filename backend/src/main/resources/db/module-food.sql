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
