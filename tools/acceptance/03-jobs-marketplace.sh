#!/usr/bin/env bash
# ==========================================================================
# Jobs marketplace acceptance script (§11.1 concurrency + §11.3 billing)
#
# What it does:
#   * seeds fixture jobs with fixed ids 900001..900008 (reset at every run)
#   * exercises the user API + admin API over HTTP (real server on :8080)
#   * asserts DB state with mysql after every scenario
#
# Prerequisites:
#   * backend running on 127.0.0.1:8080 with CAMPUS_DEV_LOGIN_ANY_USER=true
#     (needed to dev-login as user 2/3/5; user 1 is the demo account)
#   * MySQL root password chang090011, schema campus_life
#
# Notes:
#   * publish is rate limited to 10/min per user. This script publishes 3 times
#     as user 1 per run, so do not run it more than 3 times inside one minute.
#   * all fixture titles are ASCII so nothing depends on the shell codepage.
# ==========================================================================
set -u

BASE=http://127.0.0.1:8080
MYSQL_PASS=chang090011
TMP=$(mktemp -d)
PASS=0
FAIL=0

q() { mysql -uroot -p"$MYSQL_PASS" -N -B -e "USE campus_life; $1" 2>/dev/null; }

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1)); echo "  PASS  $1  [$2]"
  else
    FAIL=$((FAIL + 1)); echo "  FAIL  $1  (expected [$2], got [$3])"
  fi
}

# req METHOD PATH TOKEN [PAYLOAD_FILE] -> prints HTTP code, body in $TMP/last.json
req() {
  local method=$1 path=$2 token=${3:-} data=${4:-}
  local args=(-s -o "$TMP/last.json" -w "%{http_code}" -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$data")
  curl "${args[@]}"
}

payload() { # payload FILE JSON  (write JSON to a file, never inline in bash)
  printf '%s' "$2" > "$TMP/$1"
  echo "$TMP/$1"
}

login() { # login USER_ID -> prints token (retries through the 10/min dev-login limit)
  local uid=$1 attempt code
  for attempt in 1 2 3 4; do
    code=$(req POST /api/auth/dev-login "" "$(payload "login-$uid.json" "{\"userId\":$uid}")")
    if [ "$code" = 200 ]; then
      grep -o '"token":"[^"]*"' "$TMP/last.json" | head -1 | cut -d'"' -f4
      return 0
    fi
    if [ "$code" = 429 ]; then
      echo "  (dev-login for user $uid hit the rate limit, waiting 61s)" >&2
      sleep 61
    else
      echo "  (dev-login for user $uid failed with $code, retrying)" >&2
      sleep 2
    fi
  done
  return 1
}

echo "== (0) fixtures ======================================================"
cat > "$TMP/fixtures.sql" <<'SQL'
USE campus_life;
DELETE d FROM job_dispute d JOIN job_contract c ON c.id = d.contract_id
  WHERE c.job_id BETWEEN 900001 AND 900010;
DELETE FROM earnings_ledger
  WHERE biz_type = 'job'
    AND ref_id IN (SELECT id FROM job_contract WHERE job_id BETWEEN 900001 AND 900010);
DELETE FROM job_contract WHERE job_id BETWEEN 900001 AND 900010;
DELETE FROM job_application WHERE job_id BETWEEN 900001 AND 900010;
DELETE FROM job WHERE id BETWEEN 900001 AND 900010;
DELETE FROM job WHERE title LIKE 'E2E offsite%' OR title LIKE 'E2E takeover%';

INSERT INTO user_verification(user_id, real_name_status, student_status, provider, reject_reason, verified_at, updated_at)
VALUES (3, 'verified', 'verified', 'manual_admin', '', NOW(), NOW())
ON DUPLICATE KEY UPDATE real_name_status='verified', student_status='verified', verified_at=NOW();
DELETE FROM user_verification WHERE user_id = 2;

INSERT INTO job(id,title,day_salary,settle,location,time_desc,status,user_id,description,school_id,
                audit_status,audit_reason,audit_at,`scope`,employer_kind,take_mode,category_key,
                pay_cents,pay_unit,fee_mode,fee_value,headcount,deadline_at,workflow_status,taken_by,accepted_count)
VALUES
 (900001,'E2E campus race',0,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',0,'day','none',0,1,NULL,'open',NULL,0),
 (900004,'E2E paid unverified',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','none',0,1,NULL,'open',NULL,0),
 (900005,'E2E fee snapshot',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','rate',500,1,NULL,'open',NULL,0),
 (900006,'E2E hours mismatch',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','none',0,1,NULL,'open',NULL,0),
 (900007,'E2E cancel before start',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','none',0,1,NULL,'open',NULL,0),
 (900008,'E2E settle idempotent',2000,'daily','Gym','night','open',5,'','1','passed','',NOW(),
  'campus','student','first_come','other',2000,'hour','rate',500,1,NULL,'open',NULL,0),
 (900009,'E2E pending audit',0,'daily','Gym','night','open',5,'','1','pending','',NULL,
  'campus','student','first_come','other',0,'day','none',0,1,NULL,'cancelled',NULL,0),
 (900010,'E2E other school pending',0,'daily','Gym','night','open',5,'','2','pending','',NULL,
  'campus','student','first_come','other',0,'day','none',0,1,NULL,'cancelled',NULL,0);
SQL
mysql -uroot -p"$MYSQL_PASS" < "$TMP/fixtures.sql" 2>/dev/null
echo "fixtures ready: $(q "SELECT COUNT(*) FROM job WHERE id BETWEEN 900001 AND 900010") jobs"

TOKEN1=$(login 1) || { echo "FATAL: dev-login failed for user 1"; exit 2; }
TOKEN2=$(login 2) || { echo "FATAL: dev-login failed for user 2"; exit 2; }
TOKEN3=$(login 3) || { echo "FATAL: dev-login failed for user 3"; exit 2; }
TOKEN5=$(login 5) || { echo "FATAL: dev-login failed for user 5"; exit 2; }
ADMIN=$(curl -s -X POST "$BASE/api/admin/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
SCHOOL_ADMIN=$(curl -s -X POST "$BASE/api/admin/login" -H 'Content-Type: application/json' \
  -d '{"username":"neimenggu","password":"school123"}' | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TOKEN1" ] && [ -n "$ADMIN" ] || { echo "FATAL: login failed"; exit 1; }
flag() { # flag KEY 0|1
  local v=false; [ "$2" = 1 ] && v=true
  req PUT "/api/admin/feature-flags/$1" "$ADMIN" "$(payload "flag.json" "{\"enabled\":$v,\"remark\":\"e2e\"}")" > /dev/null
}
flag job.offsite.enabled 0
flag job.paid.enabled 0
flag job.category.takeover_class.enabled 0

echo
echo "== (1) concurrent take on one campus job ============================="
curl -s -o "$TMP/race1.json" -w "%{http_code}" -X POST "$BASE/api/jobs/900001/take" \
  -H "Authorization: Bearer $TOKEN1" > "$TMP/race1.code" &
P1=$!
curl -s -o "$TMP/race2.json" -w "%{http_code}" -X POST "$BASE/api/jobs/900001/take" \
  -H "Authorization: Bearer $TOKEN2" > "$TMP/race2.code" &
P2=$!
wait $P1 $P2
C1=$(cat "$TMP/race1.code"); C2=$(cat "$TMP/race2.code")
N200=0; N409=0
[ "$C1" = 200 ] && N200=$((N200 + 1)); [ "$C1" = 409 ] && N409=$((N409 + 1))
[ "$C2" = 200 ] && N200=$((N200 + 1)); [ "$C2" = 409 ] && N409=$((N409 + 1))
echo "  user1 -> $C1, user2 -> $C2"
check "race: exactly one 200" 1 "$N200"
check "race: exactly one 409" 1 "$N409"
check "race: exactly one contract row" 1 "$(q "SELECT COUNT(*) FROM job_contract WHERE job_id=900001")"
check "race: job taken by winner" 1 "$(q "SELECT COUNT(*) FROM job WHERE id=900001 AND taken_by IS NOT NULL AND workflow_status='taken'")"
check "race: no application rows" 0 "$(q "SELECT COUNT(*) FROM job_application WHERE job_id=900001")"

echo
echo "== (2) offsite publish gate =========================================="
OFF_BODY=$(payload offsite.json '{"title":"E2E offsite gate","scope":"offsite","payCents":0,"takeMode":"apply","categoryKey":"other","description":"gate test"}')
check "offsite publish while flag off -> 403" 403 "$(req POST /api/jobs "$TOKEN1" "$OFF_BODY")"
flag job.offsite.enabled 1
CODE=$(req POST /api/jobs "$TOKEN1" "$OFF_BODY")
check "offsite publish while flag on -> 200" 200 "$CODE"
OFF_JOB=$(grep -o '"id":[0-9]*' "$TMP/last.json" | head -1 | cut -d: -f2)
check "offsite job scope" offsite "$(q "SELECT \`scope\` FROM job WHERE id=$OFF_JOB")"
APP1=$(payload apply1.json '{"message":"I can do it"}')
check "offsite apply -> 200" 200 "$(req POST "/api/jobs/$OFF_JOB/apply" "$TOKEN2" "$APP1")"
check "offsite apply duplicate -> 200 (idempotent)" 200 "$(req POST "/api/jobs/$OFF_JOB/apply" "$TOKEN2" "$APP1")"
check "offsite apply duplicate alreadyApplied" true "$(grep -o '"alreadyApplied":[a-z]*' "$TMP/last.json" | cut -d: -f2)"
check "offsite apply keeps one row" 1 "$(q "SELECT COUNT(*) FROM job_application WHERE job_id=$OFF_JOB")"
flag job.offsite.enabled 0

echo
echo "== (3) takeover_class gate (default off) ============================="
TAKE_BODY=$(payload takeover.json '{"title":"E2E takeover gate","scope":"campus","payCents":0,"categoryKey":"takeover_class","description":"gate test"}')
check "takeover_class publish -> 403" 403 "$(req POST /api/jobs "$TOKEN1" "$TAKE_BODY")"

echo
echo "== (4) paid gate + verification ======================================"
flag job.paid.enabled 1
check "unverified user take paid job -> 403" 403 "$(req POST /api/jobs/900004/take "$TOKEN2")"
flag job.paid.enabled 0
check "paid flag off -> take paid job 403" 403 "$(req POST /api/jobs/900004/take "$TOKEN1")"
flag job.paid.enabled 1

echo
echo "== (5) fee snapshot on contract ======================================"
check "verified worker takes paid job -> 200" 200 "$(req POST /api/jobs/900005/take "$TOKEN3")"
C5=$(q "SELECT id FROM job_contract WHERE job_id=900005")
check "snapshot fee_mode" rate "$(q "SELECT fee_mode_snapshot FROM job_contract WHERE id=$C5")"
check "snapshot fee_value" 500 "$(q "SELECT fee_value_snapshot FROM job_contract WHERE id=$C5")"
check "snapshot pay_cents" 2000 "$(q "SELECT pay_cents_snapshot FROM job_contract WHERE id=$C5")"
FEE_BODY=$(payload fee.json '{"feeMode":"amount","feeValue":999}')
check "admin sets job fee -> 200" 200 "$(req POST /api/admin/jobs/900005/fee "$ADMIN" "$FEE_BODY")"
check "job fee updated" amount "$(q "SELECT fee_mode FROM job WHERE id=900005")"
check "contract fee_mode snapshot unchanged" rate "$(q "SELECT fee_mode_snapshot FROM job_contract WHERE id=$C5")"
check "contract fee_value snapshot unchanged" 500 "$(q "SELECT fee_value_snapshot FROM job_contract WHERE id=$C5")"

echo
echo "== (6) mismatched hours -> disputed, ledger frozen ==================="
check "worker takes job 900006 -> 200" 200 "$(req POST /api/jobs/900006/take "$TOKEN3")"
C6=$(q "SELECT id FROM job_contract WHERE job_id=900006")
check "start -> 200" 200 "$(req POST "/api/jobs/contracts/$C6/start" "$TOKEN3")"
H3=$(payload h3.json '{"hours":3}')
H5=$(payload h5.json '{"hours":5}')
check "worker submits 3h -> 200" 200 "$(req POST "/api/jobs/contracts/$C6/submit-hours" "$TOKEN3" "$H3")"
check "employer confirms 5h -> 200" 200 "$(req POST "/api/jobs/contracts/$C6/confirm-hours" "$TOKEN5" "$H5")"
check "contract status disputed" disputed "$(q "SELECT status FROM job_contract WHERE id=$C6")"
check "job status disputed" disputed "$(q "SELECT workflow_status FROM job WHERE id=900006")"
check "ledger rows" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C6")"
check "ledger rows frozen" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C6 AND status='frozen'")"
check "no settled income while disputed" 0 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C6 AND entry_type='job_income' AND status='settled'")"

echo
echo "== (6b) admin arbitration on the frozen contract ====================="
ARB=$(payload arb.json '{"decision":"settled","reason":"worker evidence"}')
check "admin arbitrate disputed contract -> 200" 200 "$(req POST "/api/admin/jobs/contracts/$C6/arbitrate" "$ADMIN" "$ARB")"
check "contract settled by arbitration" settled "$(q "SELECT status FROM job_contract WHERE id=$C6")"
check "job settled by arbitration" settled "$(q "SELECT workflow_status FROM job WHERE id=900006")"
check "frozen ledger switched to settled" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C6 AND status='settled'")"
check "dispute row resolved" resolved "$(q "SELECT status FROM job_dispute WHERE contract_id=$C6")"
check "arbitration audit written" 1 "$(q "SELECT COUNT(*) FROM admin_log WHERE action='job_arbitrate' AND target_id='$C6'")"

echo
echo "== (7) cancel before start -> ledger refunded ========================"
check "worker takes job 900007 -> 200" 200 "$(req POST /api/jobs/900007/take "$TOKEN3")"
C7=$(q "SELECT id FROM job_contract WHERE job_id=900007")
CANCEL=$(payload cancel.json '{"reason":"schedule conflict"}')
check "cancel before start -> 200" 200 "$(req POST "/api/jobs/contracts/$C7/cancel" "$TOKEN3" "$CANCEL")"
check "contract cancelled" cancelled "$(q "SELECT status FROM job_contract WHERE id=$C7")"
check "job cancelled" cancelled "$(q "SELECT workflow_status FROM job WHERE id=900007")"
check "ledger rows" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C7")"
check "ledger rows refunded" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C7 AND status='refunded'")"

echo
echo "== (8) settlement + idempotency ======================================"
check "worker takes job 900008 -> 200" 200 "$(req POST /api/jobs/900008/take "$TOKEN3")"
C8=$(q "SELECT id FROM job_contract WHERE job_id=900008")
check "start -> 200" 200 "$(req POST "/api/jobs/contracts/$C8/start" "$TOKEN3")"
check "worker submits 3h -> 200" 200 "$(req POST "/api/jobs/contracts/$C8/submit-hours" "$TOKEN3" "$H3")"
check "employer confirms 3h -> 200" 200 "$(req POST "/api/jobs/contracts/$C8/confirm-hours" "$TOKEN5" "$H3")"
check "contract settled" settled "$(q "SELECT status FROM job_contract WHERE id=$C8")"
check "agreed_hours = 3.00" "3.00" "$(q "SELECT agreed_hours FROM job_contract WHERE id=$C8")"
check "job settled" settled "$(q "SELECT workflow_status FROM job WHERE id=900008")"
check "ledger rows" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C8")"
check "payable = 6000 cents" 6000 "$(q "SELECT amount_cents FROM earnings_ledger WHERE biz_no='job:$C8:payable'")"
check "fee = 300 cents (5% of 6000)" 300 "$(q "SELECT amount_cents FROM earnings_ledger WHERE biz_no='job:$C8:fee'")"
check "income = 5700 cents" 5700 "$(q "SELECT amount_cents FROM earnings_ledger WHERE biz_no='job:$C8:income'")"
check "all three settled" 3 "$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C8 AND status='settled'")"
BEFORE=$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C8")
check "repeat confirm is accepted (idempotent)" 200 "$(req POST "/api/jobs/contracts/$C8/confirm-hours" "$TOKEN5" "$H3")"
AFTER=$(q "SELECT COUNT(*) FROM earnings_ledger WHERE biz_type='job' AND ref_id=$C8")
check "repeat confirm does not add ledger rows" "$BEFORE" "$AFTER"

echo
echo "== (9) admin review queue + school scoping =========================="
check "admin pending list -> 200" 200 "$(req GET '/api/admin/jobs?pending=true&page=1&size=50' "$ADMIN")"
check "pending fixture listed" 1 "$(grep -o '"id":900009' "$TMP/last.json" | wc -l | tr -d ' ')"
APPROVE=$(payload approve.json '{"approve":true,"reason":"checked"}')
check "school admin approves own-school pending job -> 200" 200 "$(req POST /api/admin/jobs/900009/review "$SCHOOL_ADMIN" "$APPROVE")"
check "approved job audit_status passed" passed "$(q "SELECT audit_status FROM job WHERE id=900009")"
check "approved job back to open" open "$(q "SELECT workflow_status FROM job WHERE id=900009")"
REJECT=$(payload reject.json '{"approve":false,"reason":"not allowed"}')
check "school admin cannot review other-school job -> 403" 403 "$(req POST /api/admin/jobs/900010/review "$SCHOOL_ADMIN" "$REJECT")"

echo
echo "== cleanup ==========================================================="
flag job.offsite.enabled 0
flag job.paid.enabled 0
flag job.category.takeover_class.enabled 0
mysql -uroot -p"$MYSQL_PASS" -e "USE campus_life; DELETE FROM job WHERE title LIKE 'E2E offsite%' OR title LIKE 'E2E takeover%';" 2>/dev/null
# Neutralize fixtures so dev listings stay clean; the next run recreates them.
cat > "$TMP/cleanup.sql" <<'SQL'
USE campus_life;
DELETE d FROM job_dispute d JOIN job_contract c ON c.id = d.contract_id
  WHERE c.job_id BETWEEN 900001 AND 900010;
DELETE FROM earnings_ledger
  WHERE biz_type = 'job'
    AND ref_id IN (SELECT id FROM job_contract WHERE job_id BETWEEN 900001 AND 900010);
DELETE FROM job_contract WHERE job_id BETWEEN 900001 AND 900010;
DELETE FROM job_application WHERE job_id BETWEEN 900001 AND 900010;
DELETE FROM job WHERE id BETWEEN 900001 AND 900010;
DELETE FROM user_verification WHERE user_id = 3;
SQL
mysql -uroot -p"$MYSQL_PASS" < "$TMP/cleanup.sql" 2>/dev/null
echo "flags restored: offsite=$(q "SELECT enabled FROM feature_flag WHERE flag_key='job.offsite.enabled'") paid=$(q "SELECT enabled FROM feature_flag WHERE flag_key='job.paid.enabled'") takeover=$(q "SELECT enabled FROM feature_flag WHERE flag_key='job.category.takeover_class.enabled'")"

echo
echo "=============================================================="
echo "  RESULT: ${PASS} passed, ${FAIL} failed"
echo "=============================================================="
rm -rf "$TMP"
[ "$FAIL" -eq 0 ]
