-- 生产演示账号清理（必须先审阅，再执行）
-- 目标：停用仍在使用仓库历史中已公开演示密码的 admin_user 账号。
-- 安全策略：先 SELECT 列出候选；确认后才执行 UPDATE。重复执行是幂等的。
USE campus_life;

-- 0) 凭据轮换检查：商家/骑手历史上用过「手机号后 6 位」等可预测初始密码。
--    credential_rotated_at IS NULL 的启用账号会让生产启动守卫直接拒绝启动。
--    处置：在管理端「骑手管理」执行一次重置密码；商家账号由管理员重置，写入轮换时间。
SELECT id, username, role, created_at
FROM admin_user
WHERE status = 1
  AND role IN ('merchant','rider')
  AND credential_rotated_at IS NULL
ORDER BY role, id;

-- 1) 先看候选账号（此步只读）
SELECT id, username, real_name, role, school_id, shop_id, status, created_at
FROM admin_user
WHERE status = 1
  AND password_hash IN (
    '$2a$10$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6',
    '$2a$10$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK',
    '$2a$10$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6',
    '$2a$10$ubOWq/4fPhG11dtWoRVvvOKuZ.672R.y/uCjKYwX0PiFejdx0iHhe'
  )
ORDER BY id;

-- 2) 人工确认后执行下面语句（默认注释，防止误跑）
-- UPDATE admin_user
-- SET status = 0,
--     token_version = token_version + 1,
--     fail_count = 0,
--     locked_until = NULL
-- WHERE status = 1
--   AND password_hash IN (
--     '$2a$10$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6',
--     '$2a$10$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK',
--     '$2a$10$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6',
--     '$2a$10$ubOWq/4fPhG11dtWoRVvvOKuZ.672R.y/uCjKYwX0PiFejdx0iHhe'
--   );

-- 3) 执行后复核：应返回 0 行
SELECT id, username, role, status
FROM admin_user
WHERE status = 1
  AND password_hash IN (
    '$2a$10$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6',
    '$2a$10$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK',
    '$2a$10$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6',
    '$2a$10$ubOWq/4fPhG11dtWoRVvvOKuZ.672R.y/uCjKYwX0PiFejdx0iHhe'
  );