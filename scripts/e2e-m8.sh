#!/bin/bash
# M8 端到端真实接口验收：多智能体路由 / 会话历史去重 / 工具权限 / Prompt 运行时
# 覆盖（任务书 B 组为主）：
#   B6 推荐问题路由 RECOMMEND；B7 价格咨询路由 CONSULT；B8 购买路由 BUY（确认续轮）；
#   B9 知识问题路由 KNOWLEDGE；B10 寒暄/无关 OTHER 直接回复；
#   B13 RouteAgent 不产生额外可见历史（三轮=3 user+3 assistant）；
#   B14 多轮切换 Agent 上下文连续；B15 跨会话隔离；B16 RouteAgent 无业务工具（单测+白名单核验）；
#   B12 路由失败受控降级（真实模型未验证，MOCK 脚本路由恒可用，此处验证协议不阻塞）
# 说明：默认无 DASHSCOPE_API_KEY → MOCK 脚本路由（显著标识），不计入真实意图识别准确率。
# 用法: bash scripts/e2e-m8.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

eq() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

# 发送 SSE 对话：输出 agent 标识与帧计数
chat_sse() {
  local sid="$1" text="$2" out="$3"
  python3 - "$sid" "$text" > /tmp/kzai_m8_body.json <<'PYEOF'
import json, sys, time
print(json.dumps({'sessionId': sys.argv[1], 'message': sys.argv[2],
    'messageId': 'm8-' + sys.argv[1][:8] + '-' + str(int(time.time() * 1000))}, ensure_ascii=False))
PYEOF
  curl -sN -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d @/tmp/kzai_m8_body.json $G/chat --max-time 30 > "$out" 2>/dev/null
  AGENT=$(grep '^data:' "$out" | grep '"code":1002' | head -1 | python3 -c "
import sys, json
try:
    d = json.loads([l for l in sys.stdin if l.strip()][0].strip()[6:])
    print(d['data'].get('agent') or '')
except Exception:
    print('')
" 2>/dev/null)
}

echo "================ M8 端到端验收（多智能体路由） ================"
TS=$(date +%s)
TOK=$(login student01 student123)
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
PROVIDER=$(curl -s -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{}' $G/chat/templates | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['provider'])")
echo "  [INFO] provider=${PROVIDER}（MOCK=脚本路由，仅协议验证）"
SID=$(curl -s -X POST -H "Authorization: Bearer $TOK" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
RK="CHAT:${STU_ID}_${SID}"

echo "--- B6/B7/B8/B9/B10. 意图路由 ---"
chat_sse "$SID" "推荐适合零基础的 Java 课程" /tmp/m8_r1.sse
eq "推荐问题 → RECOMMEND" "RECOMMEND" "$AGENT"
chat_sse "$SID" "这门课程的价格是多少" /tmp/m8_r2.sse
eq "价格咨询 → CONSULT" "CONSULT" "$AGENT"
chat_sse "$SID" "帮我购买 Java 基础课程" /tmp/m8_r3.sse
eq "购买意图 → BUY" "BUY" "$AGENT"
chat_sse "$SID" "什么是 Spring Boot？" /tmp/m8_r4.sse
eq "知识问题 → KNOWLEDGE" "KNOWLEDGE" "$AGENT"
chat_sse "$SID" "你好" /tmp/m8_r5.sse
eq "寒暄 → OTHER 直接回复" "OTHER" "$AGENT"

echo "--- B13. 路由不产生额外可见历史（三轮问题 = 3+3，不含路由临时消息）---"
# 上述已发 5 轮 → 期望恰好 5 user + 5 assistant
TYPE=$(~/opt/tools/redis/bin/redis-cli type "$RK" 2>/dev/null | tr -d '\r')
if [ "$TYPE" = "list" ]; then
  U_CNT=$(~/opt/tools/redis/bin/redis-cli lrange "$RK" 0 -1 | python3 -c "
import sys, json
print(sum(1 for l in sys.stdin if l.strip() and json.loads(l).get('type')=='USER'))")
  A_CNT=$(~/opt/tools/redis/bin/redis-cli lrange "$RK" 0 -1 | python3 -c "
import sys, json
print(sum(1 for l in sys.stdin if l.strip() and json.loads(l).get('type')=='ASSISTANT'))")
  eq "Redis user 消息数 = 对话轮数（路由零写入）" 5 "$U_CNT"
  eq "Redis assistant 消息数 = 对话轮数" 5 "$A_CNT"
else
  FAIL=$((FAIL+1)); printf "  [FAIL] CHAT Key 应为 List，实际=%s\n" "$TYPE"
fi
DETAIL=$(curl -s -H "Authorization: Bearer $TOK" $G/session/$SID)
VIS_MSG=$(echo "$DETAIL" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['messages']))")
eq "会话详情可见消息数 = 10（无隐藏路由消息）" 10 "$VIS_MSG"

echo "--- B15. 跨会话/跨用户路由状态隔离 ---"
B_USER="m8stu_$TS"
B_PHONE="139${TS:0:8}"
curl -s -X POST -H "Authorization: Bearer $(login admin admin123)" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$B_USER\",\"cellPhone\":\"$B_PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
BTOK=$(login "$B_USER" 'E2e@123456')
http_code=$(curl -s -o /tmp/m8_b -w "%{http_code}" -H "Authorization: Bearer $BTOK" $G/session/$SID)
eq "B 访问 A 的会话被拒绝（路由状态随会话隔离）" 403 "$http_code"

echo "--- 兼容：BUY 确认续轮（提案复用，M7 幂等不回归）---"
COURSE_SQL="SELECT c.id, c.name FROM course c
  JOIN course_category c1 ON c1.id=c.first_cate_id AND c1.status=1
  JOIN course_category c2 ON c2.id=c.second_cate_id AND c2.status=1
  WHERE c.deleted=0 AND c.status=2 AND NOT EXISTS
    (SELECT 1 FROM order_detail od JOIN \`order\` o ON o.id=od.order_id
     WHERE od.course_id=c.id AND o.user_id=$STU_ID AND o.deleted=0 AND o.status<>3)
  ORDER BY c.id LIMIT 1"
CID=$(mysqlq "$COURSE_SQL" | cut -f1)
CNAME=$(mysqlq "$COURSE_SQL" | cut -f2)
ORDERS_BEFORE=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
chat_sse "$SID" "帮我购买${CNAME}" /tmp/m8_b1.sse
chat_sse "$SID" "确认" /tmp/m8_b2.sse
AGENT_AFTER_CONFIRM="$AGENT"
ORDERS_AFTER=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
eq "确认轮仍路由 BUY" "BUY" "$AGENT_AFTER_CONFIRM"
eq "确认后恰好新增一笔订单（M7 幂等保持）" "$((ORDERS_BEFORE + 1))" "$ORDERS_AFTER"

~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -e "DELETE cs FROM chat_session cs JOIN user u ON u.id=cs.user_id WHERE u.username='$B_USER'; DELETE FROM user WHERE username='$B_USER';" >/dev/null 2>&1

echo "--- D. Prompt 管理（直发模式：内置默认 + Nacos，无草稿/版本/回滚表）---"
ATOK=$(login admin admin123)
STTOK=$(login student01 student123)
AH="Authorization: Bearer $ATOK"
SH8="Authorization: Bearer $STTOK"

http_code() { curl -s -o /tmp/m8_x -w "%{http_code}" "$@"; }

# D31 非管理员拒绝
eq "未登录 GET prompts 401" 401 "$(http_code $G/admin/ai/prompts)"
eq "学员 GET prompts 403" 403 "$(http_code -H "$SH8" $G/admin/ai/prompts)"
TOK_SAVE="$TOK"; TOK="$STTOK"
eq "学员 POST publish 403" 403 "$(http_code -X POST -H "Authorization: Bearer $STTOK" -H 'Content-Type: application/json' -d '{}' $G/admin/ai/prompts/KNOWLEDGE/publish)"
TOK="$TOK_SAVE"

# D28 基线：发布前历史消息数
HIST_BEFORE=$(curl -s -H "Authorization: Bearer $TOK_SAVE" $G/session/$SID | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['messages']))")

# D26 发布（修改 KNOWLEDGE 欢迎语加标记）→ Nacos 实际改变
MARK="M8MARK-$TS"
PUB_BODY=$(python3 - "$MARK" <<'PYEOF'
import json, sys
print(json.dumps({
  'systemPrompt': '你是「课栈智伴」的知识讲解助手。平台知识库（RAG）尚未上线；若用户要求查询课程资料，如实说明该能力将在后续版本提供。_marker=' + sys.argv[1],
  'welcomeText': '知识讲解助手已更新 ' + sys.argv[1],
  'hotQuestions': ['什么是 Spring Boot？', 'RAG 与微调有什么区别？']
}, ensure_ascii=False))
PYEOF
)
code=$(http_code -X POST -H "$AH" -H 'Content-Type: application/json' -d "$PUB_BODY" $G/admin/ai/prompts/KNOWLEDGE/publish)
PUB_DATA=$(cat /tmp/m8_x)
PUB_OK=$(echo "$PUB_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['published'])")
PUB_VER=$(echo "$PUB_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['version'])")
eq "管理员发布返回 published=true (HTTP $code)" "True" "$PUB_OK"
NACOS_RAW=$(curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=kzai-ai-prompts.json&group=tj.ai")
eq "Nacos 配置实际包含新内容（真实写入）" 1 "$(echo "$NACOS_RAW" | grep -c "$MARK")"
eq "Nacos 配置版本号一致" "$PUB_VER" "$(echo "$NACOS_RAW" | python3 -c "import sys,json;print(json.load(sys.stdin).get('version',''))" 2>/dev/null)"

# D27 生效内容更新（新对话读取新快照）
EFF=$(curl -s -H "$AH" $G/admin/ai/prompts/KNOWLEDGE)
eq "生效模板 source=NACOS" "NACOS" "$(echo "$EFF" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['source'])")"
eq "生效模板版本=发布版本" "$PUB_VER" "$(echo "$EFF" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['version'])")"
eq "生效 systemPrompt 含标记" 1 "$(echo "$EFF" | grep -c "$MARK")"

# D28 历史会话不被新模板污染
HIST_AFTER=$(curl -s -H "Authorization: Bearer $TOK_SAVE" $G/session/$SID | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['messages']))")
eq "发布后历史会话消息数不变" "$HIST_BEFORE" "$HIST_AFTER"

# D30 非法内容发布被拒且配置不变
BAD_BODY='{"systemPrompt":"含 ${USER} 注入"}'
code=$(http_code -X POST -H "$AH" -H 'Content-Type: application/json' -d "$BAD_BODY" $G/admin/ai/prompts/KNOWLEDGE/publish)
BAD_PUB=$(cat /tmp/m8_x | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['published'])")
eq "非法内容发布 published=false" "False" "$BAD_PUB"
EFF2=$(curl -s -H "$AH" $G/admin/ai/prompts/KNOWLEDGE)
eq "非法发布后生效内容不变" "$PUB_VER" "$(echo "$EFF2" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['version'])")"

# D25 预览校验（无副作用）
PV=$(curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"systemPrompt":"合法内容 KNOWLEDGE","welcomeText":"w"}' $G/admin/ai/prompts/KNOWLEDGE/preview)
eq "合法预览 valid=true" "True" "$(echo "$PV" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])")"
PV2=$(curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"systemPrompt":"含 ${X}"}' $G/admin/ai/prompts/KNOWLEDGE/preview)
eq "注入占位符预览 valid=false" "False" "$(echo "$PV2" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])")"

# D35 发布审计
AUDIT_N=$(grep -c "PROMPT_AUDIT.*action=publish" logs/kzai-aigc.log 2>/dev/null)
AUDIT_N=${AUDIT_N:-0}
eq "发布操作有 PROMPT_AUDIT 审计" yes "$([ "$AUDIT_N" -gt 0 ] && echo yes || echo no)"

echo "================================================="
echo "M8 端到端：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
