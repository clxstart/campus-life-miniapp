#!/usr/bin/env bash
# 生产上线前只读检查。不会改数据库、不会启动服务。
#
# 用法：
#   设置 .env.production 中的变量后执行：
#   bash tools/acceptance/production-config-check.sh
#
# 数据库检查需要 mysql 客户端：
#   MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... MYSQL_DATABASE=campus_life \
#     bash tools/acceptance/production-config-check.sh
set -u
set -o pipefail
FAIL=0

check() {
  local name="$1" value="$2" expected="$3"
  if [ "$value" = "$expected" ]; then
    echo "PASS $name"
  else
    echo "FAIL $name（期望 [$expected]，实际 [$value]）"
    FAIL=$((FAIL + 1))
  fi
}

val() { printf '%s' "${1:-}"; }

echo "== 小程序登录 / 安全开关 =="
case "$(val "${CAMPUS_WX_APPID:-}")" in
  ''|touristappid) check "CAMPUS_WX_APPID 是正式 AppID" no yes ;;
  *) check "CAMPUS_WX_APPID 是正式 AppID" yes yes ;;
esac
[ -n "${CAMPUS_WX_SECRET:-}" ] && check "CAMPUS_WX_SECRET 已配置" yes yes || check "CAMPUS_WX_SECRET 已配置" no yes
JWT_LEN=${#CAMPUS_JWT_SECRET}
if [ "$JWT_LEN" -ge 32 ]; then
  check "CAMPUS_JWT_SECRET 长度 >= 32" yes yes
else
  check "CAMPUS_JWT_SECRET 长度 >= 32" no yes
fi
check "CAMPUS_AUTH_ENFORCE" "$(val "${CAMPUS_AUTH_ENFORCE:-}")" true
check "CAMPUS_DEV_LOGIN_ENABLED" "$(val "${CAMPUS_DEV_LOGIN_ENABLED:-}")" false
check "CAMPUS_DEV_LOGIN_ANY_USER" "$(val "${CAMPUS_DEV_LOGIN_ANY_USER:-}")" false
check "CAMPUS_PAY_SIMULATE" "$(val "${CAMPUS_PAY_SIMULATE:-}")" false

echo "== HTTPS 域名 =="
for key in CAMPUS_WEB_ORIGINS CAMPUS_UPLOAD_BASE_URL; do
  value="$(val "${!key:-}")"
  case "$value" in
    https://localhost*|https://127.0.0.1*|*your-domain.example*|*example.com*|'')
      check "$key 是真实 HTTPS 域名" no yes ;;
    https://*)
      check "$key 是真实 HTTPS 域名" yes yes ;;
    *)
      check "$key 是真实 HTTPS 域名" no yes ;;
  esac
done

echo "== 部署形态 =="
case "$(val "${CAMPUS_HA_MODE:-}")" in
  single) check "CAMPUS_HA_MODE 已显式声明" yes yes ;;
  cluster)
    check "CAMPUS_HA_MODE 已显式声明" yes yes
    [ -n "${CAMPUS_HA_REDIS_URL:-}" ] && check "cluster: Redis 已配置" yes yes || check "cluster: Redis 已配置" no yes
    [ -n "${CAMPUS_HA_STORAGE_TYPE:-}" ] && check "cluster: 对象存储类型已配置" yes yes || check "cluster: 对象存储类型已配置" no yes
    ;;
  *) check "CAMPUS_HA_MODE 已显式声明" no yes ;;
esac

echo "== 微信支付 =="
if [ "${WXPAY_ENABLED:-false}" = true ]; then
  for key in WXPAY_APP_ID WXPAY_MCH_ID WXPAY_API_V3_KEY WXPAY_CERT_SERIAL_NO WXPAY_PRIVATE_KEY_PATH WXPAY_NOTIFY_URL WXPAY_REFUND_NOTIFY_URL; do
    [ -n "${!key:-}" ] && check "$key 已配置" yes yes || check "$key 已配置" no yes
  done
  check "WXPAY_APP_ID 与小程序一致" "$(val "${WXPAY_APP_ID:-}")" "$(val "${CAMPUS_WX_APPID:-}")"
else
  echo "SKIP 微信支付未启用（WXPAY_ENABLED != true）"
fi

if [ -n "${MYSQL_HOST:-}" ]; then
  echo "== 数据库账号闸门（只读）=="
  mysql_cmd=(mysql --default-character-set=utf8mb4 -h "$MYSQL_HOST" -u "${MYSQL_USER:-campus_user}" -p"${MYSQL_PASSWORD:-}" -N -B "${MYSQL_DATABASE:-campus_life}")
  leaked="$("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM admin_user WHERE status=1 AND password_hash IN ('\$2a\$10\$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6','\$2a\$10\$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK','\$2a\$10\$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6','\$2a\$10\$ubOWq/4fPhG11dtWoRVvvOKuZ.672R.y/uCjKYwX0PiFejdx0iHhe');" 2>/dev/null)"
  check "没有启用中的已泄露演示密码账号" "${leaked:-unknown}" 0
  stale="$("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM admin_user WHERE status=1 AND role IN ('merchant','rider') AND credential_rotated_at IS NULL;" 2>/dev/null)"
  check "商家/骑手初始凭据已全部轮换" "${stale:-unknown}" 0
else
  echo "SKIP 数据库检查（未设置 MYSQL_HOST）"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "生产配置静态检查通过；仍必须实际执行 prod profile 启动验证与真实微信/支付联调。"
else
  echo "生产配置检查失败：$FAIL 项"
  exit 1
fi