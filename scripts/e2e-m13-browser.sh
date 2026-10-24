#!/bin/bash
# M13 浏览器 E2E：看板/会话监管/问答监管/操作日志/系统参数/事件运维/权限
# 用法: bash scripts/e2e-m13-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH:/Users/djanjo/opt/tools/redis/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0
contains() { if printf '%s' "$3" | grep -qF -- "$2"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$1"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$1" "$2"; fi; }
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
ab() { agent-browser "$@" 2>&1 | tail -1; }
snapshot() { ab eval "(function(){return document.body.innerText.replace(/\n+/g,'|').slice(0,1800)})()" 2>&1 | tail -1; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
asAdmin() { ab open "$FE/#/login" >/dev/null; sleep 1; ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$ATOK'); 'ok'" >/dev/null; ab reload >/dev/null; sleep 5; ab open "$FE/#$1" >/dev/null; sleep 5; }
clickText() { ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('$1')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null; }

echo "================ M13 浏览器 E2E ================"
ATOK=$(login admin admin123); STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
[ -n "$ATOK" ] || { echo "admin 登录失败"; exit 1; }

echo "--- 1. 运营看板 ---"
asAdmin "/admin/dashboard"
S1=$(snapshot)
contains "1 指标卡片（用户数）" "用户数" "$S1"
contains "1 指标卡片（课程数）" "课程数" "$S1"
contains "1 今日 AI 会话数" "今日 AI 会话数" "$S1"
contains "1 模型指标 MOCK 隔离提示" "暂无真实模型数据" "$S1"
contains "1 数据更新时间" "数据更新时间" "$S1"
ab eval "(function(){var bs=document.querySelectorAll('.el-radio-button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('最近 7 天')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 3
S2=$(snapshot)
contains "1 7 天窗口趋势" "会话增长趋势" "$S2"

echo "--- 2. 会话监管 ---"
asAdmin "/admin/supervision/sessions"
S3=$(snapshot)
contains "2 会话列表渲染" "会话 ID" "$S3"
clickText "详情"; sleep 2
S4=$(snapshot)
contains "2 会话详情弹窗（消息可见）" "复核历史" "$S4"

echo "--- 3. 问答监管 ---"
asAdmin "/admin/supervision/questions"
S5=$(snapshot)
contains "3 问答列表渲染" "课程问答监管" "$S5"

echo "--- 4. 操作日志 ---"
asAdmin "/admin/operation-logs"
S6=$(snapshot)
contains "4 日志列表渲染" "操作日志" "$S6"
contains "4 登录审计可见" "LOGIN_SUCCESS" "$S6"
contains "4 导出按钮存在" "导出 CSV" "$S6"

echo "--- 5. 系统参数（草稿→发布→生效状态） ---"
asAdmin "/admin/system-settings"
S7=$(snapshot)
contains "5 参数列表（TopK）" "检索 TopK" "$S7"
ab eval "(function(){var rows=document.querySelectorAll('.el-table__row');for(var i=0;i<rows.length;i++){if(rows[i].innerText.indexOf('knowledge.top-k')>=0){var cs=rows[i].querySelectorAll('.el-input__inner');if(cs.length){cs[0].value='9';cs[0].dispatchEvent(new Event('input'));return 'ok'}}}return 'nf'})()" >/dev/null
ab eval "(function(){var rows=document.querySelectorAll('.el-table__row');for(var i=0;i<rows.length;i++){if(rows[i].innerText.indexOf('knowledge.top-k')>=0){var bs=rows[i].querySelectorAll('button');for(var j=0;j<bs.length;j++){if(bs[j].textContent.indexOf('保存草稿')>=0){bs[j].click();return 'ok'}}}}return 'nf'})()" >/dev/null; sleep 2
S8=$(snapshot)
contains "5 草稿保存反馈" "草稿已保存" "$S8"
# 刷新后草稿仍在（持久化）——精确断言 TopK 行草稿输入值
ab reload >/dev/null; sleep 5
D9=$(ab eval "(function(){var rows=document.querySelectorAll('.el-table__row');for(var i=0;i<rows.length;i++){if(rows[i].innerText.indexOf('knowledge.top-k')>=0){var cs=rows[i].querySelectorAll('.el-input__inner');return cs.length?cs[0].value:'no-input'}}return 'no-row'})()" 2>&1 | tail -1 | tr -d '"\r')
eq "5 刷新后草稿保持(9)" "9" "$D9"
# 恢复：把草稿改回 6 并发布
ab eval "(function(){var rows=document.querySelectorAll('.el-table__row');for(var i=0;i<rows.length;i++){if(rows[i].innerText.indexOf('knowledge.top-k')>=0){var cs=rows[i].querySelectorAll('.el-input__inner');if(cs.length){cs[0].value='6';cs[0].dispatchEvent(new Event('input'));return 'ok'}}}return 'nf'})()" >/dev/null
ab eval "(function(){var rows=document.querySelectorAll('.el-table__row');for(var i=0;i<rows.length;i++){if(rows[i].innerText.indexOf('knowledge.top-k')>=0){var bs=rows[i].querySelectorAll('button');for(var j=0;j<bs.length;j++){if(bs[j].textContent.indexOf('保存草稿')>=0){bs[j].click();return 'ok'}}}}return 'nf'})()" >/dev/null; sleep 2
clickText "发布配置"; sleep 2
ab eval "(function(){var bs=document.querySelectorAll('.el-message-box button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('确定')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 3
S10=$(snapshot)
contains "5 发布完成反馈" "发布完成" "$S10"

echo "--- 6. 事件运维 ---"
asAdmin "/admin/outbox"
S11=$(snapshot)
contains "6 Outbox 页面渲染" "失败通知事件运维" "$S11"

echo "--- 7. 学员/教师无法进入管理员页面 ---"
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
ab open "$FE/#/admin/supervision/sessions" >/dev/null; sleep 4
S12=$(snapshot)
if printf '%s' "$S12" | grep -qF "AI 会话监管"; then FAIL=$((FAIL+1)); printf "  [FAIL] 7 学员不应看到会话监管\n"; else PASS=$((PASS+1)); printf "  [PASS] 7 学员无法进入会话监管\n"; fi
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$TTOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
ab open "$FE/#/admin/dashboard" >/dev/null; sleep 4
S13=$(snapshot)
if printf '%s' "$S13" | grep -qF "运营数据看板"; then FAIL=$((FAIL+1)); printf "  [FAIL] 7 教师不应看到运营看板\n"; else PASS=$((PASS+1)); printf "  [PASS] 7 教师无法进入运营看板\n"; fi
ab eval "localStorage.clear(); 'ok'" >/dev/null

echo "================ M13 浏览器 E2E 完成：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" -eq 0 ]
