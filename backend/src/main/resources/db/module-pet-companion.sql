-- 校园搭子 · AI 宠物模块（外观 / 衣柜 / 派遣）
-- 可重复执行。执行顺序：在 module-pet.sql 之后。

USE campus_life;

-- ============ 1. 宠物外观：保存明确部件编号，不存随机种子 ============
SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='fur')=0,
  'ALTER TABLE user_pet ADD COLUMN fur VARCHAR(16) NOT NULL DEFAULT ''cream''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='pattern')=0,
  'ALTER TABLE user_pet ADD COLUMN pattern VARCHAR(16) NOT NULL DEFAULT ''none''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='eyes')=0,
  'ALTER TABLE user_pet ADD COLUMN eyes VARCHAR(16) NOT NULL DEFAULT ''round''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='mouth')=0,
  'ALTER TABLE user_pet ADD COLUMN mouth VARCHAR(16) NOT NULL DEFAULT ''smile''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='campus_life' AND table_name='user_pet' AND column_name='accessory')=0,
  'ALTER TABLE user_pet ADD COLUMN accessory VARCHAR(16) NOT NULL DEFAULT ''none''', 'SELECT 1'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ============ 2. 衣柜物品字典 ============
CREATE TABLE IF NOT EXISTS pet_item (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  code          VARCHAR(32)  NOT NULL,
  name          VARCHAR(32)  NOT NULL,
  category      VARCHAR(16)  NOT NULL,          -- accessory
  part_id       VARCHAR(16)  NOT NULL,          -- 对应 appearance 的部件编号
  price         INT          NOT NULL DEFAULT 0,
  description   VARCHAR(120) NOT NULL DEFAULT '',
  sort_num      INT          NOT NULL DEFAULT 0,
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pet_item_code (code),
  KEY idx_pet_item_status (status, sort_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 3. 用户已拥有物品 ============
CREATE TABLE IF NOT EXISTS pet_item_owned (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  item_id     BIGINT NOT NULL,
  equipped    TINYINT NOT NULL DEFAULT 0,       -- 1=已装备
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pet_owned (user_id, item_id),
  KEY idx_pet_owned_user (user_id, equipped)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 4. 派遣任务字典 ============
CREATE TABLE IF NOT EXISTS pet_task (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  code          VARCHAR(32)  NOT NULL,
  title         VARCHAR(48)  NOT NULL,
  description   VARCHAR(200) NOT NULL DEFAULT '',
  duration_min  INT          NOT NULL,          -- 预计耗时（分钟）
  reward_min    INT          NOT NULL,          -- 奖励下限
  reward_max    INT          NOT NULL,          -- 奖励上限
  sort_num      INT          NOT NULL DEFAULT 0,
  status        TINYINT      NOT NULL DEFAULT 1,
  UNIQUE KEY uk_pet_task_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 5. 用户派遣记录 ============
-- 进度由服务端按 start_at + end_at 计算，不依赖客户端倒计时
CREATE TABLE IF NOT EXISTS pet_dispatch (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL,
  task_id      BIGINT NOT NULL,
  start_at     DATETIME NOT NULL,
  end_at       DATETIME NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'running',  -- running / claimable / claimed
  reward       INT NOT NULL DEFAULT 0,                  -- 发放时写入实际奖励
  claimed_at   DATETIME NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pet_dispatch_user (user_id, status),
  KEY idx_pet_dispatch_end (status, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 6. 种子数据 ============
INSERT IGNORE INTO pet_item (code, name, category, part_id, price, description, sort_num) VALUES
 ('acc_scarf',  '陶土围巾', 'accessory', 'scarf',   0,   '领养时赠送的见面礼', 1),
 ('acc_bell',   '小铃铛',   'accessory', 'bell',    120, '走路会叮当响',       2),
 ('acc_glasses','圆眼镜',   'accessory', 'glasses', 180, '看起来很有学问',     3),
 ('acc_leaf',   '叶子发夹', 'accessory', 'leaf',    90,  '春天捡到的小叶子',   4);

INSERT IGNORE INTO pet_task (code, title, description, duration_min, reward_min, reward_max, sort_num) VALUES
 ('task_library', '去图书馆占座', '小云替你去三楼靠窗找个好位置', 5,  20, 30, 1),
 ('task_canteen', '食堂探路',     '看看二食堂今天有什么好吃的',   15, 45, 70, 2),
 ('task_campus',  '校园散步',     '绕着操场走一圈，顺便晒晒太阳', 30, 80, 120, 3),
 ('task_errand',  '帮忙取快递',   '菜鸟驿站的那件，记得带取件码', 60, 140, 200, 4);
