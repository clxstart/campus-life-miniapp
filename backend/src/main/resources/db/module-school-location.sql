-- 每校自定义地点（school_location）
-- 手动执行；可重复执行。
--
-- 背景：发帖/发兼职时选的地点原来是前端硬编码的
--   ['内蒙古大学','紫荆操场','逸夫图书馆','东区篮球场','学生公寓']
-- 其中「紫荆操场」是清华的、「内蒙古大学」是学校名不是地点，多校铺开后完全没法用。
-- 改成每校维护自己的地点列表，由管理端配置、小程序按选中学校拉取。
USE campus_life;

CREATE TABLE IF NOT EXISTS school_location (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id  BIGINT      NOT NULL,
  name       VARCHAR(32) NOT NULL,
  sort       INT         NOT NULL DEFAULT 0,
  status     TINYINT     NOT NULL DEFAULT 1,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 同一所学校内地点名不能重复
  UNIQUE KEY uk_school_location_name (school_id, name),
  KEY idx_school_location_school (school_id, status, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每校自定义地点';

-- ============ 种子数据：给已有学校各插一套通用地点 ============
-- 用 INSERT IGNORE 配合唯一键，重复执行不会产生重复行。
INSERT IGNORE INTO school_location (school_id, name, sort, status)
SELECT s.id, l.name, l.sort, 1
FROM school s
JOIN (
  SELECT '图书馆' AS name, 10 AS sort
  UNION ALL SELECT '教学楼', 20
  UNION ALL SELECT '食堂',   30
  UNION ALL SELECT '操场',   40
  UNION ALL SELECT '宿舍',   50
  UNION ALL SELECT '校门口', 60
  UNION ALL SELECT '其他',   70
) l
WHERE s.status = 1;

SELECT s.id AS 学校ID, s.name AS 学校, COUNT(l.id) AS 地点数
FROM school s LEFT JOIN school_location l ON l.school_id = s.id AND l.status = 1
GROUP BY s.id, s.name ORDER BY s.id;
