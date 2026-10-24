#!/bin/bash
# M4 端到端真实接口验收（经网关 18080，真实 MySQL + 微服务，无 Mock）
# 覆盖：预下单、幂等与并发、已购校验、订单查询/取消、越权、删除保护、付费资源鉴权、重启持久化
# 用法: bash scripts/e2e-m4.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_m4 -w "%{http_code}" "$@"; }
json() { python3 -c "import json;d=json.load(open('/tmp/kzai_m4'));print($1)" 2>/dev/null; }
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

echo "================ M4 端到端验收 ================"
TS=$(date +%s)
A=$(login admin admin123); T=$(login teacher01 teacher123); S=$(login student01 student123)
AH="Authorization: Bearer $A"; TH="Authorization: Bearer $T"; SH="Authorization: Bearer $S"
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")

# ---- 准备：自建分类 + 付费课程（上架） ----
mkcat() { curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d "$1" $G/admin/categories | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null; }
CAT=$(mkcat "{\"name\":\"M4分类$TS\",\"parentId\":0,\"sort\":9,\"status\":1}")
CAT2=$(mkcat "{\"name\":\"M4子分类$TS\",\"parentId\":$CAT,\"sort\":1,\"status\":1}")
mkcourse() { curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "$1" $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null; }
C1=$(mkcourse "{\"name\":\"M4付费课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"199.00\",\"validDuration\":12}")
C2=$(mkcourse "{\"name\":\"M4免费课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"0.00\",\"validDuration\":6}")
for c in $C1 $C2; do
  curl -s -X POST -H "$TH" $G/teacher/courses/$c/submit >/dev/null
  curl -s -X POST -H "$AH" $G/admin/courses/$c/audit/approve >/dev/null
done
eq "付费课程已上架" 2 "$(mysqlq "SELECT status FROM course WHERE id=$C1;")"
eq "价格按元精确入库（199.00 元 = 19900 分）" 19900 "$(mysqlq "SELECT price FROM course WHERE id=$C1;")"

echo "--- 1~3. 预下单与金额 ---"
RID="m4-$TS"
biz "学员预下单成功" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C1],\"requestId\":\"$RID\"}" $G/orders/pre-place
OID=$(json "d['data']['orderId']")
eq "订单状态为待支付" 1 "$(mysqlq "SELECT status FROM \`order\` WHERE id=$OID;")"
eq "订单金额与课程实时价格一致" 19900 "$(mysqlq "SELECT total_amount FROM \`order\` WHERE id=$OID;")"
eq "订单明细已写入" 1 "$(mysqlq "SELECT COUNT(*) FROM order_detail WHERE order_id=$OID;")"
eq "明细保存课程名称快照" "M4付费课$TS" "$(mysqlq "SELECT name FROM order_detail WHERE order_id=$OID;")"
eq "明细状态为待支付" 1 "$(mysqlq "SELECT status FROM order_detail WHERE order_id=$OID;")"

echo "--- 4~6. 幂等与并发 ---"
biz "同一 requestId 重复提交" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C1],\"requestId\":\"$RID\"}" $G/orders/pre-place
eq "重复提交返回同一订单" "$OID" "$(json "d['data']['orderId']")"
eq "幂等命中标记为 true" "True" "$(json "d['data']['idempotentHit']")"
eq "订单总数仍为 1 笔" 1 "$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE id=$OID;")"
biz "同 requestId 不同内容 → 冲突" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C1,$C2],\"requestId\":\"$RID\"}" $G/orders/pre-place
eq "冲突提示明确" yes "$(msg | grep -q 'requestId' && echo yes || echo no)"

# 并发：5 个并发请求同一 requestId
CRID="m4c-$TS"
for i in 1 2 3 4 5; do
  curl -s -X POST -H "$SH" -H 'Content-Type: application/json' \
    -d "{\"courseIds\":[$C2],\"requestId\":\"$CRID\"}" $G/orders/pre-place >/dev/null &
done
wait
eq "并发重复提交只产生 1 笔订单" 1 "$(mysqlq "SELECT COUNT(*) FROM \`order\` o JOIN order_request_idempotent r ON r.order_id=o.id WHERE r.request_id='$CRID';")"
eq "并发后幂等记录仅 1 条" 1 "$(mysqlq "SELECT COUNT(*) FROM order_request_idempotent WHERE request_id='$CRID';")"
eq "并发订单明细数与课程数一致" 1 "$(mysqlq "SELECT COUNT(*) FROM order_detail WHERE order_id=(SELECT order_id FROM order_request_idempotent WHERE request_id='$CRID');")"

echo "--- 7~9. 课程状态与合法性 ---"
C3=$(mkcourse "{\"name\":\"M4下架课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"99.00\"}")
curl -s -X POST -H "$TH" $G/teacher/courses/$C3/submit >/dev/null
curl -s -X POST -H "$AH" $G/admin/courses/$C3/audit/approve >/dev/null
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"reason":"M4核验下架"}' $G/admin/courses/$C3/offline >/dev/null
biz "已下架课程不能下单" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C3],\"requestId\":\"m4off-$TS\"}" $G/orders/pre-place
eq "下架提示明确" yes "$(msg | grep -q '不可购买' && echo yes || echo no)"
eq "失败下单未产生订单" 0 "$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE message LIKE '%下架课%';")"
curl -s -X PUT -H "$AH" "$G/admin/categories/$CAT/status?status=0" >/dev/null
biz "分类禁用后不能购买" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C2],\"requestId\":\"m4cat-$TS\"}" $G/orders/pre-place
eq "分类禁用提示明确" yes "$(msg | grep -q '不可购买\|分类' && echo yes || echo no)"
curl -s -X PUT -H "$AH" "$G/admin/categories/$CAT/status?status=1" >/dev/null
curl -s -X PUT -H "$AH" "$G/admin/categories/$CAT2/status?status=1" >/dev/null
biz "不存在的课程不能购买" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[999999999999],\"requestId\":\"m4bad-$TS\"}" $G/orders/pre-place

echo "--- 10. 事务回滚 ---"
before_order=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID;")
before_req=$(mysqlq "SELECT COUNT(*) FROM order_request_idempotent WHERE user_id=$STU_ID;")
biz "非法参数（空课程列表）被拒" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[],\"requestId\":\"m4empty-$TS\"}" $G/orders/pre-place
eq "失败后订单数未变化" "$before_order" "$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID;")"
eq "未留下孤立幂等记录" "$before_req" "$(mysqlq "SELECT COUNT(*) FROM order_request_idempotent WHERE user_id=$STU_ID;")"

echo "--- 11~12. 查询、越权与取消 ---"
biz "学员查询我的订单" 200 -H "$SH" "$G/orders?pageNum=1&pageSize=10"
eq "列表仅包含本人订单" yes "$(python3 -c "
import json
d=json.load(open('/tmp/kzai_m4'))['data']
print('yes' if all(r['userId']=='$STU_ID' for r in d['records']) else 'no')")"
biz "按状态筛选待支付" 200 -H "$SH" "$G/orders?pageNum=1&pageSize=10&status=1"
eq "筛选结果均为待支付" yes "$(python3 -c "
import json
d=json.load(open('/tmp/kzai_m4'))['data']
print('yes' if all(r['status']==1 for r in d['records']) else 'no')")"
biz "订单详情可查" 200 -H "$SH" $G/orders/$OID

# 另一个学员越权
S2_USER="m4stu_$TS"; S2_PHONE="139${TS: -8}"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$S2_USER\",\"cellPhone\":\"$S2_PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
S2=$(login "$S2_USER" 'E2e@123456'); S2H="Authorization: Bearer $S2"
http "他人查询订单被拒" 403 -H "$S2H" $G/orders/$OID
http "他人取消订单被拒" 403 -X POST -H "$S2H" $G/orders/$OID/cancel

biz "取消待支付订单" 200 -X POST -H "$SH" $G/orders/$OID/cancel
eq "订单状态变为已关闭" 3 "$(mysqlq "SELECT status FROM \`order\` WHERE id=$OID;")"
eq "关闭时间已记录" 1 "$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE id=$OID AND close_time IS NOT NULL;")"
eq "明细状态同步为已关闭" 3 "$(mysqlq "SELECT status FROM order_detail WHERE order_id=$OID;")"
biz "重复取消按幂等处理" 200 -X POST -H "$SH" $G/orders/$OID/cancel
eq "重复取消提示幂等" yes "$(json "d['data']" | grep -q '幂等' && echo yes || echo no)"

echo "--- 13. 已支付课程禁止重复购买（SEED 演示已购数据） ---"
# 说明：本阶段不实现支付，以下为明确标注的演示已购数据（非真实付款记录）
C4=$(mkcourse "{\"name\":\"M4已购课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"299.00\"}")
curl -s -X POST -H "$TH" $G/teacher/courses/$C4/submit >/dev/null
curl -s -X POST -H "$AH" $G/admin/courses/$C4/audit/approve >/dev/null
~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -e "
INSERT INTO \`order\` (id, user_id, status, message, total_amount, real_amount, discount_amount, create_time, update_time, deleted)
VALUES ($TS, $STU_ID, 2, 'SEED-DEMO 演示已支付订单（非真实支付）', 29900, 29900, 0, NOW(), NOW(), 0);
INSERT INTO order_detail (id, order_id, user_id, course_id, price, name, cover_url, valid_duration, discount_amount, real_pay_amount, status, create_time, update_time)
VALUES ($TS, $TS, $STU_ID, $C4, 29900, 'M4已购课$TS', NULL, 12, 0, 29900, 2, NOW(), NOW());" 2>/dev/null
biz "已支付课程重复购买被拒" 400 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C4],\"requestId\":\"m4dup-$TS\"}" $G/orders/pre-place
eq "重复购买提示明确" yes "$(msg | grep -q '已购买' && echo yes || echo no)"
biz "已支付订单不能取消" 400 -X POST -H "$SH" $G/orders/$TS/cancel
eq "取消已支付提示明确" yes "$(msg | grep -q '仅待支付' && echo yes || echo no)"

echo "--- 14. 禁用账号 / 失效 Token 不能下单 ---"
curl -s -X PUT -H "$AH" -H 'Content-Type: application/json' -d '{"status":0}' $G/admin/users/$STU_ID/status >/dev/null
http "禁用账号后下单被拒" 401 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C2],\"requestId\":\"m4dis-$TS\"}" $G/orders/pre-place
curl -s -X PUT -H "$AH" -H 'Content-Type: application/json' -d '{"status":1}' $G/admin/users/$STU_ID/status >/dev/null
S3=$(login student01 student123)
http "启用后新 Token 可下单" 200 -X POST -H "Authorization: Bearer $S3" -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C2],\"requestId\":\"m4ok-$TS\"}" $G/orders/pre-place
http "未登录下单被拒" 401 -X POST -H 'Content-Type: application/json' \
  -d "{\"courseIds\":[$C2],\"requestId\":\"m4anon-$TS\"}" $G/orders/pre-place

echo "--- 15. 用户删除关联保护 ---"
biz "存在订单的用户删除被拒" 400 -X DELETE -H "$AH" $G/admin/users/$STU_ID
eq "拒绝原因与关联校验相关" yes "$(msg | grep -qE '关联|校验服务' && echo yes || echo no)"
eq "用户未被删除" 1 "$(mysqlq "SELECT COUNT(*) FROM user WHERE id=$STU_ID;")"

echo "--- 19. 付费资源下载鉴权 ---"
# 上一步曾禁用/启用账号，Token 版本已变更，需重新登录
S=$(login student01 student123); SH="Authorization: Bearer $S"
printf '\x89PNG\r\n\x1a\nm4' > /tmp/kzai_m4.png
UPLOAD_ID=$(curl -s -X POST -H "$TH" -F "file=@/tmp/kzai_m4.png" $G/files/upload | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$UPLOAD_ID,\"kind\":2}" $G/teacher/courses/$C4/resources >/dev/null
FREE_ID=$(curl -s -X POST -H "$TH" -F "file=@/tmp/kzai_m4.png" $G/files/upload | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$FREE_ID,\"kind\":2}" $G/teacher/courses/$C2/resources >/dev/null
http "免费课程资源允许登录学员下载" 200 -H "$SH" $G/files/$FREE_ID/download
http "已购学员可下载付费课程资源" 200 -H "Authorization: Bearer $S3" $G/files/$UPLOAD_ID/download
http "未购学员下载付费课程资源被拒" 403 -H "$S2H" $G/files/$UPLOAD_ID/download

echo "--- 17. 服务重启后数据仍存在 ---"
source scripts/dev-env.sh >/dev/null 2>&1
bash scripts/run-services.sh stop >/dev/null 2>&1; sleep 3
bash scripts/run-services.sh start >/dev/null 2>&1
for i in $(seq 1 60); do
  up=$(for p in 18080 18081 18082 18083 18084; do lsof -nP -iTCP:$p -sTCP:LISTEN 2>/dev/null | grep -q LISTEN && printf "1" || printf "0"; done)
  [ "$up" = "11111" ] && break; sleep 2
done
eq "重启后订单仍存在" 1 "$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE id=$OID;")"
eq "重启后明细仍存在" 1 "$(mysqlq "SELECT COUNT(*) FROM order_detail WHERE order_id=$OID;")"
for i in $(seq 1 20); do S4=$(login student01 student123); [ -n "$S4" ] && break; sleep 3; done
http "重启后可查询订单列表" 200 -H "Authorization: Bearer $S4" "$G/orders?pageNum=1&pageSize=10"
http "重启后可查询订单详情" 200 -H "Authorization: Bearer $S4" $G/orders/$OID

echo "=============================================="
echo "通过: $PASS   失败: $FAIL"
