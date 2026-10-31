#!/bin/bash
# 校外岗位：发布 -> 报名 -> 录用/拒绝 -> 名额上限 -> 重复录用幂等
set -u
API=http://127.0.0.1:8080/api
MYSQL="mysql -uroot -pchang090011 -N"

login() { curl -s -X POST $API/auth/dev-login -H "Content-Type: application/json" -d "{\"userId\":$1}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }
T1=$(login 1); T3=$(login 3); T5=$(login 5)
echo "tokens: u1=${#T1} u3=${#T3} u5=${#T5}"

pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1  ($2)"; pass=$((pass+1)); else echo "FAIL $1  期望[$3] 实际[$2]"; fail=$((fail+1)); fi }
post() { curl -s -o /tmp/r.json -w '%{http_code}' -X POST "$API$1" -H "Content-Type: application/json" -H "Authorization: Bearer $2" --data-binary @"$3"; }
code_of() { grep -o '"code":[0-9]*' /tmp/r.json | head -1 | cut -d: -f2; }

# ---- 开闸（测完复原）----
# 必须走管理端接口：直接改数据库会被 FeatureFlags 的 30 秒缓存挡住，测出来的结论是假的
AT=$(curl -s -X POST $API/admin/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
setflag() { printf '{"enabled":%s}' "$2" > /tmp/f.json; curl -s -o /dev/null -X PUT "$API/admin/feature-flags/$1" -H "Content-Type: application/json" -H "Authorization: Bearer $AT" --data-binary @/tmp/f.json; }
setflag job.offsite.enabled true
setflag job.paid.enabled true
# 有偿岗位要求"人已核验"：3 个参与者都补一条真实的人工核验记录
for u in 1 3 5; do
  $MYSQL -e "insert into campus_life.user_verification(user_id,real_name_status,student_status,provider) values($u,'verified','verified','manual_admin') on duplicate key update real_name_status='verified',student_status='verified';" 2>/dev/null
done
restore() { setflag job.offsite.enabled false; setflag job.paid.enabled false; }
trap restore EXIT

echo ""
echo "=== 场景1：发布校外岗位（headcount=1，校外+有偿，user1 已核验）==="
printf '{"title":"校外图书馆整理","description":"整理书架","scope":"offsite","takeMode":"apply","categoryKey":"other","payCents":2000,"payUnit":"hour","headcount":1,"schoolId":1,"location":"市图书馆","feeMode":"rate","feeValue":500}' > /tmp/job.json
H=$(post "/jobs" "$T1" /tmp/job.json)
echo "  http=$H $(head -c 200 /tmp/r.json)"
JOB=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' /tmp/r.json | head -1)
check "发布成功" "$H" "200"
check "服务费率被忽略(fee_mode=none)" "$($MYSQL -e "select fee_mode from campus_life.job where id=$JOB;" 2>/dev/null | tr -d '\r')" "none"

echo ""
echo "=== 场景2：两个学生报名 ==="
printf '{"message":"我想去"}' > /tmp/ap.json
H3=$(post "/jobs/$JOB/apply" "$T3" /tmp/ap.json); A3=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' /tmp/r.json | head -1)
H5=$(post "/jobs/$JOB/apply" "$T5" /tmp/ap.json); A5=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' /tmp/r.json | head -1)
check "用户3 报名成功" "$H3" "200"
check "用户5 报名成功" "$H5" "200"
echo "  重复报名: $(post "/jobs/$JOB/apply" "$T3" /tmp/ap.json) $(grep -o '\"alreadyApplied\":[a-z]*' /tmp/r.json)"

echo ""
echo "=== 场景3：雇主看报名列表 ==="
curl -s "$API/jobs/$JOB/applications" -H "Authorization: Bearer $T1" > /tmp/list.json
check "列表有2条报名" "$(grep -o '"applicantId"' /tmp/list.json | wc -l | tr -d ' ')" "2"
echo "  非发布者查看: http=$(curl -s -o /dev/null -w '%{http_code}' "$API/jobs/$JOB/applications" -H "Authorization: Bearer $T3")"

echo ""
echo "=== 场景4：录用用户3 -> 建合约、占名额 ==="
printf '{"approve":true,"reason":"安排到周六"}' > /tmp/dc.json
H=$(post "/jobs/$JOB/applications/$A3/decide" "$T1" /tmp/dc.json)
echo "  http=$H $(head -c 200 /tmp/r.json)"
check "录用成功" "$H" "200"
check "合约已创建" "$($MYSQL -e "select count(*) from campus_life.job_contract where job_id=$JOB;" 2>/dev/null | tr -d '\r')" "1"
check "名额已占用" "$($MYSQL -e "select accepted_count from campus_life.job where id=$JOB;" 2>/dev/null | tr -d '\r')" "1"
check "报名状态=accepted" "$($MYSQL -e "select status from campus_life.job_application where id=$A3;" 2>/dev/null | tr -d '\r')" "accepted"

echo ""
echo "=== 场景5：名额已满 -> 录用第二人被拒 ==="
H=$(post "/jobs/$JOB/applications/$A5/decide" "$T1" /tmp/dc.json)
echo "  http=$H $(head -c 160 /tmp/r.json)"
check "名额满被拒(409)" "$H" "409"
check "未多建合约" "$($MYSQL -e "select count(*) from campus_life.job_contract where job_id=$JOB;" 2>/dev/null | tr -d '\r')" "1"

echo ""
echo "=== 场景6：重复录用同一条 -> 409 且名额不回退 ==="
H=$(post "/jobs/$JOB/applications/$A3/decide" "$T1" /tmp/dc.json)
echo "  http=$H $(head -c 160 /tmp/r.json)"
check "重复录用被拒" "$H" "409"
check "名额仍是1" "$($MYSQL -e "select accepted_count from campus_life.job where id=$JOB;" 2>/dev/null | tr -d '\r')" "1"

echo ""
echo "=== 场景7：非发布者不能录用 ==="
H=$(post "/jobs/$JOB/applications/$A5/decide" "$T3" /tmp/dc.json)
check "越权录用被拒(403)" "$H" "403"

echo ""
echo "=== 场景8：校外开关关闭后，报名被拒 ==="
restore
H=$(post "/jobs/$JOB/apply" "$T3" /tmp/ap.json)
check "闸门关闭后报名 403" "$H" "403"
H=$(post "/jobs" "$T1" /tmp/job.json)
check "闸门关闭后发布 403" "$H" "403"

echo ""
echo "结果: $pass 通过, $fail 失败"
[ "$fail" -eq 0 ] || exit 1
