#!/bin/bash
# M8 浏览器 E2E：Prompt 管理页面（直发模式）+ AI 工作台智能体标识
#   管理员：进入 Prompt 页 → 五类模板 → 编辑/预览/发布 → 成功提示 → 刷新保留新版本
#   学员：访问 Prompt 页被路由守卫拦截；AI 工作台提问课程 → 显示「课程推荐」Agent 标识
# 前置：services up + 前端 5173
# 用法: bash scripts/e2e-m8-browser.sh
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
ab() { agent-browser "$@" 2>&1 | tail -1; }
clean() { printf '%s' "$1" | tr -d '"'; }
type_text() {
  ab eval "(function(){var t=document.querySelector('.ai-input textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'$1');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
  sleep 1
  ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发送')>=0){bs[i].click();return 'sent'}}return 'nf'})()" >/dev/null
}

echo "================ M8 浏览器 E2E ================"
TS=$(date +%s)
ATOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
STOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"student01","password":"student123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

echo "--- 1. 管理员进入 Prompt 模板管理页 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$ATOK'); 'ok'" >/dev/null
# 先进入无角色门槛的工作台完成 profile/roles 拉取，再 SPA 内导航到管理页（避免硬刷新竞态）
ab reload >/dev/null; sleep 5
ab open "$FE/#/admin/ai/prompts" >/dev/null; sleep 5
SNAP=$(agent-browser snapshot 2>&1)
contains "页面标题" "Prompt 模板管理" "$SNAP"
contains "五类 Agent 标签" "课程推荐" "$SNAP"
contains "Nacos 状态标签" "Nacos" "$SNAP"
contains "直发模式标识（草稿/版本/回滚未启用）" "直发模式" "$SNAP"

echo "--- 2. 编辑知识讲解模板并预览 ---"
ab eval "(function(){var tabs=document.querySelectorAll('.el-tabs__item');for(var i=0;i<tabs.length;i++){if(tabs[i].textContent.indexOf('知识讲解')>=0){tabs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
MARK="B8MARK-$TS"
ab eval "(function(){var t=document.querySelector('.pa-body textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'你是「课栈智伴」的知识讲解助手，负责通俗讲解技术概念。平台 RAG 知识库尚未上线，相关需求请等待后续版本。标记：$MARK');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
sleep 1
ab eval "(function(){var bs=document.querySelectorAll('.pa-ops button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('预览校验')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 3
SNAP2=$(agent-browser snapshot 2>&1)
contains "预览校验通过" "校验通过" "$SNAP2"

echo "--- 3. 发布到 Nacos ---"
ab eval "(function(){var bs=document.querySelectorAll('.pa-ops button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发布到 Nacos')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
ab eval "(function(){var bs=document.querySelectorAll('.el-dialog__footer button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('确认发布')>=0){bs[i].click();return 'ok'}}return 'nobtn'})()" >/dev/null
sleep 2
SNAP3=$(agent-browser snapshot 2>&1)
contains "发布成功提示" "发布成功" "$SNAP3"
# 后端核验：Nacos 与生效内容确实更新
NACOS_HAS=$(curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=kzai-ai-prompts.json&group=tj.ai" | grep -c "$MARK")
chk "Nacos 实际写入标记" "1" "$( [ "$NACOS_HAS" -ge 1 ] && echo 1 || echo 0 )"

echo "--- 4. 刷新后新版本保留 ---"
ab open "$FE/#/ai" >/dev/null; sleep 4
ab open "$FE/#/admin/ai/prompts" >/dev/null; sleep 4
ab eval "(function(){var tabs=document.querySelectorAll('.el-tabs__item');for(var i=0;i<tabs.length;i++){if(tabs[i].textContent.indexOf('知识讲解')>=0){tabs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
SNAP4=$(agent-browser snapshot 2>&1)
contains "刷新后版本标记仍在（生效快照）" "$MARK" "$SNAP4"

echo "--- 5. 学员访问 Prompt 页被拦截（F-43 权限）---"
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
# 强制整页重载以重建 Pinia store（清除上一步管理员内存态），再走守卫
ab open "$FE/#/courses" >/dev/null; sleep 1
ab reload >/dev/null; sleep 6
ab open "$FE/#/admin/ai/prompts" >/dev/null; sleep 4
CUR=$(ab get url)
chk "学员被重定向离开管理页" "1" "$(printf '%s' "$CUR" | grep -c '/admin/ai/prompts' | awk '{print ($1==0)?1:0}')"

echo "--- 6. AI 工作台显示智能体标识 ---"
ab open "$FE/#/ai" >/dev/null; sleep 3
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('新建')>=0){bs[i].click();return 'c'}}return 'nf'})()" >/dev/null
sleep 3
type_text "推荐一些Java入门课程"
sleep 12
SNAP6=$(agent-browser snapshot 2>&1)
contains "显示「课程推荐」Agent 标识" "课程推荐" "$SNAP6"
contains "回复仍显著标识 MOCK" "MOCK" "$SNAP6"

echo "--- 7. XSS 防护延续 ---"
BAD=$(ab eval "(function(){var els=document.querySelectorAll('.ai-messages *');var bad=0;for(var i=0;i<els.length;i++){var at=els[i].attributes;for(var j=0;j<at.length;j++){if(/^on/i.test(at[j].name))bad++;}}return bad})()")
chk "消息与标识不含 on* 事件属性" "0" "${BAD//\"/}"

ab screenshot /tmp/prompt-admin.png >/dev/null 2>&1
echo "================================================="
echo "M8 浏览器 E2E：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
