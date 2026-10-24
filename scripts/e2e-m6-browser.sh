#!/bin/bash
# M6 浏览器 E2E：AI 工作台真实交互
#   登录 → 新建会话 → 流式发送 → 刷新恢复 → 切换会话 → 停止生成 → 重命名 → 删除 → XSS 防护
# 前置：scripts/run-services.sh start，且前端 npm run dev 已在 5173 监听
# 用法: bash scripts/e2e-m6-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0

chk() {
  local name="$1" expect="$2" actual="$3"
  if [ "$expect" = "$actual" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$name" "$actual"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$name" "$expect" "$actual"; fi
}
contains() {
  local name="$1" needle="$2" hay="$3"
  if printf '%s' "$hay" | grep -qF -- "$needle"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$name"
  else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$name" "$needle"; fi
}
# 输出清理：去掉首尾引号
ab() { agent-browser "$@" 2>&1 | tail -1; }
clean() { printf '%s' "$1" | tr -d '"'; }

echo "================ M6 浏览器 E2E ================"
TS=$(date +%s)

S=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"student01","password":"student123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
[ -n "$S" ] || { echo "登录失败"; exit 1; }

echo "--- 0. 注入登录态并进入 AI 工作台 ---"
ab open "$FE/#/login" >/dev/null
sleep 3
ab eval "localStorage.setItem('kzai_token','$S'); 'ok'" >/dev/null
ab open "$FE/#/ai" >/dev/null
sleep 1
ab reload >/dev/null
sleep 5
chk "登录后停留在 AI 工作台" "$FE/#/ai" "$(ab get url)"

echo "--- 1. 三栏布局与关键区域 ---"
SNAP=$(agent-browser snapshot 2>&1)
contains "左侧会话列表" "会话" "$SNAP"
contains "新建会话按钮" "新建" "$SNAP"
contains "输入框存在" "Enter 发送" "$SNAP"
contains "右侧热门问题" "热门问题" "$SNAP"
contains "右侧模型状态" "模型状态" "$SNAP"
contains "右侧欢迎语" "欢迎语" "$SNAP"

echo "--- 2. 新建会话 ---"
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('新建')>=0){bs[i].click();return 'c'}}return 'nf'})()" >/dev/null
sleep 3

echo "--- 3. 发送第一条消息（流式）---"
KEY="M6E2E-$TS"
ab eval "(function(){var t=document.querySelector('.ai-input textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'请介绍一下 ' + '$KEY');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
sleep 1
ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发送')>=0){bs[i].click();return 'sent'}}return 'nf'})()" >/dev/null
sleep 12
SNAP2=$(agent-browser snapshot 2>&1)
contains "用户消息已渲染" "$KEY" "$SNAP2"
contains "AI 回复已渲染" "MOCK" "$SNAP2"
contains "MOCK 模式显著标识" "MOCK 模式" "$SNAP2"

echo "--- 4. 刷新后恢复历史会话 ---"
ab open "$FE/#/ai" >/dev/null 2>&1
sleep 5
SNAP3=$(agent-browser snapshot 2>&1)
contains "刷新后历史会话仍在列表" "$KEY" "$SNAP3"

echo "--- 5. 切换会话并加载其历史消息 ---"
ab eval "(function(){var els=document.querySelectorAll('.ai-session');if(!els.length)return 'none';els[0].click();return 'clicked'})()" >/dev/null
sleep 4
SNAP4=$(agent-browser snapshot 2>&1)
contains "切换后历史消息已加载" "$KEY" "$SNAP4"

echo "--- 6. 停止生成（生成中点击停止）---"
ab eval "(function(){var t=document.querySelector('.ai-input textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'这是一个需要被停止的长问题');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
sleep 1
ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发送')>=0){bs[i].click();return 'sent'}}return 'nf'})()" >/dev/null
sleep 0.5
R=$(ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('停止')>=0){bs[i].click();return 'stop-clicked'}}return 'nostop'})()")
chk "生成中停止按钮可用" "stop-clicked" "$(clean "$R")"
sleep 3
SNAP5=$(agent-browser snapshot 2>&1)
contains "停止提示已展示" "停止" "$SNAP5"

echo "--- 7. 重命名会话 ---"
ab eval "(function(){var els=document.querySelectorAll('.ai-session');if(!els.length)return 'none';var ops=els[0].querySelectorAll('.ai-session__ops .el-icon');if(ops.length>=1){ops[0].click();return 'rename-clicked'}return 'noops'})()" >/dev/null
sleep 2
NEW_TITLE="M6重命名-$TS"
ab eval "(function(){var b=document.querySelector('.el-message-box input');if(!b)return 'noinput';var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(b,'$NEW_TITLE');b.dispatchEvent(new Event('input',{bubbles:true}));return 'typed'})()" >/dev/null
sleep 1
ab eval "(function(){var bs=document.querySelectorAll('.el-message-box__btns button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.replace(/\s+/g,'')==='确定'){bs[i].click();return 'ok'}}return 'nobtn'})()" >/dev/null
sleep 3
ab open "$FE/#/ai" >/dev/null 2>&1
sleep 4
SNAP6=$(agent-browser snapshot 2>&1)
contains "重命名后标题生效" "$NEW_TITLE" "$SNAP6"

echo "--- 8. Markdown 容器已渲染 ---"
MDCNT=$(ab eval "(function(){return document.querySelectorAll('.ai-msg--assistant .md').length})()")
chk "AI 消息使用 Markdown 容器渲染" "true" "$([ "${MDCNT//[!0-9]/}" -gt 0 ] && echo true || echo false)"

echo "--- 9. XSS 防护：已渲染内容不含 on* 事件属性 ---"
BAD=$(ab eval "(function(){var els=document.querySelectorAll('.ai-messages *');var bad=0;for(var i=0;i<els.length;i++){var at=els[i].attributes;for(var j=0;j<at.length;j++){if(/^on/i.test(at[j].name))bad++;}}return bad})()")
chk "已渲染内容不含 on* 事件属性" "0" "${BAD//\"/}"

echo "--- 10. 删除会话 ---"
ab eval "(function(){var els=document.querySelectorAll('.ai-session');if(!els.length)return 'none';var ops=els[0].querySelectorAll('.ai-session__ops .el-icon');if(ops.length>=2){ops[1].click();return 'del-clicked'}return 'noops'})()" >/dev/null
sleep 2
ab eval "(function(){var bs=document.querySelectorAll('.el-message-box__btns button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.replace(/\s+/g,'')==='确定'){bs[i].click();return 'ok'}}return 'nobtn'})()" >/dev/null
sleep 3
ab open "$FE/#/ai" >/dev/null 2>&1
sleep 4
SNAP7=$(agent-browser snapshot 2>&1)
if printf '%s' "$SNAP7" | grep -qF -- "$NEW_TITLE"; then
  FAIL=$((FAIL+1)); printf "  [FAIL] 删除后会话仍在列表\n"
else
  PASS=$((PASS+1)); printf "  [PASS] 删除后会话已从列表移除\n"
fi

ab screenshot /tmp/ai-workbench.png >/dev/null 2>&1
echo "================================================="
echo "M6 浏览器 E2E：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
