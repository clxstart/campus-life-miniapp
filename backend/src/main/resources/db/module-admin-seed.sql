-- 默认管理员账号（演示用）
-- ⚠️ 生产环境首次登录后必须立即改密，或直接删除这两个演示账号。
-- 密码为 BCrypt 哈希，无法反推；明文写在注释里仅为演示方便。
USE campus_life;

-- 超级管理员：全平台
--   账号 admin / 密码 admin123
INSERT IGNORE INTO admin_user(username, password_hash, real_name, role, school_id) VALUES
 ('admin', '$2a$10$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6', '平台管理员', 'super', NULL);

-- 校管（内蒙古大学，school_id=1）
--   账号 neimenggu / 密码 school123
INSERT IGNORE INTO admin_user(username, password_hash, real_name, role, school_id) VALUES
 ('neimenggu', '$2a$10$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK', '内蒙古大学管理员', 'school', 1);

SELECT id, username, real_name, role, school_id, status FROM admin_user;
