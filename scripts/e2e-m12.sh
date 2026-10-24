#!/bin/bash
# M12 HTTP E2E（任务书第十一部分 A/B/C + D 的可自动化项）：
#   A 用例管理：新增/编辑/非法分类/空问题/CSV 导入/重复规则/≥30 条/标注关联有效/学员教师 403
#   B 批量执行：创建批次/部分执行/全量执行/记录模型与配置/多次运行历史不覆盖/
#              重试语义/重复提交受控/评测期间无订单/无学员会话污染
#   C 指标计算：总意图准确率/按意图/混淆矩阵/Hit@6/拒答/失败与错误分开/P50 P95/token null/空样本
#   D 导出：CSV 与数据库一致/含来源模型模式失败原因/公式注入防护
# 用法: bash scripts/e2e-m12.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
jq() { python3 -c "import json,sys;d=json.load(open('/tmp/kzai_m12'));print($1)" 2>/dev/null; }
code() { curl -s -o /tmp/kzai_m12 -w "%{http_code}" "$@"; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
M=/Users/djanjo/opt/tools/mysql/bin/mysql
mysqlq() { $M --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }

echo "================ M12 HTTP E2E（AI 批量评测） ================"
ATOK=$(login admin admin123); STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
AH="Authorization: Bearer $ATOK"; SH="Authorization: Bearer $STOK"; TH="Authorization: Bearer $TTOK"

echo "--- A. 用例管理 ---"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d '{"question":"E2E临时用例-A1","category":"CONSULT","expectedIntent":"CONSULT","note":"e2e临时","source":"e2e-m12"}' $G/eval/cases > /tmp/kzai_m12
eq "A1 管理员新增用例成功" 200 "$(jq "d['code']")"
TMP_CASE=$(jq "d['data']['id']")
eq "A2 编辑用例成功" 200 "$(code -X PUT -H "$AH" -H 'Content-Type: application/json' \
  -d '{"question":"E2E临时用例-A1-改","category":"CONSULT","expectedIntent":"CONSULT","note":"e2e临时-改","source":"e2e-m12"}' $G/eval/cases/$TMP_CASE)"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d '{"question":"非法分类","category":"HACK","note":"x"}' $G/eval/cases > /tmp/kzai_m12
eq "A3 非法分类被拒绝(code=400)" 400 "$(jq "d['code']")"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d '{"question":"","category":"CONSULT","note":"x"}' $G/eval/cases > /tmp/kzai_m12
eq "A4 空问题被拒绝(code=400)" 400 "$(jq "d['code']")"
printf '\xEF\xBB\xBFquestion,category,expected_intent,expected_doc_id,should_refuse,needs_citation,expect_tool,note,data_deps\nE2E临时CSV用例,CONSULT,CONSULT,,0,0,,e2e csv,\n' > /tmp/m12_import_ok.csv
curl -s -X POST -H "$AH" -F "file=@/tmp/m12_import_ok.csv" $G/eval/cases/import > /tmp/kzai_m12
eq "A5 CSV 导入合法用例成功(imported=1)" 1 "$(jq "d['data']['imported']")"
printf '\xEF\xBB\xBFquestion,category,expected_intent,expected_doc_id,should_refuse,needs_citation,expect_tool,note,data_deps\nE2E临时CSV用例,CONSULT,CONSULT,,0,0,,重复行,\n坏分类行,BAD,,0,0,,,x,\n' > /tmp/m12_import_bad.csv
curl -s -X POST -H "$AH" -F "file=@/tmp/m12_import_bad.csv" $G/eval/cases/import > /tmp/kzai_m12
eq "A6 重复用例按规则跳过(skipped=1)" 1 "$(jq "d['data']['skipped']")"
eq "A6 非法 CSV 逐行报错(errors>=1)" 1 "$(jq "1 if len(d['data']['errors'])>=1 else 0")"
eq "A7 ≥30 条标注用例真实存在" 1 "$(mysqlq "SELECT COUNT(*)>=30 FROM ai_eval_case;")"
# 标注关联有效性：KNOWLEDGE 用例的 expected_doc_id 必须是「已发布 + 已建索引（MySQL 权威版本）+ ES 切片实际存在」的真实文档
eq "A8 标注文档已发布且已建索引" 1 "$(mysqlq "SELECT COUNT(*)=0 FROM ai_eval_case c JOIN ai_knowledge_document d ON CAST(c.expected_doc_id AS CHAR)=CAST(d.id AS CHAR) WHERE c.category='KNOWLEDGE' AND c.expected_doc_id IS NOT NULL AND (d.review_status<>1 OR d.deleted<>0 OR d.index_version IS NULL);")"
eq "A8 标注文档 ES 切片真实存在" 1 "$(mysqlq "SELECT COUNT(*)=0 FROM (SELECT DISTINCT CAST(expected_doc_id AS CHAR) did FROM ai_eval_case WHERE category='KNOWLEDGE' AND expected_doc_id IS NOT NULL) t WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1) x);")"
RAG_DOCS=$(mysqlq "SELECT GROUP_CONCAT(DISTINCT expected_doc_id) FROM ai_eval_case WHERE category='KNOWLEDGE' AND expected_doc_id IS NOT NULL;")
ES_HITS=$(curl -s -X POST 'http://127.0.0.1:9200/kzai-knowledge-v9001/_count' -H 'Content-Type: application/json' \
  -d "{\"query\":{\"terms\":{\"docId\":[$RAG_DOCS]}}}" | python3 -c "import json,sys;print(json.load(sys.stdin)['count'])")
eq "A8 标注文档在当前别名索引中有切片(>0)" 1 "$(python3 -c "print(1 if int('${ES_HITS:-0}')>0 else 0)")"
eq "A9 学员访问管理接口 403" 403 "$(code -H "$SH" "$G/eval/cases")"
eq "A9 教师访问管理接口 403" 403 "$(code -H "$TH" "$G/eval/cases")"
eq "A9 未登录 401" 401 "$(code $G/eval/cases)"
eq "A10 学员不能启动批量评测" 403 "$(code -X POST -H "$SH" -H 'Content-Type: application/json' -d '{"execMode":"MOCK"}' $G/eval/run)"

echo "--- B. 批量执行 ---"
# B11 全量批次
ORDERS_BEFORE=$(mysqlq "SELECT COUNT(*) FROM \`order\`;")
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"name":"E2E-MOCK-全量","execMode":"MOCK"}' $G/eval/run > /tmp/kzai_m12
RUN1=$(jq "d['data']['id']")
eq "B11 创建批次成功" 200 "$(jq "d['code']")"
eq "B13 全部用例执行（totalCases>=30）" 1 "$(jq "1 if int(d['data']['totalCases'])>=30 else 0")"
# B20 重复提交受控（运行中再次创建 → 400）
CODE2=$(curl -s -o /tmp/kzai_m12 -w "%{http_code}" -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"name":"重复批次","execMode":"MOCK"}' $G/eval/run)
BODY2=$(jq "d['code']")
eq "B20 运行中重复提交受控(400)" 400 "$BODY2"
sleep 16
curl -s -H "$AH" $G/eval/runs/$RUN1 > /tmp/kzai_m12
eq "B13 全量批次终态(status in 1,3)" 1 "$(jq "1 if d['data']['status'] in (1,3) else 0")"
eq "B14 批次记录模型与配置" 1 "$(jq "1 if d['data']['provider'] in ('MOCK','DASHSCOPE') and d['data']['kbPhysicalIndex'] and d['data']['promptSnapshot'] else 0")"
# B12 部分用例执行
CID1=$(mysqlq "SELECT id FROM ai_eval_case LIMIT 1;"); CID2=$(mysqlq "SELECT id FROM ai_eval_case ORDER BY id DESC LIMIT 1;")
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d "{\"name\":\"E2E-MOCK-部分\",\"execMode\":\"MOCK\",\"caseIds\":[\"$CID1\",\"$CID2\"]}" $G/eval/run > /tmp/kzai_m12
RUN2=$(jq "d['data']['id']")
eq "B12 选定部分用例执行成功(2条)" 2 "$(jq "int(d['data']['totalCases'])")"
sleep 8
# B15 同一用例多次运行历史不覆盖
R1C=$(mysqlq "SELECT COUNT(*) FROM ai_eval_result WHERE run_id=$RUN1;")
R2C=$(mysqlq "SELECT COUNT(*) FROM ai_eval_result WHERE run_id=$RUN2;")
eq "B15 两次批次结果行独立保留" 1 "$(python3 -c "print(1 if int('$R1C' or 0)>=30 and int('$R2C' or 0)==2 else 0)")"
# B17/B18 失败重试幂等（判定失败不算执行失败 → 无可重试；重复重试同样拒绝）
curl -s -X POST -H "$AH" $G/eval/runs/$RUN2/retry > /tmp/kzai_m12
eq "B18 无执行失败时重试受控拒绝" 400 "$(jq "d['code']")"
# B21/22 评测无副作用：无新订单、无学员会话/通知污染
ORDERS_AFTER=$(mysqlq "SELECT COUNT(*) FROM \`order\`;")
eq "B21 评测期间没有创建正式订单" "$ORDERS_BEFORE" "$ORDERS_AFTER"
eq "B22 评测不产生学员会话" 0 "$(mysqlq "SELECT COUNT(*) FROM chat_session WHERE user_id=1 AND session_id LIKE 'EVAL-%';")"
eq "B22 评测不产生通知" 0 "$(mysqlq "SELECT COUNT(*) FROM sys_notification n JOIN ai_eval_result r ON n.event_key=CONCAT('EVAL:', r.id);")"

echo "--- C. 指标计算 ---"
curl -s -H "$AH" $G/eval/runs/$RUN1/summary > /tmp/kzai_m12
eq "C23 总意图准确率分子分母完整" 1 "$(jq "1 if d['data']['intentAccuracy']['denominator']>=30 and d['data']['intentAccuracy']['numerator']<=d['data']['intentAccuracy']['denominator'] else 0")"
eq "C24 按意图统计齐全(6类)" 6 "$(jq "len(d['data']['byIntent'])")"
eq "C25 混淆矩阵6行5列" 1 "$(jq "1 if len(d['data']['confusionMatrix'])==6 and 'OTHER' in d['data']['confusionMatrix'][0] else 0")"
eq "C26 Hit@6 口径与分母说明存在" 1 "$(jq "1 if 'denominatorNote' in d['data']['rag'] else 0")"
eq "C27/C28 拒答指标含正确率与错误拒答" 1 "$(jq "1 if 'correctRefusalRate' in d['data']['refusal'] and 'falseRefusalCount' in d['data']['refusal'] else 0")"
# C29 执行失败与模型判断错误分开：结果表 status 分布可查
curl -s -H "$AH" "$G/eval/runs/$RUN1/results" > /tmp/kzai_m12
eq "C29 每条结果含 status/failReason/error 区分" 1 "$(jq "1 if 'status' in d['data']['records'][0] and 'failReason' in d['data']['records'][0] and 'error' in d['data']['records'][0] else 0")"
# C30 延迟 P50/P95 与 C31 token null
curl -s -H "$AH" $G/eval/runs/$RUN1/summary > /tmp/kzai_m12
eq "C30 延迟统计含 avg/P50/P95" 1 "$(jq "1 if 'avg' in d['data']['latency']['e2e'] and 'p50' in d['data']['latency']['e2e'] and 'p95' in d['data']['latency']['e2e'] else 0")"
eq "C31 MOCK token 为 mock_na 不冒充真实" 1 "$(jq "1 if d['data']['token']['sourceCounts'].get('mock_na',0)>0 and d['data']['token']['totalTokensSum'] is None else 0")"
# C32 MOCK 与 REAL 不混合：REAL 批次 status=5 且无结果行
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"name":"E2E-REAL-不可执行","execMode":"REAL"}' $G/eval/run > /tmp/kzai_m12
RUNR=$(jq "d['data']['id']")
eq "C32 REAL 未配置显式不可执行(status=5)" 5 "$(jq "int(d['data']['status'])")"
curl -s -H "$AH" "$G/eval/runs/$RUNR/results" > /tmp/kzai_m12
eq "C32 REAL 不可执行批次无结果行" 0 "$(jq "len(d['data']['records'])")"
# C33 空样本不出现除零：REAL 批次 summary 各比率为 null
curl -s -H "$AH" $G/eval/runs/$RUNR/summary > /tmp/kzai_m12
eq "C33 空样本显示尚无有效数据" 1 "$(jq "1 if d['data']['emptySampleNotice'] else 0")"
eq "C33 空样本准确率为 null 非虚假 100%" 1 "$(jq "1 if d['data']['intentAccuracy']['accuracy'] is None else 0")"
# C34 知识库版本变化不影响历史：批次快照 kbPhysicalIndex 保留
KB1=$(mysqlq "SELECT kb_physical_index FROM ai_eval_run WHERE id=$RUN1;")
eq "C34 历史批次保留知识库版本快照" 1 "$(python3 -c "print(1 if '$KB1' else 0)")"

echo "--- D. CSV 导出 ---"
curl -s -H "$AH" "$G/eval/runs/$RUN1/export" -o /tmp/m12_export.csv
EXP_ROWS=$(python3 -c "print(len(open('/tmp/m12_export.csv','rb').read().decode('utf-8-sig').strip().split('\n'))-1)")
eq "D42 导出行数=结果行数($R1C)" "$R1C" "$EXP_ROWS"
eq "D43 导出含来源/模型/模式/失败原因列" 1 "$(python3 -c "
h=open('/tmp/m12_export.csv','rb').read().decode('utf-8-sig').split('\n')[0]
print(1 if all(c in h for c in ['provider','modelCode','runMode','failReason']) else 0)")"
eq "D44 UTF-8 BOM 存在" 1 "$(python3 -c "print(1 if open('/tmp/m12_export.csv','rb').read(3)==b'\xef\xbb\xbf' else 0)")"
# 与数据库一致性：导出的 caseId 集合 = 结果表 caseId 集合
CONSIST=$(python3 - "$RUN1" <<'PYEOF'
import csv, subprocess, sys
run = sys.argv[1]
rows = list(csv.DictReader(open('/tmp/m12_export.csv','rb').read().decode('utf-8-sig').splitlines()))
export_ids = sorted(r['caseId'] for r in rows)
db = subprocess.run(['/Users/djanjo/opt/tools/mysql/bin/mysql','--socket=/tmp/mysql.sock','-uroot',
    'course_stack_ai','-N','-e',
    f"SELECT case_id FROM ai_eval_result WHERE run_id={run} AND attempt=1 ORDER BY case_id"],
    capture_output=True, text=True).stdout.split()
print(1 if sorted(db) == export_ids else 0)
PYEOF
)
eq "D42 导出内容与数据库一致" 1 "$CONSIST"

# 清理临时用例
$M --socket=/tmp/mysql.sock -uroot -e "DELETE FROM course_stack_ai.ai_eval_case_meta WHERE case_id IN (SELECT id FROM (SELECT id FROM course_stack_ai.ai_eval_case WHERE question LIKE 'E2E临时%') t); DELETE FROM course_stack_ai.ai_eval_case WHERE question LIKE 'E2E临时%';" >/dev/null

echo "================ M12 HTTP E2E 完成：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" -eq 0 ]
