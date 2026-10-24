#!/bin/bash
# M7 端到端真实接口验收（经网关 18080，真实 MySQL + Redis + Feign 服务间调用）
# 覆盖：CourseTools/OrderTools（MOCK 脚本化工具流=真实工具实现路径）、
#       1003 课程/订单卡片、1001/1002/1003 顺序、购买确认门禁、预下单幂等、
#       Redis List 存储、历史回放卡片、跨用户隔离
# 核对原则：工具返回的卡片/订单为事实来源，回查 MySQL 对账（不预设具体课程 ID，
#           避免"多门同前缀课程"数据导致的脆弱断言）。
# 说明：默认 DASHSCOPE_API_KEY 未配置 → MOCK Provider + 脚本化工具流（显著标识），
#       验证的是工具链路与协议；真实模型 Tool Calling 另测，不计入本脚本结论。
# 用法: bash scripts/e2e-m7.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_m7 -w "%{http_code}" "$@"; }
json() { python3 -c "import json;d=json.load(open('/tmp/kzai_m7'));print($1)" 2>/dev/null; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
http() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@")
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s)\n" "$name" "$actual"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望HTTP=%s 实际=%s\n" "$name" "$expect" "$actual"; fi
}
eq() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
mysqlx() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -e "$1" 2>/dev/null; }
redis_type() { ~/opt/tools/redis/bin/redis-cli type "$1" 2>/dev/null | tr -d '\r'; }

# SSE 对话：发送消息并把原始帧写入 $3 文件；输出 1001/1003/1002 计数
chat_sse() {
  local sid="$1" text="$2" out="$3"
  python3 - "$sid" "$text" > /tmp/kzai_m7_body.json <<'PYEOF'
import json, sys, time
print(json.dumps({
    'sessionId': sys.argv[1],
    'message': sys.argv[2],
    'messageId': 'm7-' + sys.argv[1][:8] + '-' + str(int(time.time() * 1000))
}, ensure_ascii=False))
PYEOF
  curl -sN -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d @/tmp/kzai_m7_body.json \
    $G/chat --max-time 30 > "$out" 2>/dev/null
  N1001=$(grep -c '^event: 1001' "$out")
  N1003=$(grep -c '^event: 1003' "$out")
  N1002=$(grep -c '^event: 1002' "$out")
}

extract_cards() {
  python3 - "$1" <<'PYEOF'
import sys, json
cards = []
for line in open(sys.argv[1], encoding='utf-8'):
    line = line.strip()
    if line.startswith('data: '):
        try:
            d = json.loads(line[6:])
            if d.get('code') == 1003:
                cards.append(d['data'])
        except Exception:
            pass
print(json.dumps(cards, ensure_ascii=False))
PYEOF
}

extract_text() {
  python3 - "$1" <<'PYEOF'
import sys, json
parts = []
for line in open(sys.argv[1], encoding='utf-8'):
    line = line.strip()
    if line.startswith('data: '):
        try:
            d = json.loads(line[6:])
            if d.get('code') == 1001:
                parts.append(d['data'].get('content') or '')
        except Exception:
            pass
print(''.join(parts))
PYEOF
}

first_course_card_field() {
  # $1=cards JSON $2=payload field → 第一张 COURSE_CARD 的字段值
  echo "$1" | python3 -c "
import sys, json
cards = json.load(sys.stdin)
hit = [c for c in cards if c.get('type') == 'COURSE_CARD']
v = hit[0].get('payload', {}).get('$2') if hit else ''
print('' if v is None else v)
" 2>/dev/null
}

echo "================ M7 端到端验收（工具调用 / 卡片 / 幂等） ================"
TS=$(date +%s)
TOK=$(login student01 student123)
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
PROVIDER=$(curl -s -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{}' $G/chat/templates | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['provider'])")
echo "  [INFO] provider=${PROVIDER}（MOCK 脚本化工具流=真实工具实现，非真实模型）"

# 选一门可购课程用于购买意图表述（上架 + 分类启用 + 购买窗内 + student01 未持有）
COURSE_SQL="SELECT c.id, c.name, c.price FROM course c
  JOIN course_category c1 ON c1.id=c.first_cate_id AND c1.status=1
  JOIN course_category c2 ON c2.id=c.second_cate_id AND c2.status=1
  WHERE c.deleted=0 AND c.status=2
    AND (c.purchase_start_time IS NULL OR c.purchase_start_time<=NOW())
    AND (c.purchase_end_time IS NULL OR c.purchase_end_time>=NOW())
    AND NOT EXISTS (SELECT 1 FROM order_detail od JOIN \`order\` o ON o.id=od.order_id
                    WHERE od.course_id=c.id AND o.user_id=$STU_ID AND o.deleted=0 AND o.status<>3)
  ORDER BY c.id LIMIT 1"
CID=$(mysqlq "$COURSE_SQL" | cut -f1)
CNAME=$(mysqlq "$COURSE_SQL" | cut -f2)
if [ -z "$CID" ]; then echo "  [FAIL] 未找到可购买课程，无法继续订单用例"; exit 1; fi
echo "  [INFO] 购买目标课程 id=$CID name=$CNAME"

SID=$(curl -s -X POST -H "Authorization: Bearer $TOK" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
RK="CHAT:${STU_ID}_${SID}"

# ---- B. 课程工具 ----
echo "--- B1. 课程搜索 → 真实课程卡片（与 MySQL 对账）---"
chat_sse "$SID" "推荐一下${CNAME}相关课程" /tmp/kzai_m7_b1.sse
echo "  [INFO] 1001=$N1001 1003=$N1003 1002=$N1002"
TEXT=$(extract_text /tmp/kzai_m7_b1.sse)
CARDS=$(extract_cards /tmp/kzai_m7_b1.sse)
CARD_ID=$(first_course_card_field "$CARDS" "courseId")
CARD_PRICE=$(first_course_card_field "$CARDS" "price")
CARD_YUAN=$(first_course_card_field "$CARDS" "priceYuan")
DB_PRICE=$(mysqlq "SELECT price FROM course WHERE id=$CARD_ID AND deleted=0;" 2>/dev/null)
DB_NAME=$(mysqlq "SELECT name FROM course WHERE id=$CARD_ID AND deleted=0;" 2>/dev/null)
CARD_NAME=$(first_course_card_field "$CARDS" "name")
eq "搜索回复有 1001 文本增量" yes "$([ "$N1001" -gt 0 ] && echo yes || echo no)"
eq "回复显著标注 MOCK（非真实模型）" 1 "$(echo "$TEXT" | grep -c 'MOCK')"
eq "产生 1003 课程卡片" yes "$([ "$N1003" -gt 0 ] && echo yes || echo no)"
eq "卡片 courseId 为字符串" yes "$([ -n "$CARD_ID" ] && echo yes || echo no)"
eq "卡片课程名与 MySQL 一致" "$DB_NAME" "$CARD_NAME"
eq "卡片价格与 MySQL 一致（分）" "$DB_PRICE" "$CARD_PRICE"
eq "卡片元展示值正确" "$(python3 -c "print(f'{$DB_PRICE/100:.2f}')" 2>/dev/null)" "$CARD_YUAN"
eq "1002 恰好一个" 1 "$N1002"
eq "1003 均在 1002 之前（帧序合法）" 1 "$(python3 -c "
last=''; ok=True
for line in open('/tmp/kzai_m7_b1.sse',encoding='utf-8'):
    line=line.strip()
    if line.startswith('event: '):
        if last=='1002' and line[7:]!='1002': ok=False
        last=line[7:]
print(1 if ok else 0)
")"

echo "--- B2. 课程详情（价格/章节） → 章节数与数据库一致 ---"
chat_sse "$SID" "${CARD_NAME}的价格是多少" /tmp/kzai_m7_b2.sse
CARDS2=$(extract_cards /tmp/kzai_m7_b2.sse)
CARD2_ID=$(first_course_card_field "$CARDS2" "courseId")
CARD2_CHAPTERS=$(first_course_card_field "$CARDS2" "chapterCount")
DB_CHAPTERS=$(mysqlq "SELECT COUNT(*) FROM course_chapter WHERE course_id=$CARD2_ID AND deleted=0;")
eq "1003 详情卡片章节数与 MySQL 一致" "$DB_CHAPTERS" "$CARD2_CHAPTERS"

echo "--- B3. 查询不存在的课程 → 明确未找到，无伪造卡片 ---"
chat_sse "$SID" "找一下量子区块链全栈修仙课程" /tmp/kzai_m7_b3.sse
TEXT3=$(extract_text /tmp/kzai_m7_b3.sse)
eq "不存在课程回复包含 未找到" 1 "$(echo "$TEXT3" | grep -c '未找到')"
eq "不存在课程不产生 1003 卡片" 0 "$N1003"
eq "终态恰好一个" 1 "$N1002"

# ---- C. 订单工具 ----
ORDERS_TOTAL_BEFORE=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
echo "--- C1. 购买意图（未确认）→ 只生成确认提案，不写订单 ---"
chat_sse "$SID" "帮我购买${CNAME}" /tmp/kzai_m7_c1.sse
TEXT_C1=$(extract_text /tmp/kzai_m7_c1.sse)
ORDERS_TOTAL_AFTER1=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
eq "未确认前订单总数不变" "$ORDERS_TOTAL_BEFORE" "$ORDERS_TOTAL_AFTER1"
eq "回复包含确认请求（价格核验）" 1 "$(echo "$TEXT_C1" | grep -c '请确认')"

echo "--- C2. 用户明确确认 → 真实预下单 ---"
chat_sse "$SID" "确认" /tmp/kzai_m7_c2.sse
CARDS_C2=$(extract_cards /tmp/kzai_m7_c2.sse)
TEXT_C2=$(extract_text /tmp/kzai_m7_c2.sse)
ORDER_ID=$(echo "$CARDS_C2" | python3 -c "
import sys, json
cards = json.load(sys.stdin)
hit = [c for c in cards if c.get('type')=='ORDER_CARD']
print(hit[0]['payload']['orderId'] if hit else '')
" 2>/dev/null)
eq "产生 1003 订单卡片" yes "$([ -n "$ORDER_ID" ] && echo yes || echo no)"
echo "  [INFO] 订单号=$ORDER_ID"
if [ -n "$ORDER_ID" ]; then
  DB_STATUS=$(mysqlq "SELECT status FROM \`order\` WHERE id=$ORDER_ID;")
  DB_TOTAL=$(mysqlq "SELECT total_amount FROM \`order\` WHERE id=$ORDER_ID;")
  DB_OUSER=$(mysqlq "SELECT user_id FROM \`order\` WHERE id=$ORDER_ID;")
  ORDER_COURSE=$(mysqlq "SELECT course_id FROM order_detail WHERE order_id=$ORDER_ID LIMIT 1;")
  ORDER_CNAME=$(mysqlq "SELECT name FROM order_detail WHERE order_id=$ORDER_ID LIMIT 1;")
  DB_COURSE_NAME=$(mysqlq "SELECT name FROM course WHERE id=$ORDER_COURSE AND deleted=0;")
  DB_COURSE_PRICE=$(mysqlq "SELECT price FROM course WHERE id=$ORDER_COURSE AND deleted=0;")
  DB_IDEM=$(mysqlq "SELECT COUNT(*) FROM order_request_idempotent WHERE order_id=$ORDER_ID;")
  eq "MySQL order.status=1（待支付）" 1 "$DB_STATUS"
  eq "订单金额与交易服务计算一致（=该课程实时价）" "$DB_COURSE_PRICE" "$DB_TOTAL"
  eq "订单归属当前用户" "$STU_ID" "$DB_OUSER"
  eq "order_detail 存在课程快照明细" 1 "$(mysqlq "SELECT COUNT(*) FROM order_detail WHERE order_id=$ORDER_ID AND course_id=$ORDER_COURSE;")"
  eq "明细快照课程名与数据库一致" "$DB_COURSE_NAME" "$ORDER_CNAME"
  eq "幂等记录已落库" 1 "$DB_IDEM"
  eq "回复明确「待支付」而非购买成功" 1 "$(echo "$TEXT_C2" | grep -c '待支付')"
  ORDERS_TOTAL_C2=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
  eq "确认后恰好新增一笔订单" "$((ORDERS_TOTAL_BEFORE + 1))" "$ORDERS_TOTAL_C2"
else
  FAIL=$((FAIL+1)); printf "  [FAIL] 未产生订单，中断后续用例\n"
fi

echo "--- C3. 重复确认（模型重复工具调用）→ 不重复创建订单 ---"
chat_sse "$SID" "确认" /tmp/kzai_m7_c3.sse
sleep 1
ORDERS_TOTAL_C3=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
eq "重复确认未新增订单" "$ORDERS_TOTAL_C2" "$ORDERS_TOTAL_C3"

echo "--- C4. 并发重复确认 → 幂等约束仍只新增一笔 ---"
COURSE2_SQL=$(echo "$COURSE_SQL" | sed "s/ORDER BY c.id LIMIT 1/AND c.id<>$CID ORDER BY c.id LIMIT 1/")
CID2=$(mysqlq "$COURSE2_SQL" | cut -f1)
CNAME2=$(mysqlq "$COURSE2_SQL" | cut -f2)
if [ -n "$CID2" ]; then
  chat_sse "$SID" "帮我购买${CNAME2}" /tmp/kzai_m7_c4a.sse >/dev/null
  (chat_sse "$SID" "确认" /tmp/kzai_m7_c4b1.sse) &
  (chat_sse "$SID" "好的" /tmp/kzai_m7_c4b2.sse) &
  wait
  sleep 1
  ORDERS_TOTAL_C4=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
  eq "第二门课并发确认也只新增一笔订单" "$((ORDERS_TOTAL_C2 + 1))" "$ORDERS_TOTAL_C4"
else
  echo "  [INFO] 无第二门可购课程，跳过并发用例（单元测试已覆盖唯一索引并发）"
fi

echo "--- D. Redis List 存储 / 历史回放 / 隔离 ---"
eq "CHAT Key 为 Redis List 类型（原始设计）" list "$(redis_type "$RK")"
DETAIL=$(curl -s -H "Authorization: Bearer $TOK" $G/session/$SID)
REPLAY_CARDS=$(echo "$DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
cards = []
for m in d['data']['messages']:
    cards.extend(m.get('cards') or [])
print(json.dumps(cards, ensure_ascii=False))
")
REPLAY_COURSE_HITS=$(echo "$REPLAY_CARDS" | grep -c COURSE_CARD)
REPLAY_ORDER_HITS=$(echo "$REPLAY_CARDS" | grep -c ORDER_CARD)
eq "历史回放恢复课程卡片" yes "$([ "$REPLAY_COURSE_HITS" -gt 0 ] && echo yes || echo no)"
eq "历史回放恢复订单卡片" yes "$([ "$REPLAY_ORDER_HITS" -gt 0 ] && echo yes || echo no)"
REPLAY_ORDER=$(echo "$REPLAY_CARDS" | python3 -c "
import sys, json
cards = json.load(sys.stdin)
hit = [c for c in cards if c.get('type')=='ORDER_CARD']
print(hit[0]['payload']['orderId'] if hit else '')
" 2>/dev/null)
eq "回放卡片订单号与真实订单一致" "$ORDER_ID" "$REPLAY_ORDER"
# 跨用户：B 用户不能读 A 会话，A 的订单数据不因 B 的操作变化
B_USER="m7stu_$TS"
B_PHONE="139${TS:0:8}"
curl -s -X POST -H "Authorization: Bearer $(login admin admin123)" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$B_USER\",\"cellPhone\":\"$B_PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
BTOK=$(login "$B_USER" 'E2e@123456')
http "B 访问 A 的会话被拒绝" 403 -H "Authorization: Bearer $BTOK" $G/session/$SID
ORDERS_ISO=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
eq "B 未登录 A 会话期间 A 的订单未被篡改" "$ORDERS_TOTAL_C4" "$ORDERS_ISO"

echo "--- E. 工具审计日志 ---"
AUDIT_HITS=$(grep -c "TOOL_AUDIT" logs/kzai-aigc.log 2>/dev/null)
AUDIT_HITS=${AUDIT_HITS:-0}
eq "TOOL_AUDIT 审计日志已产生" yes "$([ "$AUDIT_HITS" -gt 0 ] && echo yes || echo no)"
SAMPLE=$(grep "TOOL_AUDIT" logs/kzai-aigc.log | tail -1)
echo "  [INFO] 审计样例: $SAMPLE"

mysqlx "DELETE cs FROM chat_session cs JOIN user u ON u.id=cs.user_id WHERE u.username='$B_USER';" >/dev/null 2>&1
mysqlx "DELETE FROM user WHERE username='$B_USER';" >/dev/null 2>&1

echo "================================================="
echo "M7 端到端：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
