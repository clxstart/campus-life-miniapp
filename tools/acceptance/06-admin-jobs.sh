#!/usr/bin/env bash
# ==========================================================================
# 兼职管理端 · 履约单列表与争议处理台验收
#
# 验证本轮新增的 GET /api/admin/jobs/contracts：
#   * 超管 / 校管的可见范围（校管只能看本校）
#   * 列表字段必须是履约单快照，不是岗位当前配置
#   * 状态过滤白名单、非法状态 400
#   * 非管理员 / 匿名不能访问
#   * 从列表进入详情、裁定、审计、幂等
#
# 前置：后端跑在 127.0.0.1:8080（CAMPUS_DEV_LOGIN_ANY_USER=true），
#       MySQL root 密码 chang090011，库 campus_life。
# ==========================================================================
set -u

BASE=http://127.0.0.1:8080
MYSQL_PASS=chang090011
TMP=$(mktemp -d)
PASS=0
FAIL=0

q() { mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_PASS" -N -B -e "USE campus_life; $1" 2>/dev/null; }

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1)); echo "  PASS  $1  [$2]"
  else
    FAIL=$((FAIL + 1)); echo "  FAIL  $1  (expected [$2], got [$3])"
  fi
}

req() { # METHOD PATH TOKEN -> HTTP code, body in $TMP/last.json
  local method=$1 path=$2 token=${3:-}
  local args=(-s -o "$TMP/last.json" -w "%{http_code}" -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  curl "${args[@]}"
}

post() { # PATH TOKEN JSON_FILE -> HTTP code
  local path=$1 token=${2:-} data=${3:-}
  local args=(-s -o "$TMP/last.json" -w "%{http_code}" -X POST "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$data")
  curl "${args[@]}"
}

put() { # PATH TOKEN JSON_FILE -> HTTP code
  local path=$1 token=${2:-} data=${3:-}
  local args=(-s -o "$TMP/last.json" -w "%{http_code}" -X PUT "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$data")
  curl "${args[@]}"
}

login_admin() { # USER PASS -> token
  curl -s -X POST "$BASE/api/admin/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "== (0) fixtures ======================================================"
cat > "$TMP/fixtures.sql" <<'SQL'
USE campus_life;
DELETE FROM admin_log WHERE action = 'job_arbitrate' AND target_id IN ('900101','900102');
DELETE FROM earnings_ledger WHERE biz_type='job' AND ref_id IN (900101,900102,900103,900104,900105);
DELETE FROM job_dispute WHERE contract_id IN (900101,900102,900103,900104,900105);
DELETE FROM job_contract WHERE id IN (900101,900102,900103,900104,900105);
DELETE FROM job WHERE id IN (900011,900012,900013,900014,900015);

INSERT INTO job(id,title,day_salary,settle,location,time_desc,status,user_id,description,school_id,
                audit_status,audit_reason,audit_at,`scope`,employer_kind,take_mode,category_key,
                pay_cents,pay_unit,fee_mode,fee_value,headcount,deadline_at,workflow_status,taken_by,accepted_count)
VALUES
 (900011,'E2E admin contract school1',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','rate',500,1,NULL,'disputed',NULL,0),
 (900012,'E2E admin contract school2',2000,'daily','Gym','night','open',5,'','2','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','none',0,1,NULL,'disputed',NULL,0),
 (900013,'E2E admin contract settled',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','none',0,1,NULL,'settled',NULL,0),
 (900014,'E2E multi-seat offsite',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'offsite','partner','apply','other',2000,'hour','none',0,2,NULL,'recruiting',NULL,1),
 (900015,'E2E seat release',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'offsite','partner','apply','other',2000,'hour','none',0,2,NULL,'recruiting',NULL,1);

INSERT INTO job_contract(id,job_id,worker_id,employer_id,status,fee_mode_snapshot,fee_value_snapshot,
                         pay_cents_snapshot,pay_unit_snapshot,worker_hours,employer_hours,agreed_hours,created_at)
VALUES
 (900101,900011,2,5,'disputed','rate',500,2000,'hour',3.00,2.00,NULL,NOW()),
 (900102,900012,2,5,'disputed','none',0,2000,'hour',1.00,1.00,NULL,NOW()),
 (900103,900013,2,5,'settled','none',0,2000,'hour',2.00,2.00,2.00,NOW()),
 (900104,900014,2,5,'created','none',0,2000,'hour',NULL,NULL,NULL,NOW()),
 (900105,900015,2,5,'created','none',0,2000,'hour',NULL,NULL,NULL,NOW());

INSERT INTO job_dispute(id,contract_id,raised_by,reason,status,created_at)
VALUES
 (900201,900101,5,'工时不一致，需要人工裁定','open',NOW()),
 (900202,900102,2,'对工时存在异议','open',NOW());

INSERT INTO earnings_ledger(biz_no,biz_type,ref_id,user_id,entry_type,amount_cents,status,note)
VALUES
 ('job:900101:payable','job',900101,5,'job_payable',6000,'frozen','争议冻结'),
 ('job:900101:fee','job',900101,NULL,'platform_fee',300,'frozen','争议冻结'),
 ('job:900101:income','job',900101,2,'job_income',5700,'frozen','争议冻结');
SQL
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_PASS" < "$TMP/fixtures.sql" 2>&1 | grep -v "Using a password" || true
echo "fixtures ready: $(q "SELECT COUNT(*) FROM job_contract WHERE id IN (900101,900102,900103)") contracts"

USER_TOKEN=$(curl -s -X POST "$BASE/api/auth/dev-login" -H 'Content-Type: application/json' -d '{"userId":2}' \
  | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
ADMIN=$(login_admin "${ACCEPTANCE_ADMIN_USER:?请设置 ACCEPTANCE_ADMIN_USER}" "${ACCEPTANCE_ADMIN_PASSWORD:?请设置 ACCEPTANCE_ADMIN_PASSWORD}")
SCHOOL_ADMIN=$(login_admin "${ACCEPTANCE_SCHOOL_ADMIN_USER:-${ACCEPTANCE_ADMIN_USER:?请设置 ACCEPTANCE_ADMIN_USER}}" "${ACCEPTANCE_SCHOOL_ADMIN_PASSWORD:-${ACCEPTANCE_ADMIN_PASSWORD:?请设置 ACCEPTANCE_ADMIN_PASSWORD}}")
echo "tokens: user=${#USER_TOKEN} admin=${#ADMIN} school=${#SCHOOL_ADMIN}"

echo
echo "== (1) 鉴权 ========================================================="
check "匿名访问履约单列表 -> 401" 401 "$(req GET '/api/admin/jobs/contracts?jobId=900011')"
check "普通用户访问履约单列表 -> 401" 401 "$(req GET '/api/admin/jobs/contracts?jobId=900011' "$USER_TOKEN")"

printf '{"enabled":0}' > "$TMP/flag.json"
printf '{"realNameStatus":"verified","studentStatus":"verified","reason":"越权测试"}' > "$TMP/verify.json"
check "校管修改全平台闸门 -> 403" 403 "$(put '/api/admin/feature-flags/job.offsite.enabled' "$SCHOOL_ADMIN" "$TMP/flag.json")"
check "超管修改全平台闸门 -> 200" 200 "$(put '/api/admin/feature-flags/job.offsite.enabled' "$ADMIN" "$TMP/flag.json")"
check "校管跨校核验用户 -> 403" 403 "$(post '/api/admin/verifications/5/review' "$SCHOOL_ADMIN" "$TMP/verify.json")"

echo
echo "== (2) 超管列表 + 快照字段 =========================================="
check "超管按岗位筛选 -> 200" 200 "$(req GET '/api/admin/jobs/contracts?jobId=900011' "$ADMIN")"
check "只返回该岗位 1 条" 1 "$(grep -o '"total":1' "$TMP/last.json" | wc -l | tr -d ' ')"
check "状态是 disputed" 1 "$(grep -o '"status":"disputed"' "$TMP/last.json" | wc -l | tr -d ' ')"
check "带出争议单号" 1 "$(grep -o '"disputeId":900201' "$TMP/last.json" | wc -l | tr -d ' ')"
check "带出承接方工时 3.00" 1 "$(grep -o '"workerHours":3.00' "$TMP/last.json" | wc -l | tr -d ' ')"
check "带出费率模式快照 rate" 1 "$(grep -o '"feeModeSnapshot":"rate"' "$TMP/last.json" | wc -l | tr -d ' ')"
check "带出费率数值快照 500" 1 "$(grep -o '"feeValueSnapshot":500' "$TMP/last.json" | wc -l | tr -d ' ')"
check "带出报酬快照 2000/hour" 1 "$(grep -o '"payCentsSnapshot":2000' "$TMP/last.json" | wc -l | tr -d ' ')"

echo
echo "== (3) 状态过滤白名单 ==============================================="
check "status=disputed -> 200" 200 "$(req GET '/api/admin/jobs/contracts?jobId=900011&status=disputed' "$ADMIN")"
check "status=settled 同岗位 -> 0 条" 0 "$(grep -o '"total":0' "$TMP/last.json" | wc -l | tr -d ' ')"
check "非法状态 -> 400" 400 "$(req GET '/api/admin/jobs/contracts?status=DROP%20TABLE' "$ADMIN")"

echo
echo "== (4) 校管数据范围 ================================================="
check "校管看本校履约单 -> 200" 200 "$(req GET '/api/admin/jobs/contracts?jobId=900011' "$SCHOOL_ADMIN")"
check "本校岗位命中 1 条" 1 "$(grep -o '"total":1' "$TMP/last.json" | wc -l | tr -d ' ')"
check "校管看外校岗位 -> 200（列表按范围过滤）" 200 "$(req GET '/api/admin/jobs/contracts?jobId=900012' "$SCHOOL_ADMIN")"
check "外校岗位命中 0 条" 1 "$(grep -o '"total":0' "$TMP/last.json" | wc -l | tr -d ' ')"
check "校管看外校履约单详情 -> 403" 403 "$(req GET '/api/admin/jobs/contracts/900102' "$SCHOOL_ADMIN")"

echo
echo "== (5) 详情 + 裁定 + 审计 + 幂等 ===================================="
check "超管看外校履约单详情 -> 200" 200 "$(req GET '/api/admin/jobs/contracts/900102' "$ADMIN")"
check "校管看本校履约单详情 -> 200" 200 "$(req GET '/api/admin/jobs/contracts/900101' "$SCHOOL_ADMIN")"
check "详情带 3 条账单" 3 "$(grep -o '"bizNo"' "$TMP/last.json" | wc -l | tr -d ' ')"
check "详情带结算估算" 1 "$(grep -o '"payableCents"' "$TMP/last.json" | wc -l | tr -d ' ')"

printf '{"decision":"settled","reason":"漏填数量必须被拒绝"}' > "$TMP/arb-no-hours.json"
check "settled 裁定漏填数量 -> 400" 400 "$(post '/api/admin/jobs/contracts/900101/arbitrate' "$SCHOOL_ADMIN" "$TMP/arb-no-hours.json")"
printf '{"decision":"settled","hours":3,"reason":"双方工时核对后按承接方 3 小时结算"}' > "$TMP/arb.json"
check "校管裁定本校争议 -> 200" 200 "$(post '/api/admin/jobs/contracts/900101/arbitrate' "$SCHOOL_ADMIN" "$TMP/arb.json")"
check "履约单进入 settled" settled "$(q "SELECT status FROM job_contract WHERE id=900101")"
check "3 条账单全部解冻为 settled" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=900101 AND status='settled'")"
check "争议标记 resolved" resolved "$(q "SELECT status FROM job_dispute WHERE id=900201")"
check "裁定写审计日志" 1 "$(q "SELECT COUNT(*) FROM admin_log WHERE action='job_arbitrate' AND target_id='900101'")"
check "重复裁定 -> 200（幂等，不重复记账）" 200 "$(post '/api/admin/jobs/contracts/900101/arbitrate' "$SCHOOL_ADMIN" "$TMP/arb.json")"
check "账单仍为 3 条，没有新增" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=900101")"
check "裁定日志仍只有 1 条" 1 "$(q "SELECT COUNT(*) FROM admin_log WHERE action='job_arbitrate' AND target_id='900101'")"
printf '{"decision":"settled","hours":5,"reason":"换数量重提必须被拒"}' > "$TMP/arb-change.json"
check "已裁定后换数量重提 -> 409（不静默忽略）" 409 "$(post '/api/admin/jobs/contracts/900101/arbitrate' "$SCHOOL_ADMIN" "$TMP/arb-change.json")"
check "约定数量仍是 3.00" 3.00 "$(q "SELECT agreed_hours FROM job_contract WHERE id=900101")"

echo
echo "== (5b) 多名额校外岗位：有人开工后剩余名额仍可招募 =================="
check "名额 2 / 已录 1，合同开工 -> 200" 200 "$(post '/api/jobs/contracts/900104/start' "$USER_TOKEN")"
check "合同进入 working" working "$(q "SELECT status FROM job_contract WHERE id=900104")"
check "岗位仍处于 recruiting（剩余名额可报名）" recruiting "$(q "SELECT workflow_status FROM job WHERE id=900014")"
check "已录 1 / 名额 2" 1 "$(q "SELECT accepted_count FROM job WHERE id=900014")"
printf '{"reason":"E2E 开工后取消不释放名额"}' > "$TMP/cancel-started.json"
check "开工后取消履约单 -> 200" 200 "$(post '/api/jobs/contracts/900104/cancel' "$USER_TOKEN" "$TMP/cancel-started.json")"
check "开工后取消不释放名额（已履约，仍占 1）" 1 "$(q "SELECT accepted_count FROM job WHERE id=900014")"

echo
echo "== (5c) 开工前取消释放名额 =========================================="
check "已录 1 / 名额 2（岗位 900015）" 1 "$(q "SELECT accepted_count FROM job WHERE id=900015")"
printf '{"reason":"E2E 开工前取消，应释放名额"}' > "$TMP/cancel.json"
check "开工前取消履约单 -> 200" 200 "$(post '/api/jobs/contracts/900105/cancel' "$USER_TOKEN" "$TMP/cancel.json")"
check "合同进入 cancelled" cancelled "$(q "SELECT status FROM job_contract WHERE id=900105")"
check "名额释放回 0" 0 "$(q "SELECT accepted_count FROM job WHERE id=900015")"
check "岗位仍可招募" recruiting "$(q "SELECT workflow_status FROM job WHERE id=900015")"

echo
echo "== (6) cleanup ========================================================"
cat > "$TMP/cleanup.sql" <<'SQL'
USE campus_life;
DELETE FROM admin_log WHERE action = 'job_arbitrate' AND target_id IN ('900101','900102');
DELETE FROM earnings_ledger WHERE biz_type='job' AND ref_id IN (900101,900102,900103,900104,900105);
DELETE FROM job_dispute WHERE contract_id IN (900101,900102,900103,900104,900105);
DELETE FROM job_contract WHERE id IN (900101,900102,900103,900104,900105);
DELETE FROM job WHERE id IN (900011,900012,900013,900014,900015);
SQL
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_PASS" < "$TMP/cleanup.sql" 2>/dev/null

echo
echo "=============================================================="
echo "  RESULT: ${PASS} passed, ${FAIL} failed"
echo "=============================================================="
rm -rf "$TMP"
[ "$FAIL" -eq 0 ]
