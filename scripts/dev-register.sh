#!/bin/bash
# M15.17：MOCK 环境注册一键脚本（仅开发/测试；用法：./dev-register.sh 手机号 [密码]）
PHONE=${1:?用法: ./dev-register.sh 13800001111 [密码]}
PASSWORD=${2:-Test123456}
BASE=http://127.0.0.1:18080

echo "① 发送验证码…"
RESP=$(curl -s -X POST $BASE/auth/sms/send -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"scene\":\"REGISTER\"}")
CODE=$(echo "$RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('devCode') or '')" 2>/dev/null)
if [ -z "$CODE" ]; then
  echo "✗ 发送失败: $RESP"; exit 1
fi
echo "② 验证码（MOCK devCode）: $CODE"

echo "③ 注册…"
REG=$(curl -s -X POST $BASE/auth/register -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"smsCode\":\"$CODE\",\"password\":\"$PASSWORD\",\"confirmPassword\":\"$PASSWORD\"}")
USERNAME=$(echo "$REG" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('username') or '')" 2>/dev/null)
if [ -z "$USERNAME" ]; then
  echo "✗ 注册失败: $REG"; exit 1
fi
echo "④ 注册成功！"
echo "   ┌──────────────────────────────────────────┐"
echo "   │ 登录页填入以下任一组：                    │"
echo "   │   手机号: $PHONE  密码: $PASSWORD"
echo "   │   用户名: $USERNAME"
echo "   └──────────────────────────────────────────┘"
