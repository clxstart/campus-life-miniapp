#!/bin/bash
# 校园帮功能 · 后端验收套件统一入口
#
# 前置：
#   1. 后端跑在 127.0.0.1:8080
#   2. 已执行 db/ 下 6 个新迁移（见 README_BACKEND.md）
#   3. 后端启动时带 CAMPUS_DEV_LOGIN_ANY_USER=true（脚本要登录非演示账号做并发/越权测试）
#      生产 profile 下该开关无效，这些脚本只用于验收环境
#
# 为什么要串行 + 间隔：
#   登录接口限流是「同 IP 每分钟 10 次」，发布接口是「同用户每分钟 10 次」。
#   连着跑会撞 429，脚本里的 token 会是空串，测出来的失败全是假的。
#   每个脚本之间等一个干净窗口，保证结果可复现。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
GAP=${GAP:-62}

total_pass=0
total_fail=0
failed_scripts=()

run_one() {
  local file="$1"
  local name="$2"
  echo ""
  echo "=============================================================="
  echo "  $name  ($file)"
  echo "=============================================================="
  bash "$DIR/$file"
  local code=$?
  if [ $code -eq 0 ]; then
    echo "--- $name: 通过 ---"
  else
    echo "--- $name: 失败 (exit $code) ---"
    failed_scripts+=("$name")
  fi
  return $code
}

echo "等待首个干净限流窗口（${GAP}s）…"
sleep "$GAP"

run_one 01-circle-streak.sh "圈子连续火花" ; sleep "$GAP"
run_one 02-dispatch.sh     "配送楼栋派单" ; sleep "$GAP"
run_one 03-jobs-marketplace.sh "兼职岗位域" ; sleep "$GAP"
run_one 04-jobs-offsite.sh "校外报名录用"

echo ""
echo "=============================================================="
if [ ${#failed_scripts[@]} -eq 0 ]; then
  echo "  验收结果：4 个套件全部通过"
  echo "=============================================================="
  exit 0
else
  echo "  验收结果：以下套件失败 -> ${failed_scripts[*]}"
  echo "=============================================================="
  exit 1
fi
