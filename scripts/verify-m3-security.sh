#!/bin/bash
# M3 与历史安全遗留核验脚本（真实接口 + 真实 MySQL + 真实 Redis，无 Mock）
# 覆盖：权限与所有权、type=1 默认角色、Token 版本失效、直连伪造、缓存失效、下载鉴权
# 用法: bash scripts/verify-m3-security.sh
set -u
G=http://127.0.0.1:18080
USER_SVC=http://127.0.0.1:18082
COURSE_SVC=http://127.0.0.1:18083
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_v -w "%{http_code}" "$@"; }
json() { python3 -c "import json;d=json.load(open('/tmp/kzai_v'));print($1)" 2>/dev/null; }
msg()  { json "d['msg']"; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
roles() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(','.join(d['data']['roles']) if d.get('data') else '')" 2>/dev/null; }

http() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@")
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s, %s)\n" "$name" "$actual" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望HTTP=%s 实际=%s %s\n" "$name" "$expect" "$actual" "$(msg)"; fi
}
biz() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@"); local bc; bc=$(json "d['code']")
  if [ "$expect" = "$bc" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s, code %s, %s)\n" "$name" "$actual" "$bc" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望code=%s 实际code=%s HTTP=%s %s\n" "$name" "$expect" "$bc" "$actual" "$(msg)"; fi
}
eq() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }

echo "============ M3 / 历史安全遗留核验 ============"
TS=$(date +%s)
A=$(login admin admin123); T=$(login teacher01 teacher123); S=$(login student01 student123)
AH="Authorization: Bearer $A"; TH="Authorization: Bearer $T"; SH="Authorization: Bearer $S"

echo "--- A. 角色与接口权限边界 ---"
http "学员访问 /admin/users 被拒" 403 -H "$SH" "$G/admin/users?pageNum=1&pageSize=10"
http "学员访问 /admin/categories 被拒" 403 -H "$SH" $G/admin/categories/tree
http "教师访问 /admin/categories 被拒" 403 -H "$TH" $G/admin/categories/tree
http "学员访问 /teacher/courses 被拒" 403 -H "$SH" $G/teacher/courses

echo "--- B. type=1 默认角色（禁止自动 ADMIN） ---"
U="v3_staff_$TS"; PHONE="137${TS: -8}"
http "管理员创建 type=1 无角色账号" 200 -X POST -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"cellPhone\":\"$PHONE\",\"type\":1,\"roleCodes\":[],\"password\":\"E2e@123456\"}" $G/admin/users
R=$(roles "$U" 'E2e@123456')
eq "type=1 无角色记录时角色为 STAFF" "STAFF" "$R"
ST=$(login "$U" 'E2e@123456')
http "STAFF 只读查看用户列表（M2 既定）" 200 -H "Authorization: Bearer $ST" "$G/admin/users?pageNum=1&pageSize=10"
http "STAFF 新增用户被拒（仅 ADMIN）" 403 -X POST -H "Authorization: Bearer $ST" -H 'Content-Type: application/json' \
  -d "{\"username\":\"v3_deny_$TS\",\"cellPhone\":\"138${TS: -8}\",\"type\":3,\"roleCodes\":[\"TEACHER\"],\"password\":\"E2e@123456\"}" $G/admin/users
http "STAFF 删除用户被拒（仅 ADMIN）" 403 -X DELETE -H "Authorization: Bearer $ST" $G/admin/users/999

echo "--- C. Token 版本：Redis 缓存清空后失效凭证不得复活 ---"
OLD=$(login student01 student123)
http "旧密码修改密码" 200 -X PUT -H "Authorization: Bearer $OLD" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"student123","newPassword":"E2e@Tmp12345"}' $G/users/me/password
http "改密后旧 Token 立即失效" 401 -H "Authorization: Bearer $OLD" $G/users/me
~/opt/tools/redis/bin/redis-cli --scan --pattern "AUTH:TOKEN:VER:*" 2>/dev/null | xargs -r ~/opt/tools/redis/bin/redis-cli del >/dev/null 2>&1
http "清空 Redis 版本缓存后旧 Token 仍失效（DB 回源）" 401 -H "Authorization: Bearer $OLD" $G/users/me
NEW=$(login student01 'E2e@Tmp12345')
http "密码改回原值" 200 -X PUT -H "Authorization: Bearer $NEW" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"E2e@Tmp12345","newPassword":"student123"}' $G/users/me/password
eq "恢复后可用原密码登录" yes "$([ -n "$(login student01 student123)" ] && echo yes || echo no)"

S=$(login student01 student123); SH="Authorization: Bearer $S"

echo "--- D. 绕过网关：直连业务端口与伪造身份头 ---"
http "直连课程服务伪造身份头被拒" 401 -H "X-User-Id: 1" -H "X-User-Roles: ADMIN" -H "X-User-Name: admin" \
  $COURSE_SVC/teacher/courses
http "直连用户服务伪造身份头被拒" 401 -H "X-User-Id: 1" -H "X-User-Roles: ADMIN" -H "X-User-Name: admin" \
  "$USER_SVC/admin/users?pageNum=1&pageSize=10"
http "直连用户服务访问内部接口被拒" 403 $USER_SVC/internal/users/1/token-version

echo "--- E. 课程缓存失效（分类禁用 / 下架） ---"
# 自建分类，避免依赖 seed 状态（此前核验曾禁用过 seed 二级分类）
mkcat() { curl -s -X POST -H "$AH" -H 'Content-Type: application/json' -d "$1" $G/admin/categories | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null; }
CAT=$(mkcat "{\"name\":\"核验分类$TS\",\"parentId\":0,\"sort\":9,\"status\":1}")
CAT2=$(mkcat "{\"name\":\"核验子分类$TS\",\"parentId\":$CAT,\"sort\":1,\"status\":1}")
CID=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"缓存核验课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"price\":19900}" \
  $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
biz "提交审核" 200 -X POST -H "$TH" $G/teacher/courses/$CID/submit
biz "审核通过" 200 -X POST -H "$AH" $G/admin/courses/$CID/audit/approve
http "学员可访问（写入缓存）" 200 -H "$SH" $G/courses/$CID
eq "Redis 中存在课程缓存键" 1 "$(~/opt/tools/redis/bin/redis-cli exists "course:$CID" 2>/dev/null)"
biz "管理员禁用一级分类" 200 -X PUT -H "$AH" "$G/admin/categories/$CAT/status?status=0"
biz "分类禁用后学员访问应不可见" 400 -H "$SH" $G/courses/$CID
biz "恢复一级分类启用" 200 -X PUT -H "$AH" "$G/admin/categories/$CAT/status?status=1"
biz "恢复二级分类启用（禁用连带、启用不连带）" 200 -X PUT -H "$AH" "$G/admin/categories/$CAT2/status?status=1"

echo "--- F. 文件下载鉴权（免费 / 付费 / 未上架） ---"
printf '\x89PNG\r\n\x1a\nsec' > /tmp/kzai_v.png
PAY_NAME="付费课$TS"
PCID=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$PAY_NAME\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"price\":29900}" \
  $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
FCID=$(curl -s -X POST -H "$TH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"免费课$TS\",\"firstCateId\":$CAT,\"secondCateId\":$CAT2,\"courseType\":2,\"price\":0}" \
  $G/teacher/courses | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])" 2>/dev/null)
PRID=$(curl -s -X POST -H "$TH" -F "file=@/tmp/kzai_v.png" $G/files/upload | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
FRID=$(curl -s -X POST -H "$TH" -F "file=@/tmp/kzai_v.png" $G/files/upload | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
http "绑定付费课程资料" 200 -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$PRID,\"kind\":2}" $G/teacher/courses/$PCID/resources
http "绑定免费课程资料" 200 -X POST -H "$TH" -H 'Content-Type: application/json' -d "{\"resourceId\":$FRID,\"kind\":2}" $G/teacher/courses/$FCID/resources
biz "付费课程提交审核" 200 -X POST -H "$TH" $G/teacher/courses/$PCID/submit
biz "付费课程审核通过" 200 -X POST -H "$AH" $G/admin/courses/$PCID/audit/approve
biz "免费课程提交审核" 200 -X POST -H "$TH" $G/teacher/courses/$FCID/submit
biz "免费课程审核通过" 200 -X POST -H "$AH" $G/admin/courses/$FCID/audit/approve
eq "付费课程状态为已上架" 2 "$(mysqlq "SELECT status FROM course WHERE id=$PCID;")"
eq "免费课程状态为已上架" 2 "$(mysqlq "SELECT status FROM course WHERE id=$FCID;")"
http "免费课程资料允许登录学员下载" 200 -H "$SH" $G/files/$FRID/download
http "未购学员下载付费课程资料被拒" 403 -H "$SH" $G/files/$PRID/download
biz "管理员强制下架付费课程" 200 -X POST -H "$AH" -H 'Content-Type: application/json' -d '{"reason":"核验下架"}' $G/admin/courses/$PCID/offline
http "下架后付费资料下载被拒" 403 -H "$SH" $G/files/$PRID/download

echo "=============================================="
echo "通过: $PASS   失败: $FAIL"
