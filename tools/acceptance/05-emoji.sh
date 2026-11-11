#!/usr/bin/env bash
# 圈子和私聊 emoji 验收（方案 §8.2 / §11.11）
# 逻辑在 05-emoji.mjs：Node 24 自带 fetch / WebSocket，不需要额外依赖。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
exec node "$DIR/05-emoji.mjs"
