-- 真实热度统计：给二手商品与外卖店铺增加行为计数，供首页「今日推荐」按真实热度排序。
-- 手动执行；可重复执行。
USE campus_life;

-- ============ 1. goods 增加行为计数 ============
-- 说明：热度不是手填的，而是由用户真实行为累加（浏览/收藏/私聊）。
SET @g1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='goods' AND column_name='view_count')=0,
  'ALTER TABLE goods ADD COLUMN view_count INT NOT NULL DEFAULT 0', 'SELECT "view_count exists"'));
PREPARE s FROM @g1; EXECUTE s; DEALLOCATE PREPARE s;

SET @g2 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='goods' AND column_name='favorite_count')=0,
  'ALTER TABLE goods ADD COLUMN favorite_count INT NOT NULL DEFAULT 0', 'SELECT "favorite_count exists"'));
PREPARE s FROM @g2; EXECUTE s; DEALLOCATE PREPARE s;

SET @g3 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='goods' AND column_name='contact_count')=0,
  'ALTER TABLE goods ADD COLUMN contact_count INT NOT NULL DEFAULT 0', 'SELECT "contact_count exists"'));
PREPARE s FROM @g3; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 2. shop 增加真实订单数与浏览量 ============
-- monthly_sales 是平台侧大盘数据，不是本小程序产生的；order_count 才是真实下单数。
SET @s1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='order_count')=0,
  'ALTER TABLE shop ADD COLUMN order_count INT NOT NULL DEFAULT 0', 'SELECT "order_count exists"'));
PREPARE s FROM @s1; EXECUTE s; DEALLOCATE PREPARE s;

SET @s2 = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='shop' AND column_name='view_count')=0,
  'ALTER TABLE shop ADD COLUMN view_count INT NOT NULL DEFAULT 0', 'SELECT "view_count exists"'));
PREPARE s FROM @s2; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 3. 二手商品收藏表（可跨用户累计，不复用帖子收藏） ============
CREATE TABLE IF NOT EXISTS goods_favorite (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  goods_id   BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_goods_user (goods_id, user_id),
  KEY idx_goods_fav(goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 4. 索引：热度排序用 ============
SET @i1 = (SELECT IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='campus_life' AND table_name='goods' AND index_name='idx_goods_hot')=0,
  'ALTER TABLE goods ADD INDEX idx_goods_hot (status, view_count, favorite_count, contact_count)',
  'SELECT "idx_goods_hot exists"'));
PREPARE s FROM @i1; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 5. 从已有数据回填初始热度（幂等：只回填仍为 0 的行） ============
-- 让「今日推荐」在冷启动时也有内容；之后完全由真实行为驱动。
-- 二手：按发布时间给一个很小的基础值，不掩盖真实行为差异。
UPDATE goods SET view_count = 12 WHERE view_count = 0 AND status = 1;
-- 外卖：把历史订单回填到店铺的真实订单数
UPDATE shop s SET order_count = (
  SELECT COUNT(*) FROM user_order o WHERE o.shop_id = s.id
  AND o.status <> 'cancelled'
) WHERE s.order_count = 0;

-- ============ 6. 结果核对 ============
SELECT '二手商品热度' AS 项目, id, title, view_count, favorite_count, contact_count FROM goods WHERE status=1 ORDER BY id;
SELECT '外卖店铺热度' AS 项目, id, name, order_count, view_count, monthly_sales FROM shop ORDER BY id;
