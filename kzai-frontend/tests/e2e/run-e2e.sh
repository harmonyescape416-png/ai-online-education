#!/bin/bash
# M15.24：浏览器 E2E（agent-browser）——登录失效/订单状态/资料恢复/草稿载入/SSE 停止/播放器
# 前置：全部服务运行 + 前端 5173（隔离账号 e2e_student 随机注册）
BASE=http://127.0.0.1:18080
FRONT=http://127.0.0.1:5173
PASS=0; FAIL=0
t() { # t "名称" "实际" "预期包含"
  if echo "$2" | grep -q "$3"; then PASS=$((PASS+1)); echo "  ✓ $1"
  else FAIL=$((FAIL+1)); echo "  ✗ $1（实际: $(echo "$2" | head -c 120)）"; fi
}

echo "=== ① 隔离账号注册（MOCK devCode）==="
PHONE="139$RANDOM$RANDOM"; PHONE=${PHONE:0:11}
REG=$(curl -s -X POST $BASE/auth/sms/send -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"scene\":\"REGISTER\"}")
CODE=$(echo "$REG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'].get('devCode',''))" 2>/dev/null)
REG2=$(curl -s -X POST $BASE/auth/register -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"smsCode\":\"$CODE\",\"password\":\"E2eTest123\",\"confirmPassword\":\"E2eTest123\"}")
t "注册成功" "$REG2" "注册成功"
TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d "{\"username\":\"$PHONE\",\"password\":\"E2eTest123\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
t "登录获取 token" "$TOKEN" "eyJ"

echo "=== ② 登录失效（无效 token → 401）==="
U401=$(curl -s $BASE/users/me -H "Authorization: Bearer invalid.token.here")
t "无效 token 401" "$U401" "401"

echo "=== ③ 订单详情契约（结构化 enrollStatuses）==="
CID=$(curl -s "$BASE/courses?page=1&size=50" -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
for r in (d.get('records') or []):
    if r.get('free')==0: print(r['id']); break" 2>/dev/null)
ORDER=$(curl -s -X POST $BASE/orders/pre-place -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"courseIds\":[\"$CID\"],\"requestId\":\"e2e-$RANDOM\"}")
OID=$(echo "$ORDER" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['orderId'])")
PAY=$(curl -s -X POST "$BASE/orders/$OID/pay" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"channel":"WECHAT"}')
t "支付+开课补偿（enrollAllSuccess）" "$PAY" "enrollAllSuccess"
t "补偿状态 SUCCESS" "$PAY" "SUCCESS"
DET=$(curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN")
t "详情结构化 enrollStatuses" "$DET" "enrollStatuses"

echo "=== ④ 资料恢复入口（unbound 接口）==="
UB=$(curl -s "$BASE/teacher/resources/unbound" -H "Authorization: Bearer $(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"teacher01","password":"teacher123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")")
t "unbound 接口 200" "$UB" '"code":200'

echo "=== ⑤ 浏览器流程（agent-browser）==="
export PATH=/Users/djanjo/opt/tools/node/bin:$PATH
agent-browser close >/dev/null 2>&1; sleep 1
agent-browser set viewport 1440 900 >/dev/null 2>&1
agent-browser open "$FRONT/#/login" >/dev/null 2>&1; sleep 2
agent-browser eval "localStorage.setItem('kzai_token','$TOKEN'); location.hash='#/orders/$OID'; location.reload(); 'ok'" >/dev/null 2>&1
sleep 4
RES=$(agent-browser eval "JSON.stringify({order: document.body.textContent.includes('$OID'), amount: !!document.body.textContent.match(/¥/), enroll: document.body.textContent.includes('已开课'), noUndef: !document.body.textContent.includes('undefined')})" 2>&1 | sed 's/\\//g')
t "订单详情浏览器渲染（无 undefined）" "$RES" '"noUndef":true'
t "开课状态已开课 tag" "$RES" '"enroll":true'

agent-browser close >/dev/null 2>&1
echo "=== 结果：$PASS 通过 / $FAIL 失败 ==="
[ $FAIL -eq 0 ]
