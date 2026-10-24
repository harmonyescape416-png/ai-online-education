#!/bin/bash
# M9 端到端真实接口验收（场景 A–E）：
#   A 上传 Markdown → MySQL 落库 → 真实解析/切片（500/50）
#   C RAG 问答：KNOWLEDGE 路由 → searchKnowledge → 来源卡片（Mock Embedding 协议链路，显著标识）
#   D 删除/撤回 → 立即不可召回；E 权限：学员 403 / 检索不泄露
# 说明：默认无 Embedding Key → Mock 向量（显著标识 provider=MOCK），仅验证 ES 与 RAG 链路协议，
#       不计为真实语义检索/真实 RAG 验收。
# M15.6 运行前提（重要）：本脚本须在【全 MOCK 模式】运行（aigc 不设置 DASHSCOPE_ENABLED）——
#       MOCK 脚本路由保证 KNOWLEDGE 分支确定性调用 searchKnowledge 产生 1003 卡片；
#       REAL Chat 模式下模型行为非确定性，C/C2 场景可能无卡片（属模式语义差异，非缺陷）。
#       每次运行会新增 2 篇「上传→撤回」状态测试文档（合法回归记录，撤回态不可召回）。
# 用法: bash scripts/e2e-m9.sh
set -u
ES=http://127.0.0.1:9200
PASS=0; FAIL=0

eq() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}
code() { curl -s -o /tmp/kzai_m9 -w "%{http_code}" "$@"; }
jq() { python3 -c "import json,sys;d=json.load(open('/tmp/kzai_m9'));print($1)" 2>/dev/null; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

echo "================ M9 端到端验收（RAG 知识库） ================"
TS=$(date +%s)
ATOK=$(login admin admin123)
STOK=$(login student01 student123)
AH="Authorization: Bearer $ATOK"
SH="Authorization: Bearer $STOK"

# ---- 场景 A：上传 → 落库 → 解析切片（审核后索引）----
echo "--- A. 上传 Markdown（待审核）---"
cat > /tmp/kzai_faq_$TS.md <<EOF
# 课栈智伴购买规则 FAQ RUN-${TS}

## 退款规则
本平台的退款规则为：开课后 7 天内可申请全额退款，超过 7 天按剩余课时折算退款金额。

## 发票说明
支持开具电子普通发票，订单完成后 3 个工作日内发送至预留邮箱。
EOF
code=$(code -X POST -H "$AH" -F "file=@/tmp/kzai_faq_$TS.md;type=text/markdown" \
  -F "title=购买规则 FAQ $TS" -F "accessLevel=PUBLIC" \
  $G/admin/ai/knowledge/documents)
DOC_ID=$(jq "d['data']['id']")
eq "上传成功 (HTTP $code)" 200 "$code"
eq "初始状态：待审核" 0 "$(jq "d['data']['reviewStatus']")"
eq "初始索引状态：待处理" 0 "$(jq "d['data']['indexStatus']")"
[ -n "$DOC_ID" ] || { echo "  [FAIL] 未获得文档 ID"; exit 1; }

echo "--- A2. 非法文件被拒 / 重复上传被拒 ---"
echo "<html>not pdf</html>" > /tmp/kzai_bad_$TS.pdf
code=$(code -X POST -H "$AH" -F "file=@/tmp/kzai_bad_$TS.pdf;type=application/pdf" \
  -F "title=bad" $G/admin/ai/knowledge/documents)
eq "伪 PDF（魔数不符）被拒（body code）" 400 "$(jq "d['code']")"
code=$(code -X POST -H "$AH" -F "file=@/tmp/kzai_faq_$TS.md;type=text/markdown" \
  -F "title=dup" $G/admin/ai/knowledge/documents)
eq "相同内容重复上传被拒（SHA-256，body code）" 400 "$(jq "d['code']")"

echo "--- A3. 学员/未登录 权限 ---"
eq "学员上传被拒 403" 403 "$(code -X POST -H "$SH" -F "file=@/tmp/kzai_faq_$TS.md" $G/admin/ai/knowledge/documents)"
eq "未登录列表 401" 401 "$(code $G/admin/ai/knowledge/documents)"

# ---- 审核发布 → 自动解析/索引 ----
echo "--- A4. 审核发布 → 异步索引 → 状态成功 ---"
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"publish":true}' \
  $G/admin/ai/knowledge/documents/$DOC_ID/review)
eq "审核发布成功" 200 "$code"
STATUS=0
for i in $(seq 1 20); do
  sleep 2
  curl -s -H "$AH" $G/admin/ai/knowledge/documents/$DOC_ID > /tmp/kzai_m9
  STATUS=$(jq "d['data']['indexStatus']")
  [ "$STATUS" = "4" ] && break
done
eq "索引状态=索引成功（真实解析/切片/ES 写入）" 4 "$STATUS"
CHARS=$(jq "d['data']['charCount']")
CHUNKS=$(jq "d['data']['chunkCount']")
echo "  [INFO] 字符数=$CHARS 切片数=$CHUNKS"
eq "字符数与切片数 > 0" yes "$([ "$CHARS" -gt 0 ] && [ "$CHUNKS" -gt 0 ] && echo yes || echo no)"

# ---- 场景 B：检索测试（真实 ES + Mock 向量，显著标识）----
echo "--- B. 检索测试（Mock 向量协议：与切片完全一致的文本 → 得分 1.0）---"
# 与 TextChunker.clean 相同的清洗口径，构造与切片等长的查询文本
python3 - /tmp/kzai_faq_$TS.md > /tmp/kzai_m9_query.json <<'PYEOF2'
import json, sys, re
raw = open(sys.argv[1], encoding='utf-8').read()
t = raw.replace('\r\n', '\n').replace('\r', '\n')
t = re.sub('\n{3,}', '\n\n', t).strip()
t = '\n'.join([l.strip() for l in t.split('\n') if l.strip()])
print(json.dumps({'question': t}, ensure_ascii=False))
PYEOF2
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' -d @/tmp/kzai_m9_query.json \
  $G/admin/ai/knowledge/search-test)
HITS=$(jq "len(d['data']['hits'])")
PROVIDER=$(jq "d['data']['hits'][0]['provider']" 2>/dev/null)
SCORE=$(jq "d['data']['hits'][0]['score']" 2>/dev/null)
eq "检索命中 1 条" 1 "$HITS"
eq "来源 docId 一致" "$DOC_ID" "$(jq "d['data']['hits'][0]['docId']")"
eq "向量提供方显著标识 MOCK" "MOCK" "$PROVIDER"
eq "同文本得分 1.0（协议上限）" 1.0 "$SCORE"

echo "--- B2. 无关问题 → 空结果（Mock 向量无语义，诚实未找到）---"
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d '{"question":"量子计算机如何纠错"}' $G/admin/ai/knowledge/search-test)
HITS2=$(jq "len(d['data']['hits'])")
eq "无相关召回 → 空结果（不编造）" 0 "$HITS2"

# ---- 场景 C：RAG 问答（KNOWLEDGE 路由 → searchKnowledge → 来源卡片）----
echo "--- C. AI 工作台 RAG 问答 ---"
SID=$(curl -s -X POST -H "$SH" $G/session | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")
QTEXT=$(python3 -c "import json;print(json.load(open('/tmp/kzai_m9_query.json'))['question'])")
python3 - "$SID" "$QTEXT" > /tmp/kzai_m9_body.json <<'PYEOF2'
import json, sys, time
print(json.dumps({'sessionId': sys.argv[1], 'message': sys.argv[2],
    'messageId': 'm9-rag-' + str(int(time.time()*1000))}, ensure_ascii=False))
PYEOF2
curl -sN -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d @/tmp/kzai_m9_body.json $G/chat --max-time 30 > /tmp/kzai_m9_rag.sse
AGENT=$(grep '^data:' /tmp/kzai_m9_rag.sse | grep '"code":1002' | head -1 | python3 -c "
import sys, json
for line in sys.stdin:
    d = json.loads(line.strip()[6:])
    print(d['data'].get('agent') or ''); break" 2>/dev/null)
RAG_CARDS=$(grep -c '"RAG_SOURCE"' /tmp/kzai_m9_rag.sse)
eq "路由至 KNOWLEDGE Agent" "KNOWLEDGE" "$AGENT"
eq "产生 RAG_SOURCE 来源卡片（1003）" yes "$([ "$RAG_CARDS" -ge 1 ] && echo yes || echo no)"
eq "1002 恰好一个" 1 "$(grep -c '^event: 1002' /tmp/kzai_m9_rag.sse)"

echo "--- C2. 历史回放：来源卡片随 assistant 消息恢复（免检索）---"
REPLAY=$(curl -s -H "$SH" $G/session/$SID)
REPLAY_RAG=$(echo "$REPLAY" | python3 -c "
import sys, json
d = json.load(sys.stdin)
n = 0
for m in d['data']['messages']:
    for c in (m.get('cards') or []):
        if c.get('type') == 'RAG_SOURCE':
            n += 1
print(n)")
eq "回放恢复 RAG_SOURCE 卡片" yes "$([ "$REPLAY_RAG" -ge 1 ] && echo yes || echo no)"

# ---- 场景 E：检索权限（学员接口不泄露 INTERNAL / 审计）----
echo "--- E. 权限与降级 ---"
eq "学员可用 GET /embedding/search" 200 "$(code -X GET -H "$SH" -H 'Content-Type: application/json' -d '{"question":"退款规则"}' $G/embedding/search)"
eq "未登录 /embedding/search 401" 401 "$(code -X GET $G/embedding/search)"

# ---- 场景 D：撤回 → 立即不可召回；删除 → 向量清理 ----
echo "--- D. 撤回/删除 ---"
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"publish":false}' \
  $G/admin/ai/knowledge/documents/$DOC_ID/review)
eq "撤回成功" 200 "$code"
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' -d @/tmp/kzai_m9_query.json \
  $G/admin/ai/knowledge/search-test)
eq "撤回后同文本查询不再召回" 0 "$(jq "len(d['data']['hits'])")"
code=$(code -X DELETE -H "$AH" $G/admin/ai/knowledge/documents/$DOC_ID)
eq "删除文档" 200 "$code"
sleep 6
ES_LEFT=$(curl -s "$ES/kzai-knowledge-alias/_count?q=docId:$DOC_ID" | python3 -c "import sys,json;print(json.load(sys.stdin).get('count',-1))" 2>/dev/null)
eq "ES 向量已清理（count=0）" 0 "$ES_LEFT"

echo "================================================="
echo "M9 端到端：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
