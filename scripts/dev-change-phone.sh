#!/bin/bash
# M15.18：MOCK 环境修改手机号一键脚本（用法：./dev-change-phone.sh 登录密码 当前账号 新手机号）
# 示例：./dev-change-phone.sh student123 student01 13900007000
PASSWORD=${1:?用法: ./dev-change-phone.sh 登录密码 账号(用户名或手机号) 新手机号}
ACCOUNT=${2:?缺少账号}
NEWPHONE=${3:?缺少新手机号}
BASE=http://127.0.0.1:18080

TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$ACCOUNT\",\"password\":\"$PASSWORD\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
[ -z "$TOKEN" ] && { echo "✗ 登录失败"; exit 1; }

echo "① 发送换绑验证码…"
RESP=$(curl -s -X POST $BASE/auth/sms/send -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$NEWPHONE\",\"scene\":\"CHANGE_PHONE\"}")
CODE=$(echo "$RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('devCode') or '')" 2>/dev/null)
[ -z "$CODE" ] && { echo "✗ 发送失败: $RESP"; exit 1; }
echo "② 验证码（MOCK devCode）: $CODE"

echo "③ 确认更换…"
curl -s -X POST $BASE/auth/change-phone -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "{\"newPhone\":\"$NEWPHONE\",\"smsCode\":\"$CODE\"}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('④', d.get('data',{}).get('message') or d.get('msg'))"
