#!/bin/bash
# M10 浏览器 E2E：学员提问 → AI 回复状态轮询 → 来源标识 → 追问；教师修正/精华
# 用法: bash scripts/e2e-m10-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0
contains() { local n="$1" nd="$2" hay="$3"; if printf '%s' "$hay" | grep -qF -- "$nd"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$n"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$n" "$nd"; fi; }
ab() { agent-browser "$@" 2>&1 | tail -1; }
type_text() {
  ab eval "(function(){var t=document.querySelector('$2');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'$1');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
  sleep 1
  ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('$3')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
}

echo "================ M10 浏览器 E2E ================"
TS=$(date +%s)
STOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"student01","password":"student123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
ATOK=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

echo "--- 1. 学员提问 → AI 回复轮询出现 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
ab open "$FE/#/questions" >/dev/null; sleep 4
# 先切“我的问答”视图（列表不依赖课程 ID），再填课程 ID 提问
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('我的问答')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
ab eval "(function(){var i=document.querySelector('.qa-form input');if(!i)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(i,'1001');i.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
# 分别填标题与描述（两个 textarea），再点提交
ab eval "(function(){var ins=document.querySelectorAll('.qa-form input');if(ins.length<2)return 'noinput';var si=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;si.call(ins[1],'M10 浏览器提问 $TS');ins[1].dispatchEvent(new Event('input',{bubbles:true}));var t=document.querySelector('.qa-form textarea');var st=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;st.call(t,'请介绍该课程的学习方法与退款规则。');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
ab eval "(function(){var bs=document.querySelectorAll('.qa-form button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('提交问题')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 1
SNAP1=$(agent-browser snapshot 2>&1)
contains "提问成功提示" "提问成功" "$SNAP1"
# 打开最新详情（第一行）
ab eval "(function(){var bs=document.querySelectorAll('.el-table button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('详情')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 6
SNAP2=$(agent-browser snapshot 2>&1)
contains "AI 回复出现（来源标识）" "AI 回复" "$SNAP2"
contains "MOCK 显著标识（可追溯）" "MOCK" "$SNAP2"

echo "--- 2. 学员追问 ---"
ab eval "(function(){var t=document.querySelector('.el-dialog textarea');var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'追问：退款期限是多久？');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
sleep 1
ab eval "(function(){var bs=document.querySelectorAll('.el-dialog button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.trim()==='回复问题'){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 1
SNAP3=$(agent-browser snapshot 2>&1)
contains "追问成功" "回复成功" "$SNAP3"
contains "追问内容展示" "退款期限" "$SNAP3"

echo "--- 3. 教师端管理：修正 AI 回复 + 精华 ---"
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$ATOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5
ab open "$FE/#/teacher/questions" >/dev/null; sleep 4
SNAP4=$(agent-browser snapshot 2>&1)
contains "教师问答列表" "问答管理" "$SNAP4"
ab eval "(function(){var bs=document.querySelectorAll('.el-table button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('管理')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 4
ab eval "(function(){var bs=document.querySelectorAll('.el-dialog button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('人工修正')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 2
sleep 2
ab eval "(function(){var t=document.querySelector('.el-message-box textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'教师修正后的权威回答 RUN-${TS}');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
ab eval "(function(){var bs=document.querySelectorAll('.el-message-box button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('确定')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 1
SNAP5=$(agent-browser snapshot 2>&1)
contains "人工修正成功" "已修正" "$SNAP5"
ab eval "(function(){var bs=document.querySelectorAll('.el-dialog button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('设为精华')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
sleep 1
SNAP6=$(agent-browser snapshot 2>&1)
contains "精华标记展示" "精华" "$SNAP6"

ab screenshot /tmp/m10-teacher-qa.png >/dev/null 2>&1
echo "================================================="
echo "M10 浏览器 E2E：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
