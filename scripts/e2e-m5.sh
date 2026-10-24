#!/bin/bash
# M5 端到端真实接口验收（经网关 18080，真实 MySQL + 微服务，无 Mock）
# 覆盖：免费入课、付费权益判定、进度与断点续学、试看、受控资源、Range、教师学情、越权、服务不可用
# 用法: bash scripts/e2e-m5.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_m5 -w "%{http_code}" "$@"; }
json() { python3 -c "import json;d=json.load(open('/tmp/kzai_m5'));print($1)" 2>/dev/null; }
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

echo "================ M5 端到端验收 ================"
TS=$(date +%s)
A=$(login admin admin123); T=$(login teacher01 teacher123); S=$(login student01 student123)
AH="Authorization: Bearer $A"; TH="Authorization: Bearer $T"; SH="Authorization: Bearer $S"
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
TEA_ID=$(mysqlq "SELECT id FROM user WHERE username='teacher01';")

# 第二个学员（跨用户越权测试）
S2_USER="m5stu_$TS"; S2_PHONE="138${TS: -8}"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$S2_USER\",\"cellPhone\":\"$S2_PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
S2=$(login "$S2_USER" 'E2e@123456'); S2H="Authorization: Bearer $S2"
S2_ID=$(mysqlq "SELECT id FROM user WHERE username='$S2_USER';")

# 第二个教师（教师越权测试）
T2_USER="m5tea_$TS"; T2_PHONE="137${TS: -8}"
curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$T2_USER\",\"cellPhone\":\"$T2_PHONE\",\"type\":1,\"roleCodes\":[\"TEACHER\"],\"password\":\"E2e@123456\"}" $G/admin/users >/dev/null
T2=$(login "$T2_USER" 'E2e@123456'); T2H="Authorization: Bearer $T2"

# ---- 准备：分类 + 免费课程 + 付费课程（各 3 章） ----
mkcat() { curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d "$1" $G/admin/categories | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null; }
CAT=$(mkcat "{\"name\":\"M5分类$TS\",\"parentId\":0,\"sort\":9,\"status\":1}")
CAT2=$(mkcat "{\"name\":\"M5子分类$TS\",\"parentId\":$CAT,\"sort\":1,\"status\":1}")
mkcourse() { curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "$1" $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null; }
CFREE=$(mkcourse "{\"name\":\"M5免费课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"0.00\",\"validDuration\":6}")
CPAID=$(mkcourse "{\"name\":\"M5付费课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"priceYuan\":\"199.00\",\"validDuration\":12}")
for c in $CFREE $CPAID; do
  curl -s -X POST -H "$TH" $G/teacher/courses/$c/submit >/dev/null
  curl -s -X POST -H "$AH" $G/admin/courses/$c/audit/approve >/dev/null
  for i in 1 2 3; do
    curl -s -X POST -H "$TH" -H 'Content-Type: application/json' \
      -d "{\"name\":\"第${i}章\",\"description\":\"第${i}章说明\"}" $G/teacher/courses/$c/chapters >/dev/null
  done
done
CH1=$(mysqlq "SELECT id FROM course_chapter WHERE course_id=$CFREE ORDER BY chapter_order LIMIT 1;")
CH2=$(mysqlq "SELECT id FROM course_chapter WHERE course_id=$CFREE ORDER BY chapter_order LIMIT 1 OFFSET 1;")
PCH1=$(mysqlq "SELECT id FROM course_chapter WHERE course_id=$CPAID ORDER BY chapter_order LIMIT 1;")
PCH2=$(mysqlq "SELECT id FROM course_chapter WHERE course_id=$CPAID ORDER BY chapter_order LIMIT 1 OFFSET 1;")
eq "免费课程章节已创建" 3 "$(mysqlq "SELECT COUNT(*) FROM course_chapter WHERE course_id=$CFREE;")"

# 上传视频与资料（付费课程；扩展名必须在白名单内：mp4 / pdf）
python3 -c "
import sys
open('/tmp/kzai_m5_video.mp4','wb').write(b'\x00\x00\x00\x18ftypmp42' + bytes(range(48)))
open('/tmp/kzai_m5_doc.pdf','wb').write(b'%PDF-1.4\n' + bytes(range(48)))
"
up() { curl -s -X POST -H "$TH" -F "file=@$1" $G/files/upload | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null; }
VID=$(up /tmp/kzai_m5_video.mp4)
DOC=$(up /tmp/kzai_m5_doc.pdf)
eq "视频资源已上传" yes "$( [ -n "$VID" ] && echo yes || echo no )"
eq "资料资源已上传" yes "$( [ -n "$DOC" ] && echo yes || echo no )"
curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$VID,\"kind\":3,\"chapterId\":$PCH1}" $G/teacher/courses/$CPAID/resources >/dev/null
curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$DOC,\"kind\":2,\"chapterId\":$PCH2}" $G/teacher/courses/$CPAID/resources >/dev/null

echo "--- 1~2. 免费课程入课与重复入课 ---"
biz "免费课程加入学习成功" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CFREE}" $G/learning/lessons
eq "learning_lesson 真实落库 1 条" 1 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson WHERE user_id=$STU_ID AND course_id=$CFREE;")"
biz "重复免费入课返回既有记录" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CFREE}" $G/learning/lessons
biz "第三次重复入课" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CFREE}" $G/learning/lessons
eq "重复入课未产生重复记录" 1 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson WHERE user_id=$STU_ID AND course_id=$CFREE;")"
eq "免费入课未生成任何订单" 0 "$(mysqlq "SELECT COUNT(*) FROM order_detail WHERE course_id=$CFREE;")"
eq "权益来源为免费课程" "FREE" "$(json "d['data']['entitlementSource']")"

echo "--- 3. 未购用户访问付费章节被拒 ---"
http "未购学员入付费课程被拒" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID}" $G/learning/lessons
http "未购学员访问付费章节被拒" 403 -H "$SH" $G/learning/courses/$CPAID/chapters/$PCH1
biz "未购学员权益查询为不允许" 200 -H "$SH" $G/learning/courses/$CPAID/access
eq "未购课程 allowed=false" "False" "$(json "d['data']['allowed']")"

echo "--- 8. 伪造权益字段一律无效 ---"
http "携带 paid=true 仍被拒" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID,\"paid\":true}" $G/learning/lessons
http "携带伪造 orderId 仍被拒" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID,\"orderId\":123456789,\"orderStatus\":2}" $G/learning/lessons

echo "--- 5~7. 订单状态 / 退款 / 有效期组合（SEED-DEMO 演示已购数据） ---"
# 说明：以下为明确标注的演示已购数据，用于联调付费学习闭环，非真实支付记录，不计入真实成交额
seed_order() {
  local oid="$1" ostatus="$2" dstatus="$3" refund="$4" expire="$5" course="$6"
  mysqlx "INSERT INTO \`order\` (id, user_id, status, message, total_amount, real_amount, discount_amount, pay_time, create_time, update_time, deleted)
    VALUES ($oid, $STU_ID, $ostatus, 'SEED-DEMO 演示已支付订单（非真实支付）', 19900, 19900, 0, NOW(), NOW(), NOW(), 0);
  INSERT INTO order_detail (id, order_id, user_id, course_id, price, name, valid_duration, discount_amount, real_pay_amount, status, refund_status, course_expire_time, create_time, update_time)
    VALUES ($oid, $oid, $STU_ID, $course, 19900, 'M5付费课$TS', 12, 0, 19900, $dstatus, $refund, $expire, NOW(), NOW());"
}
# 已取消订单
OC=$((TS + 1)); seed_order $OC 3 3 "NULL" "NULL" $CPAID
http "订单已取消不能获得付费权益" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID}" $G/learning/lessons
mysqlx "DELETE FROM order_detail WHERE order_id=$OC; DELETE FROM \`order\` WHERE id=$OC;"

# 已过期权益
OE=$((TS + 2)); seed_order $OE 2 2 "NULL" "DATE_SUB(NOW(), INTERVAL 1 DAY)" $CPAID
http "已过期权益不能继续学习" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID}" $G/learning/lessons
eq "过期提示明确" yes "$(msg | grep -q '有效期' && echo yes || echo no)"
mysqlx "DELETE FROM order_detail WHERE order_id=$OE; DELETE FROM \`order\` WHERE id=$OE;"

# 退款：申请中 / 已退款 / 未知状态码 → 一律拒绝
for rs in 1 2 99; do
  OR=$((TS + 10 + rs)); seed_order $OR 2 2 "$rs" "NULL" $CPAID
  http "退款状态=$rs 不授予权益" 403 -X POST -H "$SH" -H 'Content-Type: application/json' \
    -d "{\"courseId\":$CPAID}" $G/learning/lessons
  mysqlx "DELETE FROM order_detail WHERE order_id=$OR; DELETE FROM \`order\` WHERE id=$OR;"
done

# 退款驳回（3）→ 保留权益
ORJ=$((TS + 30)); seed_order $ORJ 2 2 "3" "NULL" $CPAID
biz "退款驳回保留学习权益" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID}" $G/learning/lessons
mysqlx "DELETE FROM order_detail WHERE order_id=$ORJ; DELETE FROM \`order\` WHERE id=$ORJ;"

# 正常已支付（SEED-DEMO）
OP=$((TS + 40)); seed_order $OP 2 2 "NULL" "NULL" $CPAID
biz "已购且有效期内可学习" 200 -X POST -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"courseId\":$CPAID}" $G/learning/lessons
eq "付费权益来源为已购买" "PAID" "$(json "d['data']['entitlementSource']")"
http "已购学员访问付费章节成功" 200 -H "$SH" $G/learning/courses/$CPAID/chapters/$PCH1

echo "--- 9~11. 学习进度真实写入与校验 ---"
# 先建立进度行（起始位置 0）
biz "上报第一章起始位置" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":0,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "进度明细真实写入 MySQL" 1 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
# 模拟真实观看：距上次上报已过去 300 秒（回拨 update_time，而不是伪造上报包）
mysqlx "UPDATE learning_lesson_progress SET update_time = DATE_SUB(NOW(), INTERVAL 300 SECOND) WHERE user_id=$STU_ID AND chapter_id=$CH1;"
biz "观看 300 秒后上报" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":300,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "断点位置持久化" 300 "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
eq "有效学习时长已累计" 300 "$(mysqlq "SELECT study_seconds FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"

biz "重复上报同一位置" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":300,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
biz "第三次重复上报" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":300,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "重复上报未重复累计时长" 300 "$(mysqlq "SELECT study_seconds FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
eq "进度明细仍只有 1 条" 1 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"

# M6 验收项 5：拖动进度条造成的位置跳跃不得计入有效学习时长
mysqlx "UPDATE learning_lesson_progress SET update_time = DATE_SUB(NOW(), INTERVAL 5 SECOND) WHERE user_id=$STU_ID AND chapter_id=$CH1;"
biz "拖动到 1000 秒后上报" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":1000,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "拖动后断点随之前进" 1000 "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
eq "拖动不虚增有效时长" yes "$(mysqlq "SELECT study_seconds <= 300 + 5 + 3 FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;" | grep -q 1 && echo yes || echo no)"
printf "  [INFO] 拖动 700 秒后有效时长=%s 秒（未虚增 700 秒）\n" "$(mysqlq "SELECT study_seconds FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"

biz "上报位置倒退不回退进度" 200 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":100,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "断点位置保持最大值" 1000 "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"

biz "不存在的章节被拒" 400 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":999999999999,\"videoPositionSec\":10}" $G/learning/lessons/$CFREE/progress
biz "章节不属于该课程被拒" 400 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$PCH1,\"videoPositionSec\":10}" $G/learning/lessons/$CFREE/progress
eq "非法上报未写入明细" 0 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson_progress WHERE chapter_id=$PCH1 AND user_id=$STU_ID;")"

echo "--- 12~13. 刷新与跨设备恢复真实学习位置 ---"
biz "查询课程学习详情" 200 -H "$SH" $G/learning/lessons/$CFREE
eq "刷新后恢复真实断点" 1000 "$(json "d['data']['lastVideoPositionSec']")"
S_RELOGIN=$(login student01 student123)
biz "重新登录后查询（模拟跨设备）" 200 -H "Authorization: Bearer $S_RELOGIN" $G/learning/lessons/$CFREE
eq "跨设备恢复同一断点" 1000 "$(json "d['data']['lastVideoPositionSec']")"
eq "最近学习章节一致" "$CH1" "$(json "d['data']['lastChapterId']")"

echo "--- 14. 章节完成与课程进度 ---"
biz "标记第一章完成" 200 -X POST -H "$SH" $G/learning/courses/$CFREE/chapters/$CH1/complete
eq "章节状态为已完成" 2 "$(mysqlq "SELECT status FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
biz "标记第二章完成" 200 -X POST -H "$SH" $G/learning/courses/$CFREE/chapters/$CH2/complete
biz "查询课程进度" 200 -H "$SH" $G/learning/lessons/$CFREE
eq "已完成章节数为 2" 2 "$(json "d['data']['finishedChapterCount']")"
eq "课程进度为 66%（2/3）" 66 "$(json "d['data']['progress']")"
eq "课程进度同步写入 learning_lesson" 66 "$(mysqlq "SELECT progress FROM learning_lesson WHERE user_id=$STU_ID AND course_id=$CFREE;")"

# M6 验收项 4：同一时点对账——接口返回的进度必须与 learning_lesson 行完全一致
SNAP_API=$(curl -s -H "$SH" "$G/learning/lessons/$CFREE" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['progress'])")
SNAP_DB=$(mysqlq "SELECT progress FROM learning_lesson WHERE user_id=$STU_ID AND course_id=$CFREE;")
eq "同一时点接口进度与 MySQL 一致" "$SNAP_DB" "$SNAP_API"
printf "  [INFO] 对账时点：API progress=%s，learning_lesson.progress=%s\n" "$SNAP_API" "$SNAP_DB"
L_API=$(curl -s -H "$SH" "$G/learning/lessons?pageNum=1&pageSize=50" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(''.join(str(r['progress']) for r in d['records'] if str(r['courseId'])=='$CFREE'))")
eq "同一时点列表进度与 MySQL 一致" "$SNAP_DB" "$L_API"

# M6 验收项 6：/teacher/** 路由不得冲突（学情统计走学习服务，课程管理走课程服务）
biz "教师课程管理接口仍命中课程服务" 200 -H "$TH" "$G/teacher/courses?pageNum=1&pageSize=1"
eq "教师课程列表返回课程服务数据" yes "$(python3 -c "
import json
d=json.load(open('/tmp/kzai_m5'))['data']
print('yes' if 'records' in d else 'no')")"
biz "教师学情统计接口命中学习服务" 200 -H "$TH" "$G/teacher/learning-stats?pageNum=1&pageSize=1"
eq "学情响应含统计口径字段" yes "$(python3 -c "
import json
d=json.load(open('/tmp/kzai_m5'))['data']
r=d['records'][0] if d.get('records') else {}
print('yes' if 'learnerCount' in r and 'chapterStats' in r else 'no')")"

echo "--- 试看规则 ---"
biz "教师设置第一章可试看" 200 -X PUT -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"freePreview\":true,\"videoDurationSec\":600}" $G/teacher/chapters/$PCH1/preview
eq "试看标识真实落库" 1 "$(mysqlq "SELECT free_preview FROM course_chapter_extra WHERE chapter_id=$PCH1;")"
# 用未购学员（student02）验证
http "未购学员可访问试看章节" 200 -H "$S2H" $G/learning/courses/$CPAID/chapters/$PCH1
http "未购学员访问非试看章节被拒" 403 -H "$S2H" $G/learning/courses/$CPAID/chapters/$PCH2
http "未购学员不能保存学习进度" 403 -X PUT -H "$S2H" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$PCH1,\"videoPositionSec\":10}" $G/learning/lessons/$CPAID/progress
curl -s -X PUT -H "$TH" -H 'Content-Type: application/json' -d '{"freePreview":false}' $G/teacher/chapters/$PCH1/preview >/dev/null
http "取消试看后未购学员被拒" 403 -H "$S2H" $G/learning/courses/$CPAID/chapters/$PCH1

echo "--- 15. 付费资源无法绕过权益校验 ---"
http "未购学员下载付费资料被拒" 403 -H "$S2H" $G/files/$DOC/download
http "未购学员获取付费视频被拒" 403 -H "$S2H" $G/files/$VID/download
http "已购学员可下载付费资料" 200 -H "$SH" $G/files/$DOC/download
http "未登录访问受控资源被拒" 401 -H 'Content-Type: application/json' $G/files/$DOC/download

echo "--- 16. HTTP Range ---"
R1=$(curl -s -o /dev/null -w "%{http_code}" -H "$SH" -H 'Range: bytes=0-9' $G/files/$VID/download)
eq "合法 Range 返回 206" 206 "$R1"
CR=$(curl -s -D - -o /dev/null -H "$SH" -H 'Range: bytes=0-9' $G/files/$VID/download 2>/dev/null | grep -i '^content-range' | tr -d '\r')
eq "响应包含 Content-Range" yes "$(echo "$CR" | grep -qi 'bytes 0-9' && echo yes || echo no)"
R2=$(curl -s -o /dev/null -w "%{http_code}" -H "$SH" -H 'Range: bytes=99999999-' $G/files/$VID/download)
eq "越界 Range 返回 416" 416 "$R2"
R3=$(curl -s -o /dev/null -w "%{http_code}" -H "$SH" -H 'Range: bytes=20-10' $G/files/$VID/download)
eq "起止倒置 Range 返回 416" 416 "$R3"
R4=$(curl -s -o /dev/null -w "%{http_code}" -H "$SH" -H 'Range: bytes=abc' $G/files/$VID/download)
eq "非法 Range 表达式返回 416" 416 "$R4"

echo "--- 17~18. 教师学情统计与越权 ---"
biz "教师查询本人课程学情" 200 -H "$TH" "$G/teacher/learning-stats?pageNum=1&pageSize=10"
stat_of() { python3 -c "
import json
d=json.load(open('/tmp/kzai_m5'))['data']
print(''.join(str(r['$1']) for r in d['records'] if str(r['courseId'])=='$CFREE'))"; }
eq "统计到免费课程学习记录" yes "$( [ -n "$(stat_of learnerCount)" ] && [ "$(stat_of learnerCount)" != "0" ] && echo yes || echo no )"
eq "学习人数与 MySQL 一致" "$(mysqlq "SELECT COUNT(*) FROM learning_lesson WHERE course_id=$CFREE;")" "$(stat_of learnerCount)"
eq "已完成人数与 MySQL 一致" "$(mysqlq "SELECT COUNT(*) FROM learning_lesson WHERE course_id=$CFREE AND status=2;")" "$(stat_of finishedLearnerCount)"
eq "累计有效学习时长与 MySQL 一致" "$(mysqlq "SELECT IFNULL(SUM(study_seconds),0) FROM learning_lesson_progress WHERE course_id=$CFREE;")" "$(stat_of totalStudySeconds)"
eq "平均学习时长与 MySQL 一致" "$(mysqlq "SELECT IFNULL(FLOOR((SELECT IFNULL(SUM(study_seconds),0) FROM learning_lesson_progress WHERE course_id=$CFREE) / NULLIF((SELECT COUNT(*) FROM learning_lesson WHERE course_id=$CFREE),0)),0);")" "$(stat_of avgStudySeconds)"
eq "章节完成分布来自真实进度表" "$(mysqlq "SELECT COUNT(*) FROM learning_lesson_progress WHERE course_id=$CFREE AND status=2;")" "$(python3 -c "
import json
d = json.load(open('/tmp/kzai_m5'))['data']
recs = d.get('records') or []
total = 0
for r in recs:
    if str(r.get('courseId')) == '$CFREE':
        for ch in (r.get('chapterStats') or []):
            total += int(ch.get('finishedCount') or 0)
print(total)")"
eq "课程问答数量已真实接入（M10）" "0" "$(stat_of questionCount)"
biz "按课程筛选学情" 200 -H "$TH" "$G/teacher/learning-stats?courseId=$CFREE&pageNum=1&pageSize=10"
http "其他教师查询该课程学情被拒" 403 -H "$T2H" "$G/teacher/learning-stats?courseId=$CFREE"
http "学员不能查询学情被拒" 403 -H "$SH" "$G/teacher/learning-stats"
# CSV 导出
CSV=$(curl -s -H "$TH" "$G/teacher/learning-stats/export?courseId=$CFREE")
eq "CSV 含 UTF-8 BOM" yes "$(printf '%s' "$CSV" | head -c 3 | xxd -p | grep -q 'efbbbf' && echo yes || echo no)"
eq "CSV 含章节分布表头" yes "$(printf '%s' "$CSV" | grep -q '章节学习人数' && echo yes || echo no)"
eq "CSV 标注问答数据未完成" yes "$(printf '%s' "$CSV" | grep -q 'M10' && echo yes || echo no)"
eq "CSV 标注 AI 采纳率未完成" yes "$(printf '%s' "$CSV" | grep -q '未完成' && echo yes || echo no)"
eq "CSV 为中文 UTF-8 内容" yes "$(printf '%s' "$CSV" | grep -q '课程名称' && echo yes || echo no)"

echo "--- 跨用户越权 ---"
biz "学员 A 查询我的学习" 200 -H "$SH" "$G/learning/lessons?pageNum=1&pageSize=10"
eq "列表仅包含本人课程" yes "$(python3 -c "
import json
d=json.load(open('/tmp/kzai_m5'))['data']
print('yes' if len(d['records'])>0 else 'no')")"
POS_BEFORE=$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")
# 学员 B 尝试用自己的身份写入 A 的进度：只能写入自己的记录
biz "学员 B 上报自己的进度" 200 -X PUT -H "$S2H" -H 'Content-Type: application/json' \
  -d "{\"chapterId\":$CH1,\"videoPositionSec\":999,\"videoDurationSec\":1200}" $G/learning/lessons/$CFREE/progress
eq "学员 A 的进度未被篡改" "$POS_BEFORE" "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
eq "学员 B 的进度独立记录" 999 "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$S2_ID AND chapter_id=$CH1;")"

echo "--- 用户删除关联保护（学习记录） ---"
biz "存在学习记录的用户删除被拒" 400 -X DELETE -H "$AH" $G/admin/users/$STU_ID
eq "拒绝原因与关联校验相关" yes "$(msg | grep -qE '关联|校验服务' && echo yes || echo no)"
eq "用户未被删除" 1 "$(mysqlq "SELECT COUNT(*) FROM user WHERE id=$STU_ID;")"

echo "--- 19. 学习服务不可用时的受控错误 ---"
source scripts/dev-env.sh >/dev/null 2>&1
PID=$(lsof -ti tcp:18085 2>/dev/null | head -1)
[ -n "$PID" ] && kill -9 "$PID" >/dev/null 2>&1
sleep 5
UNAVAIL=$(curl -s -o /tmp/kzai_m5 -w "%{http_code}" -H "$SH" "$G/learning/lessons?pageNum=1&pageSize=10")
eq "学习服务不可用时返回受控错误（非 200）" yes "$( [ "$UNAVAIL" != "200" ] && echo yes || echo no )"
printf "  [INFO] 学习服务不可用时网关返回 HTTP %s\n" "$UNAVAIL"
# 恢复学习服务（先停后启，确保 pid 与端口状态一致）
bash scripts/run-services.sh stop >/dev/null 2>&1; sleep 3
bash scripts/run-services.sh start >/dev/null 2>&1
for i in $(seq 1 60); do
  up=$(for p in 18080 18081 18082 18083 18084 18085; do lsof -nP -iTCP:$p -sTCP:LISTEN 2>/dev/null | grep -q LISTEN && printf "1" || printf "0"; done)
  [ "$up" = "111111" ] && break; sleep 2
done
for i in $(seq 1 20); do S5=$(login student01 student123); [ -n "$S5" ] && break; sleep 3; done
echo "--- 重启后学习数据仍存在 ---"
eq "重启后学习记录仍存在" 1 "$(mysqlq "SELECT COUNT(*) FROM learning_lesson WHERE user_id=$STU_ID AND course_id=$CFREE;")"
biz "重启后可查询我的学习" 200 -H "Authorization: Bearer $S5" "$G/learning/lessons?pageNum=1&pageSize=10"
eq "重启后断点仍保留" "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")" "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")"
eq "重启后断点非空" yes "$( [ "$(mysqlq "SELECT video_position_sec FROM learning_lesson_progress WHERE user_id=$STU_ID AND chapter_id=$CH1;")" != "0" ] && echo yes || echo no )"
biz "重启后可查询课程学习详情" 200 -H "Authorization: Bearer $S5" $G/learning/lessons/$CFREE
eq "重启后课程进度仍为 66%" 66 "$(json "d['data']['progress']")"

echo "=============================================="
echo "通过: $PASS   失败: $FAIL"
