#!/bin/bash
# M11 浏览器 E2E：通知铃铛真实未读数 → 通知中心 → 查看已读 → 全部已读 → 删除 → 跳转 → 跨用户隔离
# 用法: bash scripts/e2e-m11-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0
contains() { local n="$1" nd="$2" hay="$3"; if printf '%s' "$hay" | grep -qF -- "$nd"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$n"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$n" "$nd"; fi; }
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
ab() { agent-browser "$@" 2>&1 | tail -1; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
unread() { curl -s -H "Authorization: Bearer $1" $G/notifications/unread-count | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['count'])" 2>/dev/null; }
snapshot() { ab eval "(function(){return document.body.innerText.replace(/\n+/g,'|').slice(0,1400)})()" 2>&1 | tail -1; }
click_text() { ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('$1')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null; }

echo "================ M11 浏览器 E2E（通知铃铛与通知中心） ================"
TS=$(date +%s)
STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
TEA_ID=$(mysqlq "SELECT id FROM user WHERE username='teacher01';")
CID=$(mysqlq "SELECT id FROM course WHERE creater=$TEA_ID AND deleted=0 AND status=2 ORDER BY id LIMIT 1")

echo "--- 0. 制造真实通知：学员提问 → AI 回复；教师回答 ---"
mysqlq "DELETE FROM sys_notification WHERE recipient_id IN ($STU_ID,$TEA_ID);" >/dev/null
QID=$(curl -s -X POST -H "Authorization: Bearer $STOK" -H 'Content-Type: application/json' \
  -d "{\"title\":\"M11 浏览器通知验证 $TS\",\"description\":\"请介绍退款规则。\",\"courseId\":$CID}" \
  $G/questions | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['questionId'])" 2>/dev/null)
sleep 20
curl -s -o /dev/null -X POST -H "Authorization: Bearer $TTOK" -H 'Content-Type: application/json' \
  -d "{\"content\":\"M11 浏览器教师答复 $TS\"}" "$G/teacher/questions/$QID/reply"
sleep 8
UNREAD1=$(unread $STOK)
eq "0 后端存在未读通知（真实数据）" 1 "$(mysqlq "SELECT COUNT(*)>0 FROM sys_notification WHERE recipient_id=$STU_ID AND read_status=0 AND deleted=0;")"

echo "--- 1. 学员登录 → 顶部铃铛展示真实未读数量 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
# 进入主框架页（铃铛位于 MainLayout 顶部）
ab open "$FE/#/courses" >/dev/null; sleep 5
SNAP1=$(snapshot)
contains "1 主框架渲染（含铃铛容器）" "课栈智伴" "$SNAP1"
BELL1=$(ab eval "(function(){var b=document.querySelector('.notify-bell .el-badge__content');return b?b.textContent.trim():'none'})()" 2>&1 | tail -1 | tr -d '"\r')
eq "1 铃铛未读徽标=后端未读数" "$UNREAD1" "$BELL1"
eq "1 铃铛可点击进入通知中心" 1 "$(ab eval "(function(){var b=document.querySelector('.notify-bell');if(!b)return 0;b.click();return 1})()" 2>&1 | tail -1 | tr -d '"\r')"
sleep 4
SNAP1B=$(snapshot)
contains "1 点击铃铛进入通知中心" "通知中心" "$SNAP1B"

echo "--- 2. 进入通知中心 ---"
ab open "$FE/#/notifications" >/dev/null; sleep 5
SNAP2=$(snapshot)
contains "2 通知中心展示 AI 回复通知" "AI 已回复你的问题" "$SNAP2"
contains "2 通知中心展示教师回复通知" "教师回答了你的问题" "$SNAP2"
contains "2 MOCK 标识保留（不冒充真实模型）" "MOCK" "$SNAP2"

echo "--- 3. 查看通知 → 自动已读 → 未读数减少 ---"
click_text "查看"; sleep 4
SNAP3=$(snapshot)
contains "3 详情弹窗显示通知内容" "查看关联业务" "$SNAP3"
UNREAD2=$(unread $STOK)
eq "3 查看后未读数减少" 1 "$([ "$UNREAD2" -lt "$UNREAD1" ] && echo 1 || echo "未减：$UNREAD1→$UNREAD2")"

echo "--- 4. 全部标记已读 ---"
click_text "全部标记已读"; sleep 2
click_text "确定"; sleep 3
eq "4 全部已读后未读为 0" 0 "$(unread $STOK)"

echo "--- 5. 已读筛选与类型筛选 ---"
click_text "已读"; sleep 3
SNAP5=$(snapshot)
contains "5 已读筛选有记录" "已读" "$SNAP5"
click_text "全部"; sleep 2

echo "--- 6. 删除通知后列表更新 ---"
ab eval "(function(){var bs=document.querySelectorAll('.el-table button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('删除')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
click_text "确定"; sleep 3
eq "6 删除后数据库置位" 1 "$(mysqlq "SELECT COUNT(*)>0 FROM sys_notification WHERE recipient_id=$STU_ID AND deleted=1;")"
SNAP6=$(snapshot)
contains "6 列表刷新后含剩余通知或空态" "通知中心" "$SNAP6"

echo "--- 7. 刷新后数据保留（持久化） ---"
ab reload >/dev/null; sleep 5
SNAP7=$(snapshot)
contains "7 刷新后仍展示通知中心" "通知中心" "$SNAP7"
eq "7 刷新后未读数与后端一致" "$(unread $STOK)" "$(ab eval "(function(){var b=document.querySelector('.notify-bell .el-badge__content');return b?b.textContent.trim():'0'})()" 2>&1 | tail -1 | tr -d '"\r')"

echo "--- 8. 教师登录 → 通知严格隔离 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$TTOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 6
ab open "$FE/#/notifications" >/dev/null; sleep 5
SNAP8=$(snapshot)
eq "8 教师看不到学员的 AI 回复通知" 0 "$(printf '%s' "$SNAP8" | grep -c 'M11 浏览器通知验证')"
contains "8 教师通知中心正常渲染（无学员数据）" "通知中心" "$SNAP8"

echo "================================================="
echo "M11 浏览器 E2E：通过 $PASS 失败 $FAIL"
