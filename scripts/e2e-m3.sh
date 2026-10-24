#!/bin/bash
# M3 端到端真实接口验收（经网关 18080，真实 MySQL + 微服务，无 Mock）
# 覆盖：分类管理、教师课程/章节/资料、管理员审核、学员可见性、越权拦截、持久化
# 用法: bash scripts/e2e-m3.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_body -w "%{http_code}" "$@"; }
json() { python3 -c "import sys,json;d=json.load(open('/tmp/kzai_body'));print($1)" 2>/dev/null; }
msg()  { json "d['msg']"; }
bcode(){ json "d['code']"; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

http() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@")
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s %s)\n" "$name" "$actual" "$(msg)"
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

echo "================ M3 端到端验收 ================"
TS=$(date +%s)
A=$(login admin admin123); T=$(login teacher01 teacher123); S=$(login student01 student123)
if [ -z "$A" ] || [ -z "$T" ] || [ -z "$S" ]; then echo "登录失败，终止"; exit 1; fi
AH="Authorization: Bearer $A"; TH="Authorization: Bearer $T"; SH="Authorization: Bearer $S"

echo "--- 1. 管理员课程分类 ---"
CAT="e2e分类$TS"
CATID=$(curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$CAT\",\"parentId\":0,\"sort\":9,\"status\":1}" $G/admin/categories | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
eq "新增一级分类成功" yes "$([ -n "$CATID" ] && [ "$CATID" != "None" ] && echo yes || echo no)"
SUBID=$(curl -s -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"子分类$TS\",\"parentId\":$CATID,\"sort\":1,\"status\":1}" $G/admin/categories | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
eq "新增二级分类成功" yes "$([ -n "$SUBID" ] && [ "$SUBID" != "None" ] && echo yes || echo no)"
http "修改分类排序" 200 -X PUT -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$CAT\",\"parentId\":0,\"sort\":3,\"status\":1}" $G/admin/categories/$CATID
eq "排序已持久化" 3 "$(mysqlq "SELECT sort FROM course_category WHERE id=$CATID;")"
biz "三级分类创建被拒（仅两级）" 400 -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"三级$TS\",\"parentId\":$SUBID,\"sort\":1,\"status\":1}" $G/admin/categories

echo "--- 2. 教师创建与编辑本人课程 ---"
COURSE="e2e课程$TS"
CID=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$COURSE\",\"firstCateId\":$CATID,\"secondCateId\":$SUBID,\"courseType\":2,\"price\":19900,\"validDuration\":6,\"usePeople\":\"零基础\",\"courseIntroduce\":\"简介$TS\",\"courseDetail\":\"详情$TS\"}" \
  $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
eq "教师创建课程成功" yes "$([ -n "$CID" ] && [ "$CID" != "None" ] && echo yes || echo no)"
eq "新建课程为待上架" 1 "$(mysqlq "SELECT status FROM course WHERE id=$CID;")"
eq "价格以分入库" 19900 "$(mysqlq "SELECT price FROM course WHERE id=$CID;")"
eq "课程归属本人" 1 "$(mysqlq "SELECT COUNT(*) FROM course WHERE id=$CID AND creater=(SELECT id FROM user WHERE username='teacher01');")"
http "教师编辑本人课程" 200 -X PUT -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$COURSE-改\",\"firstCateId\":$CATID,\"secondCateId\":$SUBID,\"courseType\":2,\"price\":29900,\"validDuration\":6,\"usePeople\":\"零基础/进阶\",\"courseIntroduce\":\"简介改\",\"courseDetail\":\"详情改\"}" $G/teacher/courses/$CID
eq "编辑后价格已更新" 29900 "$(mysqlq "SELECT price FROM course WHERE id=$CID;")"
eq "课程内容落 course_content" 1 "$(mysqlq "SELECT COUNT(*) FROM course_content WHERE id=$CID AND course_detail='详情改';")"

echo "--- 3. 教师越权（教师A改教师B课程） ---"
TB_USER="e2e_tea_$TS"; TB_PHONE="136${TS: -8}"
http "管理员创建教师B账号" 200 -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TB_USER\",\"cellPhone\":\"$TB_PHONE\",\"type\":3,\"roleCodes\":[\"TEACHER\"],\"password\":\"E2e@123456\"}" $G/admin/users
TB=$(login "$TB_USER" 'E2e@123456')
TBH="Authorization: Bearer $TB"
BCID=$(curl -s -X POST -H "$TBH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"教师B课程$TS\",\"firstCateId\":$CATID,\"secondCateId\":$SUBID,\"courseType\":2,\"price\":1000}" \
  $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
http "教师A修改教师B课程被拒" 403 -X PUT -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"越权改名\",\"firstCateId\":$CATID,\"courseType\":2,\"price\":1}" $G/teacher/courses/$BCID
eq "教师B课程未被篡改" 0 "$(mysqlq "SELECT COUNT(*) FROM course WHERE id=$BCID AND name='越权改名';")"
http "学员调用教师课程接口被拒" 403 -X PUT -H "$SH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"学员越权\",\"firstCateId\":$CATID,\"courseType\":2,\"price\":1}" $G/teacher/courses/$BCID
http "学员调用教师列表被拒" 403 -H "$SH" $G/teacher/courses

echo "--- 4. 章节管理 ---"
C1=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d '{"name":"第一章","description":"入门"}' $G/teacher/courses/$CID/chapters | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
C2=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d '{"name":"第二章","description":"进阶"}' $G/teacher/courses/$CID/chapters | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
C3=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' -d '{"name":"第三章","description":"实战"}' $G/teacher/courses/$CID/chapters | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
http "章节排序（反序）" 200 -X PUT -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"chapterIds\":[$C3,$C2,$C1]}" $G/teacher/courses/$CID/chapters/order
eq "排序持久化-第三章为第1" 1 "$(mysqlq "SELECT chapter_order FROM course_chapter WHERE id=$C3;")"
eq "排序持久化-第一章为第3" 3 "$(mysqlq "SELECT chapter_order FROM course_chapter WHERE id=$C1;")"
http "删除章节" 200 -X DELETE -H "$TH" $G/teacher/chapters/$C3
eq "删除为逻辑删除" 1 "$(mysqlq "SELECT deleted FROM course_chapter WHERE id=$C3;")"
eq "章节数同步 section_num" 2 "$(mysqlq "SELECT section_num FROM course WHERE id=$CID;")"

echo "--- 5. 文件上传与资料绑定 ---"
printf '\x89PNG\r\n\x1a\nM3 test' > /tmp/kzai_ok.png
printf 'MZ fake executable' > /tmp/kzai_bad.exe
dd if=/dev/zero of=/tmp/kzai_big.png bs=1m count=21 2>/dev/null
RID=$(curl -s -X POST -H "$TH" -F "file=@/tmp/kzai_ok.png" $G/files/upload | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['id'] if d.get('data') else '')" 2>/dev/null)
eq "合法文件上传成功" yes "$([ -n "$RID" ] && echo yes || echo no)"
biz "非法类型上传被拒" 400 -X POST -H "$TH" -F "file=@/tmp/kzai_bad.exe" $G/files/upload
biz "超大文件被拒" 400 -X POST -H "$TH" -F "file=@/tmp/kzai_big.png" $G/files/upload
http "绑定封面" 200 -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$RID,\"kind\":1}" $G/teacher/courses/$CID/resources
eq "封面回填 course.cover_url" 1 "$(mysqlq "SELECT COUNT(*) FROM course WHERE id=$CID AND cover_url='/files/$RID/download';")"
http "绑定章节资料" 200 -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$RID,\"kind\":2,\"chapterId\":$C1}" $G/teacher/courses/$CID/resources
http "学员越权下载未上架课程资料" 403 -H "$SH" $G/files/$RID/download
http "学员越权下载被拒（未登录）" 401 $G/files/$RID/download

echo "--- 6. 提交审核与学员可见性 ---"
http "教师提交审核" 200 -X POST -H "$TH" $G/teacher/courses/$CID/submit
eq "审核记录落 course_audit" 1 "$(mysqlq "SELECT COUNT(*) FROM course_audit WHERE course_id=$CID AND audit_status=1;")"
biz "重复提交被拒" 400 -X POST -H "$TH" $G/teacher/courses/$CID/submit
biz "审核中编辑被拒" 400 -X PUT -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"审核中改名\",\"firstCateId\":$CATID,\"courseType\":2,\"price\":1}" $G/teacher/courses/$CID
eq "审核中课程未被改名" 0 "$(mysqlq "SELECT COUNT(*) FROM course WHERE id=$CID AND name='审核中改名';")"
biz "学员访问未上架课程被拒" 400 -H "$SH" $G/courses/$CID

echo "--- 7. 管理员驳回 / 通过 / 强制下架 ---"
eq "待审核列表包含该课程" 1 "$(curl -s -G -H "$AH" --data-urlencode "name=$COURSE" "$G/admin/courses/audits" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(1 if any(r['id']=='$CID' for r in d['records']) else 0)" 2>/dev/null)"
http "驳回（填原因）" 200 -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"reason":"封面不清晰"}' $G/admin/courses/$CID/audit/reject
eq "驳回原因持久化" "封面不清晰" "$(mysqlq "SELECT reason FROM course_audit WHERE course_id=$CID AND audit_status=3 ORDER BY create_time DESC LIMIT 1;")"
eq "驳回后课程仍为待上架" 1 "$(mysqlq "SELECT status FROM course WHERE id=$CID;")"
REASON=$(curl -s -H "$TH" $G/teacher/courses/$CID/audit | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(d['auditReason'] or '')" 2>/dev/null)
eq "教师可见审核意见" "封面不清晰" "$REASON"
biz "学员仍不可见驳回课程" 400 -H "$SH" $G/courses/$CID
http "驳回后重新提交审核" 200 -X POST -H "$TH" $G/teacher/courses/$CID/submit
eq "重新提交后为待审核" 1 "$(mysqlq "SELECT audit_status FROM course_audit WHERE course_id=$CID ORDER BY create_time DESC, id DESC LIMIT 1;")"
http "审核通过" 200 -X POST -H "$AH" $G/admin/courses/$CID/audit/approve
eq "通过后课程已上架" 2 "$(mysqlq "SELECT status FROM course WHERE id=$CID;")"
http "学员可见已上架课程" 200 -H "$SH" $G/courses/$CID
eq "学员列表包含该课程" 1 "$(curl -s -G -H "$SH" --data-urlencode "keyword=$COURSE" "$G/courses" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(1 if any(r['id']=='$CID' for r in d['records']) else 0)" 2>/dev/null)"
http "未购学员下载付费课程资料被拒（付费内容需学习权益）" 403 -H "$SH" $G/files/$RID/download
http "强制下架（填原因）" 200 -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"reason":"内容违规"}' $G/admin/courses/$CID/offline
eq "下架后状态为3" 3 "$(mysqlq "SELECT status FROM course WHERE id=$CID;")"
biz "下架后学员不可见" 400 -H "$SH" $G/courses/$CID
biz "重复下架被拒（状态流转校验）" 400 -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"reason":"再次下架"}' $G/admin/courses/$CID/offline

echo "--- 8. 分类禁用与删除校验 ---"
http "禁用一级分类" 200 -X PUT -H "$AH" "$G/admin/categories/$CATID/status?status=0"
eq "二级分类连带禁用" 0 "$(mysqlq "SELECT status FROM course_category WHERE id=$SUBID;")"
eq "学员端分类树不含已禁用分类" 0 "$(curl -s -H "$SH" $G/categories/tree | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(sum(1 for c in d if c['id']==$CATID))" 2>/dev/null)"
eq "学员课程列表不含已禁用分类课程" 0 "$(curl -s -H "$SH" "$G/courses?pageNum=1&pageSize=50" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(sum(1 for r in d['records'] if r['id']=='$CID'))" 2>/dev/null)"
http "重新启用分类" 200 -X PUT -H "$AH" "$G/admin/categories/$CATID/status?status=1"
biz "一级分类存在子分类删除失败" 400 -X DELETE -H "$AH" $G/admin/categories/$CATID
biz "二级分类存在关联课程删除失败" 400 -X DELETE -H "$AH" $G/admin/categories/$SUBID
eq "拒绝原因为课程关联" yes "$(msg | grep -q '门课程' && echo yes || echo no)"

echo "--- 9. 服务重启后数据仍存在 ---"
source scripts/dev-env.sh >/dev/null 2>&1
bash scripts/run-services.sh stop >/dev/null 2>&1; sleep 3
bash scripts/run-services.sh start >/dev/null 2>&1
for i in $(seq 1 45); do
  up=$(for p in 18080 18081 18082 18083; do lsof -nP -iTCP:$p -sTCP:LISTEN 2>/dev/null | grep -q LISTEN && printf "1" || printf "0"; done)
  [ "$up" = "1111" ] && break; sleep 2
done
for i in $(seq 1 20); do
  A2=$(login admin admin123); T2=$(login teacher01 teacher123)
  [ -n "$A2" ] && [ -n "$T2" ] && break; sleep 3
done
eq "重启后课程仍存在" 1 "$(mysqlq "SELECT COUNT(*) FROM course WHERE id=$CID;")"
eq "重启后章节仍存在" 2 "$(mysqlq "SELECT COUNT(*) FROM course_chapter WHERE course_id=$CID AND deleted=0;")"
http "重启后教师可查询课程" 200 -H "Authorization: Bearer $T2" $G/teacher/courses/$CID
http "重启后管理员可查询审核列表" 200 -H "Authorization: Bearer $A2" $G/admin/courses/audits

echo "=============================================="
echo "通过: $PASS   失败: $FAIL"
