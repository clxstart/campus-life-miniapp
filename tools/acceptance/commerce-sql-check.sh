#!/usr/bin/env bash
# 商业链路整改 SQL 在真实 MySQL 上的只读校验（只用本地开发库，不连生产）。
# 用法：DB_PASSWORD=*** bash tools/acceptance/commerce-sql-check.sh
# 覆盖：对账批次 / 支付列表 / 导出键集 / 租约锁 的语法与执行计划；锁的互斥语义（用独立锁名，结束后清理）。
set -euo pipefail
MYSQL=${MYSQL:-/d/mysql/bin/mysql}
DB=${DB_NAME:-campus_life}
export MYSQL_PWD="${DB_PASSWORD:?需要 DB_PASSWORD}"
q() { "$MYSQL" -uroot -h127.0.0.1 "$DB" -N -e "$1"; }

echo "== 对账批次 EXPLAIN（期望 key=idx_order_pay_status，带 LIMIT）"
q "EXPLAIN SELECT id FROM user_order WHERE pay_status='unpaid' AND status='created' AND created_at < NOW() - INTERVAL 5 MINUTE AND (pay_check_at IS NULL OR pay_check_at < NOW() - INTERVAL 50 SECOND) ORDER BY id ASC LIMIT 100"

echo "== 支付列表（联表一次取回，关键词前缀匹配）EXPLAIN"
q "EXPLAIN SELECT o.id, COALESCE(u.nickname,''), s.name,
      COALESCE((SELECT p.verified FROM payment_record p WHERE p.order_id=o.id ORDER BY p.id DESC LIMIT 1),1)
    FROM user_order o JOIN shop s ON s.id=o.shop_id LEFT JOIN \`user\` u ON u.id=o.user_id
    WHERE o.shop_id IN (1,2,3) AND o.pay_status='paid'
      AND (o.out_trade_no LIKE CONCAT('T2026','%') OR o.transaction_id LIKE CONCAT('T2026','%')
           OR EXISTS (SELECT 1 FROM payment_record pk WHERE pk.order_id=o.id AND pk.transaction_id LIKE CONCAT('T2026','%')))
    ORDER BY o.paid_at DESC, o.id DESC LIMIT 20 OFFSET 0"

echo "== 导出键集分页 EXPLAIN（无深 OFFSET）"
q "EXPLAIN SELECT o.id FROM user_order o JOIN shop s ON s.id=o.shop_id WHERE o.shop_id IN (1,2,3) AND o.id < 1000000 ORDER BY o.id DESC LIMIT 500"

echo "== 租约锁互斥语义"
q "DELETE FROM scheduler_lock WHERE name='acceptance-lock'"
q "INSERT IGNORE INTO scheduler_lock(name) VALUES ('acceptance-lock')"
a=$(q "UPDATE scheduler_lock SET owner='A', locked_until=DATE_ADD(NOW(), INTERVAL 60 SECOND) WHERE name='acceptance-lock' AND (locked_until < NOW() OR owner='A'); SELECT ROW_COUNT();")
b=$(q "UPDATE scheduler_lock SET owner='B', locked_until=DATE_ADD(NOW(), INTERVAL 60 SECOND) WHERE name='acceptance-lock' AND (locked_until < NOW() OR owner='B'); SELECT ROW_COUNT();")
q "UPDATE scheduler_lock SET locked_until='1970-01-01 00:00:00' WHERE name='acceptance-lock' AND owner='A'"
c=$(q "UPDATE scheduler_lock SET owner='B', locked_until=DATE_ADD(NOW(), INTERVAL 60 SECOND) WHERE name='acceptance-lock' AND (locked_until < NOW() OR owner='B'); SELECT ROW_COUNT();")
q "DELETE FROM scheduler_lock WHERE name='acceptance-lock'"
echo "A 获取=$a  B 抢占=$b  A 释放后 B 获取=$c"
[[ "$a" == "1" && "$b" == "0" && "$c" == "1" ]] && echo "锁语义 OK" || { echo "锁语义 FAIL"; exit 1; }
