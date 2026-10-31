#!/bin/bash
# 圈子火花验收：同日一次 / 连续3天点亮 / 漏日重置
set -u
API=http://127.0.0.1:8080/api
MYSQL="mysql -uroot -pchang090011 -N"

T1=$(curl -s -X POST $API/auth/dev-login -H "Content-Type: application/json" -d '{"userId":1}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
CIRCLE=2
RUN=$(date +%s)

# 发布限流是「同用户每分钟 10 次」，本测试共发 5 条。
# 连续跑两次就会撞窗口，所以先等一个干净窗口，保证结果可复现。
echo "等待限流窗口刷新（约 61s）..."
sleep 61

reset_state() {
  $MYSQL -e "delete from campus_life.circle_activity_day where circle_id=$CIRCLE;" 2>/dev/null
  $MYSQL -e "update campus_life.circle set streak_days=0, last_active_date=NULL, spark_on=0 where id=$CIRCLE;" 2>/dev/null
}
send_msg() {
  # clientId 必须每轮唯一：复用同一个 clientId 会命中消息幂等，返回既有消息而不产生新活跃日
  printf '{"circleId":%s,"type":"text","content":"%s","clientId":"%s-%s"}' "$CIRCLE" "$1" "$2" "$RUN" > /tmp/msg.json
  curl -s -X POST $API/circle-messages -H "Content-Type: application/json" -H "Authorization: Bearer $T1" --data-binary @/tmp/msg.json
}
# 库里的原始值
streak_of() {
  $MYSQL -e "select concat(streak_days,'|',ifnull(last_active_date,'NULL'),'|',spark_on) from campus_life.circle where id=$CIRCLE;" 2>/dev/null | tr -d '\r'
}
# 接口返回的展示值（只取嵌套的 streak 对象，避免和扁平字段重复匹配）
get_detail() {
  curl -s "$API/circles/$CIRCLE?userId=1" -H "Authorization: Bearer $T1" | tr -d '\n\r' | grep -o '"streak":{[^}]*}' | head -1
}
day_count() {
  $MYSQL -e "select count(*) from campus_life.circle_activity_day where circle_id=$CIRCLE;" 2>/dev/null | tr -d '\r'
}

pass=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "PASS $1  ($2)"; pass=$((pass+1));
  else echo "FAIL $1  期望[$3] 实际[$2]"; fail=$((fail+1)); fi
}
field() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2 | tr -d '"'; }

echo "=== 场景1：当天首发消息 -> 连续1天，火花未点亮 ==="
reset_state
send_msg "hi streak" "c1" >/dev/null
S=$(streak_of); D=$(get_detail)
check "库内连续天数=1" "$(echo "$S" | cut -d'|' -f1)" "1"
check "库内火花=0" "$(echo "$S" | cut -d'|' -f3)" "0"
check "接口 activeToday" "$(field "$D" activeToday)" "true"
check "接口 streakDays" "$(field "$D" streakDays)" "1"
check "接口 sparkOn" "$(field "$D" sparkOn)" "false"

echo "=== 场景2：同一天再发 2 条 -> 仍是 1 天 ==="
send_msg "第二条" "c2" >/dev/null
send_msg "第三条" "c3" >/dev/null
S=$(streak_of)
check "同日多条不重复计天" "$(echo "$S" | cut -d'|' -f1)" "1"
check "活跃日只记 1 行" "$(day_count)" "1"

echo "=== 场景3：昨天已连续2天 -> 今天第3天点亮火花 ==="
reset_state
YDAY=$($MYSQL -e "select date_sub(curdate(), interval 1 day);" 2>/dev/null | tr -d '\r')
$MYSQL -e "update campus_life.circle set streak_days=2, last_active_date='$YDAY', spark_on=0 where id=$CIRCLE;" 2>/dev/null
send_msg "第三天" "c4" >/dev/null
S=$(streak_of); D=$(get_detail)
check "第3天连续=3" "$(echo "$S" | cut -d'|' -f1)" "3"
check "火花点亮" "$(echo "$S" | cut -d'|' -f3)" "1"
check "接口 sparkOn=true" "$(field "$D" sparkOn)" "true"

echo "=== 场景4：漏一天（上次活跃是2天前）-> 展示必须归零 ==="
reset_state
D2=$($MYSQL -e "select date_sub(curdate(), interval 2 day);" 2>/dev/null | tr -d '\r')
$MYSQL -e "update campus_life.circle set streak_days=5, last_active_date='$D2', spark_on=1 where id=$CIRCLE;" 2>/dev/null
D=$(get_detail)
check "漏日展示为0（不能拿旧值骗人）" "$(field "$D" streakDays)" "0"
check "漏日火花熄灭" "$(field "$D" sparkOn)" "false"
check "漏日 activeToday=false" "$(field "$D" activeToday)" "false"

echo "=== 场景5：断掉后再活跃 -> 从1重新开始 ==="
send_msg "重新开始" "c5" >/dev/null
S=$(streak_of)
check "重置为1" "$(echo "$S" | cut -d'|' -f1)" "1"
check "火花重新熄灭" "$(echo "$S" | cut -d'|' -f3)" "0"

echo ""
echo "结果: $pass 通过, $fail 失败"
[ "$fail" -eq 0 ] || exit 1
