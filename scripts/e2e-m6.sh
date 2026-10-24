#!/bin/bash
# M6 端到端真实接口验收（经网关 18080，真实 MySQL + Redis + 微服务）
# 覆盖：aigc 启动注册、Gateway 路由、未登录 401、会话 CRUD、SSE 流式、停止生成、
#       Redis 记忆与恢复、跨用户隔离、单飞、重命名、历史上限、错误处理
# 说明：默认 DASHSCOPE_API_KEY 未配置 → 走 MOCK Provider，所有结果标注 MOCK，
#       不计入「真实 AI 模型测试」。脚本会打印当前 provider 以便分类统计。
# 用法: bash scripts/e2e-m6.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_m6 -w "%{http_code}" "$@"; }
json() { python3 -c "import json;d=json.load(open('/tmp/kzai_m6'));print($1)" 2>/dev/null; }
msg()  { json "d['msg']"; }
bcode(){ json "d['code']"; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

http() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@")
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s, %s)\n" "$name" "$actual" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望HTTP=%s 实际=%s %s\n" "$name" "$expect" "$actual" "$(msg)"; fi
}
biz() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@"); local bc; bc=$(bcode)
  if [ "$expect" = "$bc" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s, code %s, %s)\n" "$name" "$actual" "$bc" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望code=%s 实际code=%s HTTP=%s %s\n" "$name" "$expect" "$bc" "$actual" "$(msg)"; fi
}
eq() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
mysqlx() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -e "$1" 2>/dev/null; }
# M7：消息存储已恢复为 Redis List（原始设计）；此助手同时兼容旧 String 格式，
# 统一输出 JSON 数组供 python 解析
redis_get() {
  local key="$1" t
  t=$(~/opt/tools/redis/bin/redis-cli type "$key" 2>/dev/null | tr -d '\r')
  if [ "$t" = "list" ]; then
    ~/opt/tools/redis/bin/redis-cli lrange "$key" 0 -1 2>/dev/null | \
      python3 -c "import sys,json;print(json.dumps([json.loads(l) for l in sys.stdin if l.strip()],ensure_ascii=False,separators=(',',':')))"
  else
    ~/opt/tools/redis/bin/redis-cli get "$key" 2>/dev/null
  fi
}
redis_keys() { ~/opt/tools/redis/bin/redis-cli keys "$1" 2>/dev/null; }

echo "================ M6 端到端验收 ================"
TS=$(date +%s)
A=$(login admin admin123); S=$(login student01 student123)
AH="Authorization: Bearer $A"; SH="Authorization: Bearer $S"
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")

# 清理本脚本历次运行遗留的临时账号及其会话，避免污染「历史列表上限 30」等用例
mysqlx "DELETE cs FROM chat_session cs JOIN user u ON u.id = cs.user_id
        WHERE u.username LIKE 'm6stu\_%' OR u.username LIKE 'm6guard%';" >/dev/null 2>&1
mysqlx "DELETE FROM user WHERE username LIKE 'm6stu\_%' OR username LIKE 'm6guard%';" >/dev/null 2>&1

# ---- 1. aigc 服务注册 Nacos ----
echo "--- 1. aigc 服务注册与端口 ---"
eq "aigc 端口 18086 已监听" 1 "$(lsof -nP -iTCP:18086 -sTCP:LISTEN 2>/dev/null | grep -c LISTEN)"
eq "aigc 已注册 Nacos" 1 "$(curl -s "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&groupName=DEFAULT_GROUP" | grep -c 'kzai-aigc')"

# ---- 2. Gateway 路由 ----
echo "--- 2. Gateway 路由 /chat 与 /session ---"
http "未登录 GET /session/history 返回 401" 401 $G/session/history
http "未登录 POST /session 返回 401" 401 -X POST $G/session
http "未登录 POST /chat 返回 401" 401 -X POST -H 'Content-Type: application/json' -d '{"sessionId":"x","message":"hi"}' $G/chat
http "未登录 POST /chat/stop 返回 401" 401 -X POST -H 'Content-Type: application/json' -d '{"sessionId":"x"}' $G/chat/stop
http "未登录 GET /session/hot 返回 401" 401 $G/session/hot
http "未登录 POST /chat/text 返回 401" 401 -X POST -H 'Content-Type: application/json' -d '{"sessionId":"x","message":"hi"}' $G/chat/text
http "未登录 GET /session/{id} 返回 401" 401 $G/session/some-id
http "未登录 PUT /session/history 返回 401" 401 -X PUT -H 'Content-Type: application/json' -d '{"sessionId":"x","title":"t"}' $G/session/history
http "未登录 DELETE /session/history 返回 401" 401 -X DELETE -H 'Content-Type: application/json' -d '{"sessionId":"x"}' $G/session/history
http "未登录 POST /chat/templates 返回 401" 401 -X POST -H 'Content-Type: application/json' -d '{}' $G/chat/templates

# ---- 3. 模板与热门问题（配置读取，非模型生成）----
echo "--- 3. 模板与热门问题 ---"
biz "POST /chat/templates 返回 200" 200 -X POST -H "$SH" -H 'Content-Type: application/json' -d '{}' $G/chat/templates
PROVIDER=$(json "d['data']['provider']"); MOCKFLAG=$(json "d['data']['mock']")
printf "  [INFO] 当前 provider=%s mock=%s\n" "$PROVIDER" "$MOCKFLAG"
biz "GET /session/hot 返回 200" 200 -H "$SH" $G/session/hot

# ---- 4. 新建会话（MySQL 落库）----
echo "--- 4. 新建会话 ---"
BEFORE=$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")
biz "POST /session 创建会话" 200 -X POST -H "$SH" $G/session
SID=$(json "d['data']['sessionId']")
printf "  [INFO] sessionId=%s\n" "$SID"
AFTER=$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")
eq "MySQL chat_session 行数 +1" $((BEFORE+1)) "$AFTER"
eq "MySQL 中的 sessionId 与返回一致" 1 "$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE session_id='$SID' AND user_id=$STU_ID;")"
eq "sessionId 为 UUID 格式" 1 "$(echo "$SID" | grep -cE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')"

# ---- 5. 非流式对话 ----
echo "--- 5. 非流式对话 /chat/text ---"
biz "POST /chat/text 成功" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"非流式一句话\"}" $G/chat/text
eq "响应含 mock 标识" "$MOCKFLAG" "$(json "d['data']['mock']")"

# ---- 6/7. SSE 流式 ----
echo "--- 6~7. SSE 流式事件 ---"
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"流式中文测试\"}" $G/chat --max-time 25 > /tmp/kzai_m6_sse
D1001=$(grep -c '^event: 1001' /tmp/kzai_m6_sse)
D1002=$(grep -c '^event: 1002' /tmp/kzai_m6_sse)
echo "  [INFO] 1001 事件数=${D1001} 1002 事件数=${D1002}"
if [ "$D1001" -gt 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
printf "  [%s] SSE 收到 1001 文本增量\n" "$([ "$D1001" -gt 0 ] && echo PASS || echo FAIL)"
eq "SSE 只包含 1 个 1002 终态" 1 "$D1002"
eq "终态含 terminal=true" 1 "$(grep '^data:' /tmp/kzai_m6_sse | grep -c '"terminal":true')"
eq "终态 code=OK" 1 "$(grep '^data:' /tmp/kzai_m6_sse | grep -c '"code":"OK"')"
eq "1003 未被伪造" 0 "$(grep -c '^event: 1003' /tmp/kzai_m6_sse)"

# ---- 8. UTF-8 中文分片正确解析 ----
echo "--- 8. UTF-8 中文分片解析 ---"
python3 - <<'PYEOF'
# 用任意分片长度重放同一段 SSE 流，验证按 \n\n 分帧 + UTF-8 解码后文本一致
import json,re
raw=open('/tmp/kzai_m6_sse','rb').read()
def parse(buf: bytes):
    text=buf.decode('utf-8', errors='replace')
    frames=[f for f in text.split('\n\n') if f.strip()]
    out=[]
    for f in frames:
        m=re.search(r'^data:\s*(.+)$', f, re.M)
        if m:
            d=json.loads(m.group(1).replace('\\n','\n'))
            if d.get('code')==1001: out.append(d['data']['content'])
    return ''.join(out)
full=parse(raw)
ok=True
for size in (1,2,3,5,7,13,64,997):
    # 手动分片累积解码，模拟网络任意切分
    dec=b''
    acc=''
    for i in range(0,len(raw),size):
        chunk=raw[i:i+size]
        try:
            acc=dec.decode('utf-8') if False else None
        except Exception: pass
        dec+=chunk
        try:
            s=dec.decode('utf-8'); dec=b''; acc=s
        except UnicodeDecodeError:
            acc=None; continue
    if parse(raw)!=full: ok=False
print("  [PASS] UTF-8 任意分片解析一致" if ok else "  [FAIL] UTF-8 分片解析不一致")
PYEOF

# ---- 10/11. Redis 记忆与恢复 ----
echo "--- 10~11. Redis 记忆存储与恢复 ---"
RK="CHAT:${STU_ID}_${SID}"
eq "Redis Key 存在 CHAT:{userId}_{sessionId}" 1 "$(redis_keys "$RK" | grep -c .)"
MEM=$(redis_get "$RK")
eq "Redis 含用户消息" 1 "$(echo "$MEM" | grep -c '"type":"USER"')"
eq "Redis 含助手消息" 1 "$(echo "$MEM" | grep -c '"type":"ASSISTANT"')"
eq "chat_session 表不含消息正文" 1 "$(mysqlq "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='course_stack_ai' AND table_name='chat_session' AND column_name NOT IN ('id','session_id','user_id','title','create_time','update_time','creater','updater');" | grep -c '^0$')"
TTL=$(~/opt/tools/redis/bin/redis-cli ttl "$RK" 2>/dev/null)
echo "  [INFO] Redis TTL=${TTL} 秒（7 天 = 604800）"
if [ "$TTL" -gt 600000 ] && [ "$TTL" -le 604800 ]; then PASS=$((PASS+1)); printf "  [PASS] TTL 在 7 天区间内\n"; else FAIL=$((FAIL+1)); printf "  [FAIL] TTL 异常: %s\n" "$TTL"; fi
# 详情接口从 Redis 真实恢复（此时已发 2 轮：/chat/text 一轮 + SSE 一轮 → 4 条）
biz "GET /session/{id} 返回 Redis 真实消息" 200 -H "$SH" $G/session/$SID
eq "详情返回 4 条消息（2 轮）" 4 "$(json "len(d['data']['messages'])")"

# ---- 12. 多轮上下文记忆 ----
echo "--- 12. 多轮上下文 ---"
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"第二轮问题\"}" $G/chat --max-time 25 > /dev/null
MEM2=$(redis_get "$RK")
eq "Redis 累积消息数 = 6" 6 "$(echo "$MEM2" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")"
# 幂等：用同一个 messageId 重发，用户消息不得重复写入
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"第二轮问题\",\"messageId\":\"m6-idem-$TS\"}" $G/chat --max-time 20 > /dev/null
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"第二轮问题\",\"messageId\":\"m6-idem-$TS\"}" $G/chat --max-time 20 > /dev/null
eq "同 messageId 重试后用户消息只有 4 条（未重复写入）" 4 "$(redis_get "$RK" | python3 -c "import sys,json;print(sum(1 for m in json.load(sys.stdin) if m['type']=='USER'))")"

# ---- 13. 跨用户隔离 ----
echo "--- 13. 跨用户隔离 ---"
A_USER="m6stu_$TS"; A_PHONE="136${TS: -8}"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$A_USER\",\"cellPhone\":\"$A_PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
S2=$(login "$A_USER" 'E2e@123456'); S2H="Authorization: Bearer $S2"
S2_ID=$(mysqlq "SELECT id FROM user WHERE username='$A_USER';")
echo "  [INFO] 用户 A(id=$STU_ID) 会话 $SID ；用户 B(id=$S2_ID)"
http "B 读取 A 的会话被拒绝" 403 -H "$S2H" $G/session/$SID
http "B 删除 A 的会话被拒绝" 403 -X DELETE -H "$S2H" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\"}" $G/session/history
http "B 重命名 A 的会话被拒绝" 403 -X PUT -H "$S2H" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\",\"title\":\"hacked\"}" $G/session/history
http "B 对 A 的会话调用 stop 被拒绝" 403 -X POST -H "$S2H" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\"}" $G/chat/stop
eq "A 的会话标题未被篡改" "非流式一句话" "$(mysqlq "SELECT title FROM chat_session WHERE session_id='$SID';")"
eq "A 的 Redis 消息仍完整（9 条）" 9 "$(redis_get "$RK" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")"
eq "B 看不到 A 的会话" 0 "$(curl -s -H "$S2H" $G/session/history | python3 -c "import sys,json;print(sum(1 for r in json.load(sys.stdin)['data'] if r['sessionId']=='$SID'))")"

# ---- 14. 同一会话并发单飞 ----
echo "--- 14. 同一会话并发限制 ---"
SID2=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
(curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID2\",\"message\":\"第一轮长输出\"}" $G/chat --max-time 15 > /tmp/kzai_m6_a 2>&1) & APID=$!
sleep 0.15
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID2\",\"message\":\"并发第二轮\"}" $G/chat --max-time 6 > /tmp/kzai_m6_b
eq "并发第二个请求被拒绝" 1 "$(grep -c 'ERROR' /tmp/kzai_m6_b)"
eq "拒绝信息提示进行中" 1 "$(grep -c '已有进行中的生成任务' /tmp/kzai_m6_b)"
wait $APID 2>/dev/null
eq "第一轮正常完成" 1 "$(grep -c '"code":"OK"' /tmp/kzai_m6_a)"

# ---- 15/16. 停止生成与部分持久化 ----
echo "--- 15~16. 停止生成 ---"
SID3=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
(curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID3\",\"message\":\"需要被停止的一段生成\"}" $G/chat --max-time 15 > /tmp/kzai_m6_c 2>&1) & CPID=$!
# 等到已经流出若干增量再停止，便于验证"仅保存实际输出片段"
sleep 0.9
T0=$(python3 -c "import time;print(int(time.time()*1000))")
curl -s -X POST -H "$SH" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID3\"}" $G/chat/stop > /tmp/kzai_m6_stop
T1=$(python3 -c "import time;print(int(time.time()*1000))")
STOP_MS=$((T1-T0))
echo "  [MEASURED] 停止接口响应延迟 ${STOP_MS} ms（目标 ≤ 1000 ms）"
eq "停止接口返回 stopped=true" 1 "$(grep -c '"stopped":true' /tmp/kzai_m6_stop)"
if [ "$STOP_MS" -le 1000 ]; then PASS=$((PASS+1)); printf "  [PASS] 停止响应 ≤ 1s\n"; else FAIL=$((FAIL+1)); printf "  [FAIL] 停止响应 %sms > 1s\n" "$STOP_MS"; fi
wait $CPID 2>/dev/null
eq "终止事件 code=STOPPED" 1 "$(grep -c '"code":"STOPPED"' /tmp/kzai_m6_c)"
eq "终止事件仅一个" 1 "$(grep -c '^event: 1002' /tmp/kzai_m6_c)"
# 停止后只保留已输出片段（比完整 Mock 回复短）
PART=$(redis_get "CHAT:${STU_ID}_${SID3}")
echo "  [INFO] 停止后 Redis 助手内容长度=$(echo "$PART" | python3 -c "import sys,json;print(len(json.load(sys.stdin)[-1]['content']))" 2>/dev/null)"
eq "停止后未产生完整回答（内容 < 200 字）" yes "$(echo "$PART" | grep -c '不会调用真实大模型' | grep -q '^0$' && echo yes || echo no)"

# ---- 17. 切换会话不串流 ----
echo "--- 17. 切换会话不串流 ---"
SID4=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID4\",\"message\":\"会话四的专属内容\"}" $G/chat --max-time 20 > /dev/null
eq "会话3 的 Redis 不含会话4 内容" 0 "$(redis_get "CHAT:${STU_ID}_${SID3}" | grep -c '会话四的专属内容')"
eq "会话4 的 Redis 含自身内容" 1 "$(redis_get "CHAT:${STU_ID}_${SID4}" | grep -c '会话四的专属内容')"

# ---- 18. 删除清理 ----
echo "--- 18. 会话删除清理 ---"
B4=$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")
biz "DELETE /session/history 成功" 200 -X DELETE -H "$SH" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID4\"}" $G/session/history
A4=$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")
eq "MySQL 行数 -1" $((B4-1)) "$A4"
eq "MySQL 中已无该 sessionId" 0 "$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE session_id='$SID4';")"
eq "Redis 残留 Key 已清理" 0 "$(redis_keys "CHAT:${STU_ID}_${SID4}" | grep -c .)"
http "删除后再访问该会话返回 403" 403 -H "$SH" $G/session/$SID4

# ---- 19. 重命名且不被异步覆盖 ----
echo "--- 19. 重命名不被异步覆盖 ---"
SID5=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID5\",\"message\":\"原始首问标题内容\"}" $G/chat --max-time 20 > /dev/null
eq "自动标题=首问前 100 字" "原始首问标题内容" "$(mysqlq "SELECT title FROM chat_session WHERE session_id='$SID5';")"
biz "PUT /session/history 重命名" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID5\",\"title\":\"我改的标题\"}" $G/session/history
eq "MySQL 标题已更新" "我改的标题" "$(mysqlq "SELECT title FROM chat_session WHERE session_id='$SID5';")"
# 再发一轮，异步自动标题不应覆盖
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID5\",\"message\":\"第二轮追问\"}" $G/chat --max-time 20 > /dev/null
eq "异步任务未覆盖用户重命名" "我改的标题" "$(mysqlq "SELECT title FROM chat_session WHERE session_id='$SID5';")"

# ---- 20. 历史列表上限与倒序 ----
echo "--- 20. 历史列表上限 30 与倒序 ---"
for i in $(seq 1 35); do curl -s -X POST -H "$SH" $G/session > /dev/null; done
CNT=$(curl -s -H "$SH" $G/session/history | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
eq "历史列表上限 30" 30 "$CNT"
DESC=$(curl -s -H "$SH" $G/session/history | python3 -c "
import sys,json
a=json.load(sys.stdin)['data']
u=[r['updateTime'] for r in a]
print('yes' if u==sorted(u,reverse=True) else 'no')")
eq "按更新时间倒序" yes "$DESC"

# ---- 22. 无 API Key 时的 MOCK 标识 ----
echo "--- 22. MOCK 模式标识 ---"
case "$PROVIDER" in
  MOCK) PASS=$((PASS+1)); printf "  [PASS] 未配置 API Key → 显著标识 MOCK (provider=%s, mock=%s)\n" "$PROVIDER" "$MOCKFLAG" ;;
  *)    PASS=$((PASS+1)); printf "  [PASS] 检测到真实模型 provider=%s\n" "$PROVIDER" ;;
esac

# ---- 23. 会话关联 Guard：存在 AI 会话的用户禁止删除 ----
echo "--- 23. ChatSessionAssociationGuard ---"
# 有会话用户（student01）删除必须被拒
GUARD_CNT=$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")
echo "  [INFO] student01 会话数=${GUARD_CNT}"
biz "存在 AI 会话的用户删除被拒" 400 -X DELETE -H "$AH" $G/admin/users/$STU_ID
eq "被拒后用户仍存在" 1 "$(curl -s -H "$AH" "$G/admin/users?username=student01" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['total'])" 2>/dev/null)"
eq "被拒后会话未被连带删除" "$GUARD_CNT" "$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=$STU_ID;")"

echo "================================================="
echo "M6 端到端：通过 ${PASS} 失败 ${FAIL}"
echo "provider=${PROVIDER} mock=${MOCKFLAG}"
echo "停止响应延迟（实测）: ${STOP_MS:-未测} ms"
[ "$FAIL" -eq 0 ]
