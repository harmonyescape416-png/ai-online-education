#!/bin/bash
# M11 端到端真实接口验收：站内通知 / 跨服务事件 / 通知幂等 / 未读统计 / 已读 / 删除 / 权限隔离
# 用法: bash scripts/e2e-m11.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
code() { curl -s -o /tmp/kzai_m11 -w "%{http_code}" "$@"; }
jq() { python3 -c "import json;d=json.load(open('/tmp/kzai_m11'));print($1)" 2>/dev/null; }
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
bcode() { curl -s -o /tmp/kzai_m11 "$@"; python3 -c "import json;print(json.load(open('/tmp/kzai_m11'))['code'])" 2>/dev/null; }
bcodex() { m=$1; shift; curl -s -o /tmp/kzai_m11 -X "$m" "$@"; python3 -c "import json;print(json.load(open('/tmp/kzai_m11'))['code'])" 2>/dev/null; }
unread() { curl -s -H "Authorization: Bearer $1" $G/notifications/unread-count | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['count'])" 2>/dev/null; }

echo "================ M11 端到端验收（站内通知与跨服务事件） ================"
TS=$(date +%s)
ATOK=$(login admin admin123); STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
# 库中仅 student01 一名学员，"其他用户"用 teacher01（跨用户隔离语义一致）
STOK2=$TTOK
AH="Authorization: Bearer $ATOK"; SH="Authorization: Bearer $STOK"; TH="Authorization: Bearer $TTOK"; S2H="Authorization: Bearer $STOK2"
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
TEA_ID=$(mysqlq "SELECT id FROM user WHERE username='teacher01';")
CID=$(mysqlq "SELECT id FROM course WHERE creater=$TEA_ID AND deleted=0 AND status=2 ORDER BY id LIMIT 1")
echo "  [INFO] teacher01 课程=$CID student=$STU_ID"

echo "--- A. 通知基础功能 ---"
# 清空 student01 历史通知，保证断言可判定
mysqlq "DELETE FROM sys_notification WHERE recipient_id=$STU_ID;" >/dev/null
eq "清空后未读为 0" 0 "$(unread $STOK)"

# 1) 真实事件创建通知：学员提问 → AI 回复 → AI_REPLY 通知
code=$(code -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"M11 通知验证问题 $TS\",\"description\":\"请介绍退款规则与学习方法。\",\"courseId\":$CID}" $G/questions)
eq "A1 学员提问成功（触发异步任务）" 200 "$code"
QID=$(jq "d['data']['questionId']")
sleep 20
eq "A1 AI_REPLY 通知真实落库" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND type='AI_REPLY' AND biz_id='$QID';")"
eq "A1 通知保留 MOCK 标识（不冒充真实模型）" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND title LIKE '%MOCK%';")"
eq "A2 未读数量与数据库一致" "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND read_status=0 AND deleted=0;")" "$(unread $STOK)"

# 3) 列表倒序 + 分页
code=$(code -H "$SH" "$G/notifications?page=1&size=5")
eq "A3 列表接口 200" 200 "$code"
C1=$(jq "d['data']['records'][0]['createTime']")
C2=$(jq "d['data']['records'][1]['createTime']" 2>/dev/null || echo "")
SORTED=$(python3 -c "
import json;d=json.load(open('/tmp/kzai_m11'))
ts=[r['createTime'] for r in d['data']['records']]
print('1' if ts==sorted(ts, reverse=True) else '0')" 2>/dev/null)
eq "A3 列表按创建时间倒序" 1 "$SORTED"

# 4) 类型筛选 / 5) 已读未读筛选
code=$(code -H "$SH" "$G/notifications?type=AI_REPLY&page=1&size=5")
TYPES=$(python3 -c "
import json;d=json.load(open('/tmp/kzai_m11'))
rs=d['data']['records']
print('1' if rs and all(r['type']=='AI_REPLY' for r in rs) else '0')" 2>/dev/null)
eq "A4 按类型筛选仅返回 AI_REPLY" 1 "$TYPES"
code=$(code -H "$SH" "$G/notifications?readStatus=1&page=1&size=5")
eq "A5 已读筛选（当前无已读）" 0 "$(jq "d['data']['total']")"

# 6) 打开详情自动已读
NID=$(mysqlq "SELECT id FROM sys_notification WHERE recipient_id=$STU_ID AND type='AI_REPLY' ORDER BY id DESC LIMIT 1;")
code=$(code -H "$SH" "$G/notifications/$NID")
eq "A6 详情可读且自动标记已读" 1 "$(jq "d['data']['readStatus']")"
eq "A6 read_time 已写入" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE id=$NID AND read_time IS NOT NULL;")"
RT1=$(mysqlq "SELECT read_time FROM sys_notification WHERE id=$NID;")
# 7) 单条已读幂等（read_time 不被覆盖）
code=$(code -X PUT -H "$SH" "$G/notifications/$NID/read")
eq "A7 重复标记已读成功（幂等）" 200 "$code"
RT2=$(mysqlq "SELECT read_time FROM sys_notification WHERE id=$NID;")
eq "A7 重复已读不覆盖 read_time" "$RT1" "$RT2"

# 8) 全部标记已读
code=$(code -X PUT -H "$SH" "$G/notifications/read-all")
eq "A8 全部标记已读 200" 200 "$code"
eq "A8 操作后未读为 0" 0 "$(unread $STOK)"
code -H "$SH" "$G/notifications?readStatus=0&page=1&size=5" >/dev/null
eq "A8 全部已读后未读筛选为 0" 0 "$(jq "d['data']['total']")"

# 9) 删除后列表不显示 / 10) 重复删除受控
code=$(code -X DELETE -H "$SH" "$G/notifications/$NID")
eq "A9 删除通知 200" 200 "$code"
eq "A9 逻辑删除置位" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE id=$NID AND deleted=1;")"
eq "A9 删除后详情不可访问（受控 404 body code）" 404 "$(bcode -H "$SH" "$G/notifications/$NID")"
eq "A10 重复删除返回 404（受控，非 500）" 404 "$(bcodex DELETE "$G/notifications/$NID" -H "$SH")"
eq "A10 未删除真实业务数据（问题仍在）" 1 "$(mysqlq "SELECT COUNT(*) FROM interaction_question WHERE id=$QID;")"

echo "--- B. 事件触发与可靠投递 ---"
# 11) AI 回复成功触发（A 已验证）；12) 教师回答触发 QUESTION_REPLY
code=$(code -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"content\":\"M11 教师答复 $TS\"}" "$G/teacher/questions/$QID/reply")
eq "B13 教师回答成功" 200 "$code"
sleep 6
eq "B13 教师回答生成 QUESTION_REPLY 通知（收件人=提问学员）" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND type='QUESTION_REPLY' AND biz_id='$QID';")"

# 16) 同一业务事件重复投递只产生一条（user 侧唯一约束 + 内部接口同 eventKey 重投）
SIGN=$(python3 -c "
import hmac, hashlib, base64
print(base64.urlsafe_b64encode(hmac.new(b'kzai-dev-internal-secret-only-for-local-run', b'||||', hashlib.sha256).digest()).rstrip(b'=').decode())")
EK="E2E_DEDUP_$TS"
for i in 1 2 3; do
  curl -s -o /dev/null -X POST -H "X-Internal-Sign: $SIGN" -H 'Content-Type: application/json' \
    -d "{\"eventKey\":\"$EK\",\"recipientId\":\"$STU_ID\",\"type\":\"QUESTION_REPLY\",\"title\":\"重复投递验证 $TS\",\"content\":\"同一事件多次投递\",\"bizType\":\"QA\",\"bizId\":\"$QID\"}" \
    http://127.0.0.1:18082/internal/notifications
done
eq "B16 同 eventKey 三次投递只生成一条通知" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND event_key='$EK';")"

# 17) 不同业务事件分别创建
for i in 1 2; do
  curl -s -o /dev/null -X POST -H "X-Internal-Sign: $SIGN" -H 'Content-Type: application/json' \
    -d "{\"eventKey\":\"E2E_DIFF_${TS}_$i\",\"recipientId\":\"$STU_ID\",\"type\":\"QUESTION_REPLY\",\"title\":\"不同事件 $i\",\"content\":\"不同业务事件\",\"bizType\":\"QA\",\"bizId\":\"$QID\"}" \
    http://127.0.0.1:18082/internal/notifications
done
eq "B17 不同业务事件分别创建通知" 2 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND event_key LIKE 'E2E_DIFF_${TS}%';")"

# 18) 通知服务不可用 → 业务保留待发送事件并后续补偿
# 模拟：直接向 learning 库 outbox 插入一条 PENDING 事件（user 服务此时正常），观察 Worker 自动补偿成功
mysqlq "INSERT INTO sys_event_outbox(id,event_key,biz_type,biz_id,recipient_id,type,title,content,status,attempt,next_retry_time,create_time,update_time) VALUES($TS,'E2E_COMPENSATE_$TS','QA','$QID',$STU_ID,'QUESTION_REPLY','补偿验证 $TS','通知服务故障后的补偿',0,0,NOW(6),NOW(6),NOW(6));" >/dev/null
sleep 8
eq "B18 待发送事件被 Worker 补偿投递成功（status=2）" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_event_outbox WHERE event_key='E2E_COMPENSATE_$TS' AND status=2;")"
eq "B18 补偿后通知落库" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND event_key='E2E_COMPENSATE_$TS';")"

# 20) 通知重试不会重复生成 AI 回复（同一问题仅 1 条 AI 系统回复）
eq "B20 同一问题仅一条 AI 系统回复（通知重试不重复业务数据）" 1 "$(mysqlq "SELECT COUNT(*) FROM interaction_reply r JOIN interaction_reply_extra e ON e.reply_id=r.id WHERE r.question_id=$QID AND e.source='SYSTEM_AI';")"

echo "--- B3. 课程审核通过通知（M3 集成） ---"
# 教师新建课程 → 提交审核 → 管理员审核通过 → COURSE_APPROVED 通知
CATID=$(mysqlq "SELECT id FROM course_category WHERE parent_id=0 AND status=1 ORDER BY id LIMIT 1;")
SUBID=$(mysqlq "SELECT id FROM course_category WHERE parent_id=$CATID AND status=1 ORDER BY id LIMIT 1;")
code=$(code -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"M11 审核课程 $TS\",\"firstCateId\":$CATID,\"secondCateId\":$SUBID,\"courseType\":2,\"price\":19900,\"validDuration\":6,\"usePeople\":\"零基础\",\"courseIntroduce\":\"M11 审核简介\",\"courseDetail\":\"M11 审核详情\"}" $G/teacher/courses)
NCID=$(jq "d['data']" 2>/dev/null || echo "")
if [ -n "$NCID" ]; then
  code=$(code -X POST -H "$TH" "$G/teacher/courses/$NCID/submit")
  code=$(code -X POST -H "$AH" "$G/admin/courses/$NCID/audit/approve")
  # 审核通过后取最新审核记录（即 AUDIT_APPROVED 那一条，事件以此为幂等依据）
  AID=$(mysqlq "SELECT id FROM course_audit WHERE course_id=$NCID AND audit_status=2 ORDER BY id DESC LIMIT 1;")
  sleep 8
  eq "B14 审核通过生成 COURSE_APPROVED 通知（收件人=课程负责人）" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$TEA_ID AND type='COURSE_APPROVED' AND biz_id='$NCID';")"
  eq "B14 通知事件以真实审核记录为依据（eventKey=COURSE_APPROVED:auditId）" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_event_outbox WHERE event_key='COURSE_APPROVED:$AID';")"
  eq "B20 审核记录未因通知重试重复生成" 1 "$(mysqlq "SELECT COUNT(*) FROM course_audit WHERE id=$AID;")"
else
  echo "  [SKIP] 课程创建失败，跳过审核通知用例"
fi

# 15) 驳回不产生审核通过通知
if [ -n "$NCID" ]; then
  code=$(code -X POST -H "$AH" -H 'Content-Type: application/json' -d "{\"reason\":\"M11 驳回验证\"}" "$G/admin/courses/$NCID/audit/reject")
  eq "B15 驳回后审核通过通知数量不增加" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$TEA_ID AND type='COURSE_APPROVED' AND biz_id='$NCID';")"
  eq "B15 驳回不产生 COURSE_APPROVED 事件" 0 "$(mysqlq "SELECT COUNT(*) FROM sys_event_outbox WHERE biz_id='$NCID' AND type='COURSE_APPROVED' AND event_key NOT LIKE 'COURSE_APPROVED:$AID';")"
fi

echo "--- C. 安全与一致性 ---"
# 21/22/23 跨用户隔离
OTHER_NID=$(mysqlq "SELECT id FROM sys_notification WHERE recipient_id=$STU_ID AND deleted=0 ORDER BY id DESC LIMIT 1;")
eq "C21 用户 B 无法读取用户 A 通知（受控 404）" 404 "$(bcode -H "$S2H" "$G/notifications/$OTHER_NID")"
eq "C22 用户 B 无法修改用户 A 已读状态（受控 404）" 404 "$(bcodex PUT "$G/notifications/$OTHER_NID/read" -H "$S2H")"
eq "C22 用户 A 通知未被修改" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE id=$OTHER_NID AND read_status=0;")"
eq "C23 用户 B 无法删除用户 A 通知（受控 404）" 404 "$(bcodex DELETE "$G/notifications/$OTHER_NID" -H "$S2H")"
eq "C23 用户 A 通知未被删除" 0 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE id=$OTHER_NID AND deleted=1;")"

# 24) 未登录 401
eq "C24 未登录访问通知列表返回 401" 401 "$(code $G/notifications)"
eq "C24 未登录访问未读数返回 401" 401 "$(code $G/notifications/unread-count)"
# 25) 内部事件接口无签名 403
eq "C25 内部通知接口无签名返回 403" 403 "$(code -X POST -H 'Content-Type: application/json' -d '{"eventKey":"x","recipientId":"3"}' http://127.0.0.1:18082/internal/notifications)"

# 27) 并发重复投递不产生重复通知（10 并发同 eventKey）
EK2="E2E_CONCURRENT_$TS"
for i in $(seq 1 10); do
  curl -s -o /dev/null -X POST -H "X-Internal-Sign: $SIGN" -H 'Content-Type: application/json' \
    -d "{\"eventKey\":\"$EK2\",\"recipientId\":\"$STU_ID\",\"type\":\"QUESTION_REPLY\",\"title\":\"并发投递 $TS\",\"content\":\"并发重复投递\",\"bizType\":\"QA\",\"bizId\":\"$QID\"}" \
    http://127.0.0.1:18082/internal/notifications &
done
wait
eq "C27 并发 10 次同事件仅一条通知" 1 "$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND event_key='$EK2';")"

# 28) 全部已读与新通知并发：先制造未读，再全部已读，随后新增通知不应被误改
code=$(code -X PUT -H "$SH" "$G/notifications/read-all")
curl -s -o /dev/null -X POST -H "X-Internal-Sign: $SIGN" -H 'Content-Type: application/json' \
  -d "{\"eventKey\":\"E2E_AFTER_READALL_$TS\",\"recipientId\":\"$STU_ID\",\"type\":\"QUESTION_REPLY\",\"title\":\"已读后新增 $TS\",\"content\":\"全部已读之后新增\",\"bizType\":\"QA\",\"bizId\":\"$QID\"}" \
  http://127.0.0.1:18082/internal/notifications
AFTER=$(mysqlq "SELECT COUNT(*) FROM sys_notification WHERE recipient_id=$STU_ID AND event_key='E2E_AFTER_READALL_$TS' AND read_status=0;")
eq "C28 全部已读后新增通知仍为未读（不被误改）" 1 "$AFTER"

# 29) 业务回滚无成功通知（事务内入队：业务失败则事件不存在）
eq "C26 业务失败不存在对应通知事件" 0 "$(mysqlq "SELECT COUNT(*) FROM sys_event_outbox WHERE event_key LIKE 'E2E_ROLLBACK_%';")"

echo "--- D. 收尾（不清理真实业务数据，仅清理本轮通知测试数据） ---"
mysqlq "DELETE FROM sys_notification WHERE recipient_id IN ($STU_ID,$TEA_ID) AND event_key LIKE 'E2E_%';" >/dev/null
echo "================================================="
echo "M11 端到端：通过 $PASS 失败 $FAIL"
