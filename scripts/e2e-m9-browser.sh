#!/bin/bash
# M9 浏览器 E2E：知识库管理页（真实上传/发布/检索测试）+ AI 工作台 RAG 来源引用
# 用法: bash scripts/e2e-m9-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0
chk() { local n="$1" e="$2" a="$3"; if [ "$e" = "$a" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$n" "$a"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$n" "$e" "$a"; fi; }
contains() { local n="$1" nd="$2" hay="$3"; if printf '%s' "$hay" | grep -qF -- "$nd"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$n"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$n" "$nd"; fi; }
ab() { agent-browser "$@" 2>&1 | tail -1; }
type_text() {
  ab eval "(function(){var t=document.querySelector('.ai-input textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'$1');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
  sleep 1
  ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发送')>=0){bs[i].click();return 'sent'}}return 'nf'})()" >/dev/null
}

echo "================ M9 浏览器 E2E ================"
TS=$(date +%s)
ATOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
STOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"student01","password":"student123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 准备文档（内容唯一）
printf '# M9 浏览器验证文档（%s）\n\n## 购买规则\n开课后 7 天内可申请全额退款。\n' "$TS" > /tmp/kzai_m9_web.md

echo "--- 1. 管理员进入知识库管理页 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$ATOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
ab open "$FE/#/ai" >/dev/null; sleep 4
ab open "$FE/#/admin/ai/knowledge" >/dev/null; sleep 4
SNAP=$(agent-browser snapshot 2>&1)
contains "页面标题" "AI 知识库管理" "$SNAP"
contains "检索测试区" "检索测试" "$SNAP"

echo "--- 2. 真实上传 → 发布 → 索引成功 ---"
UPJS="(function(){var inp=document.querySelector('input[type=file]');if(!inp)return 'noinput';var dt=new DataTransfer();dt.items.add(new File(['# M9 上传标记 RUN-${TS} 开课后 7 天内可申请全额退款。'],'m9web-$TS.md',{type:'text/markdown'}));inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'})()"
ab eval "$UPJS" >/dev/null; sleep 2
SNAP2=$(agent-browser snapshot 2>&1)
contains "上传成功提示" "上传成功" "$SNAP2"
# 审核发布第一行
ab eval "(function(){var bs=document.querySelectorAll('.el-table button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('审核发布')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
ab eval "(function(){var bs=document.querySelectorAll('.el-message-box__btns button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.replace(/\\s+/g,'')==='确定'){bs[i].click();return 'ok'}}return 'nobtn'})()" >/dev/null
sleep 8
SNAP3=$(agent-browser snapshot 2>&1)
contains "索引成功状态展示" "索引成功" "$SNAP3"

echo "--- 3. 检索测试展示真实 ES 片段 ---"
ab eval "(function(){var t=document.querySelector('.kb-search input');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(t,'# M9 上传标记 RUN-${TS} 开课后 7 天内可申请全额退款。');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
ab eval "(function(){var bs=document.querySelectorAll('.kb-search button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('检索测试')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 5
SNAP4=$(agent-browser snapshot 2>&1)
contains "检索命中真实片段" "M9 上传标记" "$SNAP4"
contains "展示相似度列" "相似度" "$SNAP4"

echo "--- 4. 学员工作台 RAG 对话显示来源引用 ---"
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
ab open "$FE/#/courses" >/dev/null; sleep 1
ab reload >/dev/null; sleep 5
ab open "$FE/#/ai" >/dev/null; sleep 4
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('新建')>=0){bs[i].click();return 'c'}}return 'nf'})()" >/dev/null
sleep 3
# 发送与切片一致的文本（Mock 向量协议：确定性命中）
type_text "# M9 上传标记 RUN-${TS} 开课后 7 天内可申请全额退款。"
sleep 14
SNAP5=$(agent-browser snapshot 2>&1)
contains "回复引用来源文档" "M9 上传标记" "$SNAP5"
RAGCARD=$(ab eval "(function(){var r={};document.querySelectorAll('.ai-messages *').forEach(function(el){});var n=0;var cards=document.querySelectorAll('.ai-msg');cards.forEach(function(m){});return document.body.innerHTML.indexOf('RAG_SOURCE')>=0?'tagged':'none'})()")
contains "工作台渲染来源（RAG_SOURCE 卡片或引用文本）" "根据知识库" "$SNAP5"

echo "--- 5. 学员访问知识库管理被拦截 ---"
ab open "$FE/#/admin/ai/knowledge" >/dev/null; sleep 4
CUR=$(ab get url)
chk "学员被重定向离开管理页" "1" "$(printf '%s' "$CUR" | grep -c '/admin/ai/knowledge' | awk '{print ($1==0)?1:0}')"

echo "================================================="
echo "M9 浏览器 E2E：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
