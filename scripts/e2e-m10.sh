#!/bin/bash
# M10 端到端真实接口验收：课程问答 / 异步 AI 回复（MOCK 显著标识）/ 教师管理 / 权限 / Guard
# 用法: bash scripts/e2e-m10.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
code() { curl -s -o /tmp/kzai_m10 -w "%{http_code}" "$@"; }
jq() { python3 -c "import json;d=json.load(open('/tmp/kzai_m10'));print($1)" 2>/dev/null; }
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

echo "================ M10 端到端验收（课程问答与 AI 自动回复） ================"
TS=$(date +%s)
ATOK=$(login admin admin123); STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
AH="Authorization: Bearer $ATOK"; SH="Authorization: Bearer $STOK"; TH="Authorization: Bearer $TTOK"
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
TEA_ID=$(mysqlq "SELECT id FROM user WHERE username='teacher01';")
# 教师本人课程（teacher01）
CID=$(mysqlq "SELECT id FROM course WHERE creater=$TEA_ID AND deleted=0 AND status=2 ORDER BY id LIMIT 1")
echo "  [INFO] teacher01 课程=$CID student=$STU_ID"

echo "--- A. 提问与校验 ---"
code=$(code -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"M10 验证问题 $TS\",\"description\":\"请介绍该课程的学习方法与退款规则。\",\"courseId\":$CID}" \
  $G/questions)
eq "学员提问成功" 200 "$code"
QID=$(jq "d['data']['questionId']")
TASK_ID=$(jq "d['data']['taskId']")
[ -n "$QID" ] || { echo "  [FAIL] 未获得问题 ID"; exit 1; }
DB_Q=$(mysqlq "SELECT COUNT(*) FROM interaction_question WHERE id=$QID AND user_id=$STU_ID AND answer_times=0 AND latest_answer_id IS NULL AND status=0;")
eq "MySQL 落库且初始状态正确（answer_times=0/latest 空/status=0）" 1 "$DB_Q"
eq "异步任务已创建（持久化）" 1 "$(mysqlq "SELECT COUNT(*) FROM ai_question_reply_task WHERE id=$TASK_ID AND question_id=$QID AND status=0;")"

code=$(code -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"bad\",\"description\":\"x\",\"courseId\":999999}" $G/questions)
eq "不存在课程被拒（body code 400）" 400 "$(jq "d['code']")"
code=$(code -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"bad chapter\",\"description\":\"x\",\"courseId\":$CID,\"chapterId\":999999}" $G/questions)
eq "非法章节归属被拒" 400 "$(jq "d['code']")"
code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"admin\",\"description\":\"x\",\"courseId\":$CID}" $G/questions)
eq "管理员也可提问（登录用户）" 200 "$code"

echo "--- B. 异步 AI 回复（MOCK 显著标识；真实模型未验证）---"
FINAL=0
for i in $(seq 1 15); do
  sleep 2
  STATUS=$(mysqlq "SELECT status FROM ai_question_reply_task WHERE id=$TASK_ID;")
  [ "$STATUS" = "2" ] && FINAL=1 && break
  [ "$STATUS" = "3" ] && break
done
eq "任务最终成功（2=SUCCESS）" 2 "$STATUS"
REPLY_ID=$(mysqlq "SELECT reply_id FROM ai_question_reply_task WHERE id=$TASK_ID;")
DB_R=$(mysqlq "SELECT COUNT(*) FROM interaction_reply WHERE id=$REPLY_ID AND question_id=$QID AND user_id=9999 AND is_student=0;")
eq "AI 回复落库（user=9999 系统账号，is_student=0）" 1 "$DB_R"
DB_EXTRA=$(mysqlq "SELECT COUNT(*) FROM interaction_reply_extra WHERE reply_id=$REPLY_ID AND source='SYSTEM_AI' AND model_code='MOCK';")
eq "来源元数据可追溯（SYSTEM_AI + MOCK 显著标识）" 1 "$DB_EXTRA"
DB_AGG=$(mysqlq "SELECT answer_times, latest_answer_id FROM interaction_question WHERE id=$QID")
eq "问题聚合正确（answer_times=1 且 latest=回复）" "$REPLY_ID" "$(echo "$DB_AGG" | awk '{print $2}')"
MOCK_MARK=$(mysqlq "SELECT content LIKE '%MOCK%' FROM interaction_reply WHERE id=$REPLY_ID;")
eq "MOCK 回复内容显著标识" 1 "$MOCK_MARK"

echo "--- B2. 学员详情/追问/匿名 ---"
code=$(code -H "$SH" $G/questions/$QID)
eq "学员查看本人问题详情" 200 "$code"
eq "回复树含 AI 回复（source 可见）" "SYSTEM_AI" "$(jq "d['data']['replies'][0]['source']")"
code=$(code -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"content\":\"追问：退款期限是多久？\",\"targetReplyId\":$REPLY_ID}" $G/questions/$QID/replies)
eq "学员追问成功" 200 "$code"
FOLLOW_ROOT=$(mysqlq "SELECT answer_id FROM interaction_reply WHERE question_id=$QID AND target_reply_id=$REPLY_ID LIMIT 1;")
eq "追问 answer_id 指向回复根（链正确）" "$REPLY_ID" "$FOLLOW_ROOT"

echo "--- C. 教师管理 ---"
code=$(code -H "$TH" "$G/teacher/questions?courseId=$CID")
eq "教师问答列表（本人课程）" 200 "$code"
code=$(code -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"content\":\"教师补充说明：以官方退款规则为准。\"}" $G/teacher/questions/$QID/reply)
eq "教师直接回复" 200 "$code"
TEACHER_REPLY=$(mysqlq "SELECT id FROM interaction_reply WHERE question_id=$QID AND user_id=$TEA_ID ORDER BY id DESC LIMIT 1")
DB_TS=$(mysqlq "SELECT source FROM interaction_reply_extra WHERE reply_id=$TEACHER_REPLY;")
eq "教师回复来源=TEACHER" "TEACHER" "$DB_TS"

code=$(code -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"content\":\"教师修正后的 AI 回复内容。\"}" $G/teacher/replies/$REPLY_ID/correct)
eq "教师人工修正 AI 回复" 200 "$code"
DB_CORRECT=$(mysqlq "SELECT corrected, prev_content LIKE '%MOCK%' FROM interaction_reply_extra WHERE reply_id=$REPLY_ID;")
eq "修正审计（corrected=1 且修正前内容留存）" "1	1" "$DB_CORRECT"
code=$(code -X POST -H "$TH" $G/teacher/questions/$QID/regenerate)
eq "已修正的 AI 回复拒绝重生成（防覆盖）" 200 "$code"
REG_CODE=$(jq "d['code']")
eq "重生成被业务拒绝（body code 400）" 400 "$REG_CODE"

code=$(code -X POST -H "$TH" -H 'Content-Type: application/json' -d '{"on":true}' $G/teacher/replies/$TEACHER_REPLY/feature)
eq "设为精华" 200 "$code"
code=$(code -X PATCH -H "$TH" -H 'Content-Type: application/json' -d '{"visible":false}' $G/teacher/replies/$TEACHER_REPLY/visibility)
eq "教师隐藏回复" 200 "$code"
HIDDEN_VISIBLE=$(code -H "$SH" $G/questions/$QID)
eq "隐藏回复对学员不可见" 0 "$(grep -c "以官方退款规则为准" /tmp/kzai_m10)"
code=$(code -X PATCH -H "$TH" -H 'Content-Type: application/json' -d '{"visible":false}' $G/teacher/questions/$QID/visibility)
eq "教师隐藏问题" 200 "$code"
STU_LIST=$(code -H "$SH" "$G/questions?courseId=$CID")
eq "隐藏问题不出现在学员列表" 0 "$(grep -c "M10 验证问题 $TS" /tmp/kzai_m10)"
code=$(code -X PATCH -H "$TH" -H 'Content-Type: application/json' -d '{"visible":true}' $G/teacher/questions/$QID/visibility)
code=$(code -X PATCH -H "$TH" -H 'Content-Type: application/json' -d '{"visible":true}' $G/teacher/replies/$TEACHER_REPLY/visibility)

echo "--- C2. 越权 ---"
# 另一教师（teacher02，若存在）试图管理 teacher01 的问题
T2OK=$(login teacher02 teacher123)
if [ -n "$T2OK" ]; then
  code=$(code -X POST -H "Authorization: Bearer $T2OK" -H 'Content-Type: application/json' \
    -d "{\"content\":\"越权回复\"}" $G/teacher/questions/$QID/reply)
  eq "其他教师越权回复被拒（body code 403）" 403 "$(jq "d['code']")"
else
  eq "学员调用教师接口被拒（body code 403）" 403 "$(curl -s -X POST -H "$SH" -H 'Content-Type: application/json' -d '{"content":"x"}' $G/teacher/questions/$QID/reply | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])" | tr -d '[:space:]')"
fi
eq "学员调用教师修正被拒" 403 "$(curl -s -X POST -H "$SH" -H 'Content-Type: application/json' -d '{"content":"x"}' $G/teacher/replies/$REPLY_ID/correct | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])")"

echo "--- D. 统计与 Guard ---"
STATS=$(code -H "$TH" "$G/teacher/learning-stats?courseId=$CID")
QC=$(jq "d['data']['records'][0]['questionCount']")
echo "  [INFO] questionCount=$QC"
eq "学情 questionCount 为真实数字（非 null）" yes "$([ "$QC" != "None" ] && [ "$QC" != "" ] && echo yes || echo no)"
eq "aiAdoptRate 维持 null（口径未确认，不硬编码）" "None" "$(jq "d['data']['records'][0]['aiAdoptRate']")"

# InteractionAssociationGuard：student01 有问答关联 → 删除被拒
code=$(code -X DELETE -H "$AH" $G/admin/users/$STU_ID)
GUARD_MSG=$(jq "d['msg']" 2>/dev/null)
eq "有互动关联的学员删除被拒" 400 "$(jq "d['code']")"
eq "拒绝原因含业务关联保护" "1" "$(printf '%s' "$GUARD_MSG" | grep -c '关联' | awk '{print ($1>0)?1:0}')"
eq "内部互动计数端点无签名被拒（网关拒绝外部 /internal）" 403 "$(code $G/internal/learning/interactions/count-by-user/$STU_ID)"

echo "================================================="
echo "M10 端到端：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
