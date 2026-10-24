#!/bin/bash
# M2 端到端真实接口验收（经网关 18080，无 Mock）
# 说明：业务异常 BizException 以 HTTP 200 + body.code 表达；鉴权异常 HTTP 状态码跟随业务码（401/403）
# 用法: bash scripts/e2e-m2.sh
set -u
G=http://127.0.0.1:18080
PASS=0; FAIL=0

code() { curl -s -o /tmp/kzai_body -w "%{http_code}" "$@"; }
json() { python3 -c "import sys,json;d=json.load(open('/tmp/kzai_body'));print($1)" 2>/dev/null; }
msg()  { json "d['msg']"; }
bcode(){ json "d['code']"; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }

# http <用例> <期望HTTP> <curl参数...>
http() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@")
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s %s)\n" "$name" "$actual" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望HTTP=%s 实际=%s %s\n" "$name" "$expect" "$actual" "$(msg)"; fi
}

# biz <用例> <期望body.code> <curl参数...>   业务异常：HTTP 200 + body.code
biz() {
  local name="$1" expect="$2"; shift 2
  local actual; actual=$(code "$@"); local bc; bc=$(bcode)
  if [ "$expect" = "$bc" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (HTTP %s, code %s, %s)\n" "$name" "$actual" "$bc" "$(msg)"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望code=%s 实际code=%s HTTP=%s %s\n" "$name" "$expect" "$bc" "$actual" "$(msg)"; fi
}

eq() { # eq <用例> <期望值> <实际值>
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi
}

echo "================ M2 端到端验收 ================"
# 严格删除策略下测试账号无法删除，故每次运行使用唯一账号避免互相干扰
TS=$(date +%s)
NEWUSER="e2e_stu_$TS"
PHONE="139${TS: -8}"
PHONE2="138${TS: -8}"
PHONE3="137${TS: -8}"
CREATE_JSON="{\"username\":\"$NEWUSER\",\"cellPhone\":\"$PHONE\",\"type\":2,\"roleCodes\":[\"STUDENT\"],\"password\":\"E2e@123456\"}"
LOGIN_JSON="{\"username\":\"$NEWUSER\",\"password\":\"E2e@123456\"}"
DUP_JSON="{\"username\":\"$NEWUSER\",\"cellPhone\":\"$PHONE2\",\"type\":2,\"roleCodes\":[\"STUDENT\"]}"
LOGIN_JSON="{\"username\":\"$NEWUSER\",\"password\":\"E2e@123456\"}"
DUP_JSON="{\"username\":\"$NEWUSER\",\"cellPhone\":\"13911110003\",\"type\":2,\"roleCodes\":[\"STUDENT\"]}"
TA=$(login admin admin123); TS=$(login student01 student123); TT=$(login teacher01 teacher123)
[ -z "$TA" ] && { echo "管理员登录失败，终止"; exit 1; }
A="Authorization: Bearer $TA"; S="Authorization: Bearer $TS"; T="Authorization: Bearer $TT"

echo "--- 1. 管理员用户管理 ---"
http "用户列表分页" 200 -H "$A" "$G/admin/users?pageNum=1&pageSize=2"
TOTAL=$(json "d['data']['total']"); PAGES=$(json "d['data']['pages']")
eq "分页总数与页数正确" yes "$([ "$TOTAL" -ge 4 ] && [ "$PAGES" -ge 2 ] && echo yes || echo no)"
http "新增学员账号" 200 -X POST -H "$A" -H 'Content-Type: application/json' \
  -d "$CREATE_JSON" "$G/admin/users"
NEWID=$(json "d['data']['id']")
http "新账号可登录" 200 -X POST $G/auth/login -H 'Content-Type: application/json' -d "$LOGIN_JSON"
http "组合条件搜索" 200 -H "$A" "$G/admin/users?username=$NEWUSER&roleCode=STUDENT"
eq "搜索命中 1 条" 1 "$(json "d['data']['total']")"
http "编辑用户手机号" 200 -X PUT -H "$A" -H 'Content-Type: application/json' -d "{\"cellPhone\":\"$PHONE3\"}" "$G/admin/users/$NEWID"
eq "手机号已更新" "$PHONE3" "$(json "d['data']['cellPhone']")"
http "分配角色" 200 -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"roleCodes":["TEACHER"]}' "$G/admin/users/$NEWID/roles"
eq "角色变更同步 user.type" 3 "$(json "d['data']['type']")"
biz "重复账号新增被拒" 400 -X POST -H "$A" -H 'Content-Type: application/json' \
  -d "$DUP_JSON" "$G/admin/users"
biz "非法角色被拒" 400 -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"roleCodes":["HACKER"]}' "$G/admin/users/$NEWID/roles"

echo "--- 2. 角色权限控制 ---"
http "学员访问管理接口" 403 -H "$S" "$G/admin/users"
http "教师访问管理接口" 403 -H "$T" "$G/admin/users"
http "伪造 X-User-Roles 头" 403 -H "$S" -H 'X-User-Roles: ADMIN' "$G/admin/users"
http "伪造 X-User-Id 头仍取真实身份" 200 -H "$S" -H 'X-User-Id: 1' "$G/users/me"
eq "返回的是本人而非伪造用户" student01 "$(json "d['data']['username']")"
http "内部接口外部不可达" 403 -H "$A" "$G/internal/courses/count-by-creator/2"
http "无 token 访问被拒" 401 "$G/admin/users"
http "非法 token 被拒" 401 -H 'Authorization: Bearer bad.token.value' "$G/users/me"

echo "--- 3. 状态与密码（Token 失效） ---"
TN=$(login "$NEWUSER" 'E2e@123456')
http "新账号 token 可用" 200 -H "Authorization: Bearer $TN" "$G/users/me"
http "禁用账号" 200 -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"status":0}' "$G/admin/users/$NEWID/status"
http "禁用后旧 token 失效" 401 -H "Authorization: Bearer $TN" "$G/users/me"
http "禁用账号无法登录" 403 -X POST $G/auth/login -H 'Content-Type: application/json' -d "$LOGIN_JSON"
http "管理员重新启用账号" 200 -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"status":1}' "$G/admin/users/$NEWID/status"
http "重新启用后可登录" 200 -X POST $G/auth/login -H 'Content-Type: application/json' -d "$LOGIN_JSON"
http "管理员重置密码" 200 -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"newPassword":"Reset@123456"}' "$G/admin/users/$NEWID/password"
http "重置后新密码可登录" 200 -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"e2e_stu","password":"Reset@123456"}'
http "重置后旧密码失效" 401 -X POST $G/auth/login -H 'Content-Type: application/json' -d "$LOGIN_JSON"

echo "--- 4. 删除前关联校验（严格 fail-closed） ---"
biz "删除用户：关联类型未接入即拒绝" 400 -X DELETE -H "$A" "$G/admin/users/2"
# M10：COURSE/ORDER/LEARNING/CHAT_SESSION/INTERACTION 五类 Guard 全部接入，
# 关联计数均为 0 的新用户可以删除（fail-closed 仅在 Guard 缺失或服务不可用时生效）
http "删除用户：五类 Guard 齐全且无关联时放行" 200 -X DELETE -H "$A" "$G/admin/users/$NEWID"
http "删除后按用户名查询" 200 -H "$A" "$G/admin/users?username=$NEWUSER"
eq "删除后列表不再有该用户" 0 "$(json "d['data']['total']")"

echo "--- 5. 个人中心与改密 ---"
http "修改个人资料" 200 -X PUT -H "$S" -H 'Content-Type: application/json' -d '{"cellPhone":"13800000009"}' "$G/users/me"
eq "手机号已更新" 13800000009 "$(json "d['data']['cellPhone']")"
biz "旧密码错误时改密被拒" 400 -X PUT -H "$S" -H 'Content-Type: application/json' -d '{"oldPassword":"wrongpwd","newPassword":"New@123456"}' "$G/users/me/password"
http "正确旧密码可改密" 200 -X PUT -H "$S" -H 'Content-Type: application/json' -d '{"oldPassword":"student123","newPassword":"New@123456"}' "$G/users/me/password"
http "改密后旧 token 失效" 401 -H "$S" "$G/users/me"
http "改密后新密码可登录" 200 -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"student01","password":"New@123456"}'
TS2=$(login student01 'New@123456')
http "还原 seed 密码" 200 -X PUT -H "Authorization: Bearer $TS2" -H 'Content-Type: application/json' -d '{"oldPassword":"New@123456","newPassword":"student123"}' "$G/users/me/password"
http "还原后可登录" 200 -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"student01","password":"student123"}'

echo "--- 6. 操作日志与持久化 ---"
CNT=$(~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "SELECT COUNT(*) FROM sys_operation_log;" 2>/dev/null)
eq "管理操作已写入日志表" yes "$([ "${CNT:-0}" -gt 0 ] && echo yes || echo no)"
ROLES=$(~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "SELECT COUNT(*) FROM sys_user_role;" 2>/dev/null)
eq "角色表存在数据" yes "$([ "${ROLES:-0}" -gt 0 ] && echo yes || echo no)"

echo "--- 7. 身份信任边界（绕过网关直连业务端口） ---"
# 直连 user 服务伪造管理员身份头（无内部签名）
FORGED=$(curl -s -w "|%{http_code}" -H 'X-User-Id: 1' -H 'X-User-Name: admin' \
  -H 'X-Username: admin' -H 'X-User-Role: 1' -H 'X-User-Roles: ADMIN' \
  "http://127.0.0.1:18082/admin/users")
eq "直连伪造管理员身份被拒" 401 "$(echo "$FORGED" | tail -c 4)"
FORGED2=$(curl -s -w "|%{http_code}" "http://127.0.0.1:18082/users/me")
eq "直连无身份用户接口被拒" 401 "$(echo "$FORGED2" | tail -c 4)"
INTERNAL=$(curl -s -w "|%{http_code}" "http://127.0.0.1:18083/internal/courses/count-by-creator/2")
eq "直连内部接口无签名被拒" 403 "$(echo "$INTERNAL" | tail -c 4)"

echo "=============================================="
echo "通过: $PASS   失败: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
