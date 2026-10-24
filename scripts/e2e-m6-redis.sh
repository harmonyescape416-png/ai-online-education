#!/bin/bash
# M6 Redis 故障降级验收
#   做法：单独把 aigc 服务指向一个无人监听的 Redis 端口（网关/认证的 Redis 保持正常），
#         验证 aigc 在无 Redis 时仍能对话，但接口必须显式标记 persisted=false；
#         恢复后重新可用。禁止把临时内容冒充持久会话。
# 用法: bash scripts/e2e-m6-redis.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
source scripts/dev-env.sh >/dev/null 2>&1
ROOT="$HOME/CodeBuddy/20261018213055"
G=http://127.0.0.1:18080
R="$HOME/opt/tools/redis/bin/redis-cli"
PASS=0; FAIL=0

chk() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}

echo "================ M6 Redis 降级验收 ================"

S=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"student01","password":"student123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
SH="Authorization: Bearer $S"

echo "--- 1. 基线：Redis 正常 ---"
SID=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"基线消息\"}" $G/chat --max-time 20 > /tmp/kzai_m6_base.sse
chk "基线 SSE 有 1001 增量" yes "$(grep -q '^event: 1001' /tmp/kzai_m6_base.sse && echo yes || echo no)"
chk "基线终态 persisted=true" 1 "$(grep '^data:.*1002' /tmp/kzai_m6_base.sse | grep -c 'persisted\":true')"

echo "--- 2. 以失效 Redis 地址重启 aigc（网关 Redis 保持正常）---"
pid=$(lsof -nP -iTCP:18086 -sTCP:LISTEN -t 2>/dev/null | head -1)
[ -n "$pid" ] && kill "$pid" 2>/dev/null
sleep 3
nohup java -jar "$ROOT/kzai-aigc/target/kzai-aigc-1.0.0-SNAPSHOT.jar" \
  --server.port=18086 --spring.data.redis.port=6399 > "$ROOT/logs/kzai-aigc.log" 2>&1 &
sleep 12
grep -q "Started KzaiAigcApplication" "$ROOT/logs/kzai-aigc.log" && echo "  [INFO] aigc 已以失效 Redis 启动" || echo "  [INFO] aigc 启动中"

curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"Redis 不可用时的提问\"}" $G/chat --max-time 25 > /tmp/kzai_m6_deg.sse
echo "  [INFO] 降级 SSE 帧数=$(grep -c '^event:' /tmp/kzai_m6_deg.sse)"
grep '^data:.*1002' /tmp/kzai_m6_deg.sse | head -1
if grep -q '"persisted":false' /tmp/kzai_m6_deg.sse; then
  PASS=$((PASS+1)); printf "  [PASS] 降级时终态显式标记 persisted=false\n"
else
  FAIL=$((FAIL+1)); printf "  [FAIL] 降级时未标记 persisted=false\n"
fi

echo "--- 恢复：以正常 Redis 重启 aigc ---"
pid=$(lsof -nP -iTCP:18086 -sTCP:LISTEN -t 2>/dev/null | head -1)
[ -n "$pid" ] && kill "$pid" 2>/dev/null
sleep 3
nohup java -jar "$ROOT/kzai-aigc/target/kzai-aigc-1.0.0-SNAPSHOT.jar" \
  --server.port=18086 > "$ROOT/logs/kzai-aigc.log" 2>&1 &
sleep 12
grep -q "Started KzaiAigcApplication" "$ROOT/logs/kzai-aigc.log" && echo "  [INFO] aigc 已以正常 Redis 启动" || echo "  [INFO] aigc 启动中"

SID3=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID3\",\"message\":\"恢复后的消息\"}" $G/chat --max-time 25 > /tmp/kzai_m6_rec.sse
chk "恢复后终态 persisted=true" 1 "$(grep '^data:.*1002' /tmp/kzai_m6_rec.sse | grep -c '"persisted":true')"
chk "恢复后 Redis Key 重新写入" 1 "$($R keys "CHAT:3_${SID3}" | grep -c .)"

echo "================================================="
echo "M6 Redis 降级：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
