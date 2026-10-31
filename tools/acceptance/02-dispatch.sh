#!/bin/bash
# 配送楼栋派单验收（方案 §11.8 / §11.9）
set -u
API=http://127.0.0.1:8080/api
MYSQL="mysql -uroot -pchang090011 -N"

UT=$(curl -s -X POST $API/auth/dev-login -H "Content-Type: application/json" -d '{"userId":1}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AT=$(curl -s -X POST $API/admin/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
R9=$(curl -s -X POST $API/admin/login -H "Content-Type: application/json" -d '{"username":"rider01","password":"rider123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
R21=$(curl -s -X POST $API/admin/login -H "Content-Type: application/json" -d '{"username":"rider02","password":"rider123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

echo "tokens: user=${#UT} admin=${#AT} rider01=${#R9} rider02=${#R21}"

# 前置：把参与测试的骑手恢复成可用，并清掉历史未完成订单，
# 否则上一轮遗留的 busy 状态会让自动派单正确地进入队列（那是正确行为，不是 bug）
$MYSQL -e "update campus_life.user_order set status='closed' where rider_id in (9,21) and status in ('delivering','picked');" 2>/dev/null
$MYSQL -e "update campus_life.admin_user set rider_status='idle' where id in (9,21);" 2>/dev/null

GOODS=$($MYSQL -e "select id from campus_life.shop_goods where shop_id=1 limit 1;" 2>/dev/null | tr -d '\r')
echo "shop=1 goods=$GOODS"

pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1  ($2)"; pass=$((pass+1)); else echo "FAIL $1  期望[$3] 实际[$2]"; fail=$((fail+1)); fi }

make_order() {
  printf '{"userId":1,"shopId":1,"items":[{"goodsId":%s,"count":1}],"address":"%s","contactName":"张三","contactPhone":"13800000000"}' "$GOODS" "$1" > /tmp/order.json
  curl -s -X POST $API/orders -H "Content-Type: application/json" -H "Authorization: Bearer $UT" --data-binary @/tmp/order.json | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1
}
# 模拟"已支付 + 商家已接单"
mark_accepted() { $MYSQL -e "update campus_life.user_order set pay_status='paid', status='accepted' where id=$1;" 2>/dev/null; }
ocol() { $MYSQL -e "select $2 from campus_life.user_order where id=$1;" 2>/dev/null | tr -d '\r'; }

echo ""
echo "=== 准备：楼栋责任区（学校1 / 学生公寓6号楼 -> 骑手 rider01=id9）==="
$MYSQL -e "delete from campus_life.building where school_id=1;" 2>/dev/null
printf '{"name":"学生公寓6号楼","matchKeywords":"6号楼,6栋,六号楼","defaultRiderId":9,"schoolId":1}' > /tmp/b.json
curl -s -X POST $API/admin/dispatch/buildings -H "Content-Type: application/json" -H "Authorization: Bearer $AT" --data-binary @/tmp/b.json | head -c 200
echo ""

echo "=== 场景1：地址能匹配楼栋 -> 自动派给默认骑手 ==="
O1=$(make_order "学生公寓6号楼312")
echo "  订单 id=$O1"
mark_accepted $O1
curl -s -X POST $API/admin/dispatch/redispatch -H "Authorization: Bearer $AT"
echo ""
check "责任骑手=9(默认)" "$(ocol $O1 rider_id)" "9"
check "派单状态=auto" "$(ocol $O1 dispatch_status)" "auto"
check "订单进入 delivering" "$(ocol $O1 status)" "delivering"
check "配送费已快照成分" "$([ "$(ocol $O1 delivery_pay_cents)" -gt 0 ] && echo yes)" "yes"
echo "  楼栋: $($MYSQL -e "select name from campus_life.building where id=$(ocol $O1 building_id);" 2>/dev/null | tr -d '\r')"

echo ""
echo "=== 场景2：地址匹配不到楼栋 -> 进异常队列，不瞎派 ==="
O2=$(make_order "不存在的火星基地1号")
echo "  订单 id=$O2"
mark_accepted $O2
curl -s -X POST $API/admin/dispatch/redispatch -H "Authorization: Bearer $AT" >/dev/null
check "无责任骑手" "$(ocol $O2 rider_id)" "NULL"
check "派单状态=queued" "$(ocol $O2 dispatch_status)" "queued"
check "队列里能看到这单" "$(curl -s "$API/admin/dispatch/queue" -H "Authorization: Bearer $AT" | grep -c "\"id\":$O2,")" "1"

echo ""
echo "=== 场景3：负责人改派 -> 旧骑手立即失去推进权限 ==="
printf '{"riderId":21,"action":"reassign","reason":"默认骑手临时请假"}' > /tmp/a.json
curl -s -X POST $API/admin/dispatch/orders/$O1/assign -H "Content-Type: application/json" -H "Authorization: Bearer $AT" --data-binary @/tmp/a.json
echo ""
check "责任骑手改为21" "$(ocol $O1 rider_id)" "21"
check "派单状态=reassign" "$(ocol $O1 dispatch_status)" "reassign"
echo "  --- 旧骑手 rider01 尝试取货（应被拒） ---"
curl -s -o /tmp/r.json -w "  http=%{http_code} " -X POST $API/rider/orders/$O1/pick -H "Authorization: Bearer $R9"; cat /tmp/r.json | head -c 120; echo ""
check "旧骑手推进失败(非200)" "$(grep -c '"code":0' /tmp/r.json)" "0"
echo "  --- 新骑手 rider02 取货（应成功） ---"
curl -s -o /tmp/r2.json -w "  http=%{http_code} " -X POST $API/rider/orders/$O1/pick -H "Authorization: Bearer $R21"; cat /tmp/r2.json | head -c 120; echo ""
check "新骑手取货成功" "$(grep -c '"code":0' /tmp/r2.json)" "1"
check "订单状态=picked" "$(ocol $O1 status)" "picked"

echo ""
echo "=== 场景4：改派留痕（谁操作/原责任人/新责任人/原因）==="
LOG=$(curl -s "$API/admin/dispatch/orders/$O1/log" -H "Authorization: Bearer $AT")
echo "  $LOG" | head -c 400; echo ""
check "有 auto 记录" "$(echo "$LOG" | grep -c '"action":"auto"')" "1"
check "有 reassign 记录" "$(echo "$LOG" | grep -c '"action":"reassign"')" "1"
check "记录原责任人=9" "$(echo "$LOG" | grep -c '"fromRiderId":9')" "1"
check "记录新责任人=21" "$(echo "$LOG" | grep -c '"toRiderId":21')" "1"

echo ""
echo "=== 场景5：送达只记一次配送报酬 ==="
$MYSQL -e "delete from campus_life.earnings_ledger where biz_type='delivery' and ref_id=$O1;" 2>/dev/null
curl -s -X POST $API/rider/orders/$O1/deliver -H "Authorization: Bearer $R21" >/dev/null
check "订单已完成" "$(ocol $O1 status)" "completed"
check "报酬已标记" "$(ocol $O1 delivery_pay_recorded)" "1"
check "账单恰好1条" "$($MYSQL -e "select count(*) from campus_life.earnings_ledger where biz_no='delivery:$O1:income';" 2>/dev/null | tr -d '\r')" "1"
echo "  --- 重复调用送达（应失败，且不产生第二条账单）---"
curl -s -X POST $API/rider/orders/$O1/deliver -H "Authorization: Bearer $R21" | head -c 120; echo ""
check "重复送达不重复记账" "$($MYSQL -e "select count(*) from campus_life.earnings_ledger where biz_type='delivery' and ref_id=$O1;" 2>/dev/null | tr -d '\r')" "1"

echo ""
echo "=== 场景6：抢单接口默认关闭 ==="
GCODE=$(curl -s -o /tmp/g.json -w '%{http_code}' -X POST $API/rider/orders/$O2/grab -H "Authorization: Bearer $R9")
echo "  http=$GCODE $(head -c 160 /tmp/g.json)"
check "自由抢单被拒(403)" "$GCODE" "403"

echo ""
echo "结果: $pass 通过, $fail 失败"
[ "$fail" -eq 0 ] || exit 1
