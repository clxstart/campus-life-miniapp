#!/bin/bash
# 串行化 mvn 调用：同一个 backend/target 目录不能被多个 mvn 进程同时写。
# 用法：bash tools/mvn-lock.sh -o test -Dtest=XxxTest
# 锁目录 20 分钟未释放视为残留（进程被硬杀），自动清理。
set -u
LOCK=/tmp/campus-mvn.lock
WAITED=0
while ! mkdir "$LOCK" 2>/dev/null; do
  if [ -d "$LOCK" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +20 2>/dev/null)" ]; then
    rmdir "$LOCK" 2>/dev/null && continue
  fi
  sleep 3
  WAITED=$((WAITED + 3))
  if [ "$WAITED" -gt 900 ]; then
    echo "mvn-lock: 等待锁超过 15 分钟，放弃" >&2
    exit 1
  fi
done
cleanup() { rmdir "$LOCK" 2>/dev/null; }
trap cleanup EXIT
cd "$(dirname "$0")/../backend" || exit 1
mvn "$@"