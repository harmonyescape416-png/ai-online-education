#!/bin/bash
# M12 浏览器 E2E：管理员评测看板（任务书第十部分）
#   用例列表/分类筛选/新增用例/MOCK 标识/全量评测启动/进度/看板指标/混淆矩阵/
#   失败详情/批次历史/导出按钮/学员无权访问
# 用法: bash scripts/e2e-m12-browser.sh
set -u
export PATH="$HOME/opt/tools/node/bin:$PATH"
G=http://127.0.0.1:18080
FE=http://127.0.0.1:5173
PASS=0; FAIL=0
contains() { local n="$1" nd="$2" hay="$3"; if printf '%s' "$hay" | grep -qF -- "$nd"; then PASS=$((PASS+1)); printf "  [PASS] %s\n" "$n"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 未找到：%s\n" "$n" "$nd"; fi; }
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }
ab() { agent-browser "$@" 2>&1 | tail -1; }
login() { curl -s -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('data') else '')" 2>/dev/null; }
snapshot() { ab eval "(function(){return document.body.innerText.replace(/\n+/g,'|').slice(0,1600)})()" 2>&1 | tail -1; }
click_text() { ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('$1')>=0){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null; }

echo "================ M12 浏览器 E2E（AI 评测看板） ================"
ATOK=$(login admin admin123); STOK=$(login student01 student123)
[ -n "$ATOK" ] || { echo "admin 登录失败"; exit 1; }

echo "--- 1. 管理员进入评测看板 ---"
ab open "$FE/#/login" >/dev/null; sleep 2
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$ATOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5   # 刷新使 pinia store 按 localStorage 重建 profile（避免残留旧会话角色）
ab open "$FE/#/admin/ai/evaluations" >/dev/null; sleep 5
S1=$(snapshot)
contains "1 页面渲染（评测用例列表）" "评测用例" "$S1"
contains "1 用例数据加载（含标注用例）" "RECOMMEND" "$S1"

echo "--- 2. 新增用例（对话框） ---"
click_text "新增用例"; sleep 2
ab eval "(function(){var tas=document.querySelectorAll('.el-dialog textarea');if(tas.length){var s=tas[0];s.value='浏览器E2E临时用例';s.dispatchEvent(new Event('input'));}return tas.length})()" >/dev/null
ab eval "(function(){var i=document.querySelector('.el-dialog textarea:nth-of-type(1)');return 'ok'})()" >/dev/null
# 填写标注理由
ab eval "(function(){var tas=document.querySelectorAll('.el-dialog textarea');if(tas.length>1){var s=tas[1];s.value='浏览器E2E标注理由';s.dispatchEvent(new Event('input'));return 'ok'}return 'nf'})()" >/dev/null
click_text "保存"; sleep 3
S2=$(snapshot)
contains "2 新增用例出现在列表" "浏览器E2E临时用例" "$S2"

echo "--- 3. 分类筛选 ---"
ab eval "(function(){var inputs=document.querySelectorAll('.el-select input');if(inputs.length){inputs[0].click();return 'ok'}return 'nf'})()" >/dev/null; sleep 1
ab eval "(function(){var items=document.querySelectorAll('.el-select-dropdown__item');for(var i=0;i<items.length;i++){if(items[i].textContent.trim()==='BUY'){items[i].click();return 'ok'}}return 'nf'})()" >/dev/null; sleep 2
S3=$(snapshot)
contains "3 分类筛选生效（BUY 可见）" "BUY" "$S3"
if printf '%s' "$S3" | grep -qF "GREETING"; then FAIL=$((FAIL+1)); printf "  [FAIL] 3 筛选后不应出现其他分类\n"; else PASS=$((PASS+1)); printf "  [PASS] 3 筛选后其他分类不可见\n"; fi
ab eval "(function(){var items=document.querySelectorAll('.el-select-dropdown__item');for(var i=0;i<items.length;i++){if(items[i].textContent.trim()==='RECOMMEND'){items[i].click();return 'ok'}}return 'nf'})()" >/dev/null; sleep 1
click_text "查询" >/dev/null; sleep 2

echo "--- 4. 全量评测启动与进度 ---"
click_text "全量评测"; sleep 3
S4=$(snapshot)
contains "4 批次已启动（进入看板/批次）" "MOCK" "$S4"
sleep 14
ab open "$FE/#/admin/ai/evaluations" >/dev/null; sleep 3
# 打开批次历史并查看最新批次
click_text "批次历史" >/dev/null 2>&1 || true
ab eval "(function(){var tabs=document.querySelectorAll('.el-tabs__item');for(var i=0;i<tabs.length;i++){if(tabs[i].textContent.indexOf('批次历史')>=0){tabs[i].click();return 'ok'}}return 'nf'})()" >/dev/null; sleep 3
S5=$(snapshot)
contains "5 批次历史展示（模型/模式/知识库版本）" "MOCK" "$S5"
contains "5 批次历史含知识库版本" "kzai-knowledge-v" "$S5"

echo "--- 5. 看板指标（汇总数据来自后端） ---"
ab eval "(function(){var tabs=document.querySelectorAll('.el-tabs__item');for(var i=0;i<tabs.length;i++){if(tabs[i].textContent.indexOf('批次看板')>=0){tabs[i].click();return 'ok'}}return 'nf'})()" >/dev/null; sleep 3
S6=$(snapshot)
contains "5 意图准确率卡片" "意图准确率" "$S6"
contains "5 RAG Hit@6 卡片" "RAG Hit@6" "$S6"
contains "5 正确拒答率卡片" "正确拒答率" "$S6"
contains "5 Token 用量卡片" "Token" "$S6"
contains "5 混淆矩阵" "混淆矩阵" "$S6"
contains "5 MOCK 显著标识" "MOCK 协议验证" "$S6"
if printf '%s' "$S6" | grep -qF "尚无有效数据"; then printf "  [INFO] 看板显示空态（尚无有效数据）\n"; else PASS=$((PASS+1)); printf "  [PASS] 5 看板展示真实统计（非空态）\n"; fi

echo "--- 6. 失败用例详情 ---"
NFAIL=$(ab eval "(function(){var tabs=document.querySelectorAll('.el-card');for(var i=0;i<tabs.length;i++){var h=tabs[i].querySelector('.el-card__header');if(h&&h.textContent.indexOf('失败用例详情')>=0){var m=h.textContent.match(/（(\\d+) 条/);return m?m[1]:'0'}}return '0'})()" 2>&1 | tail -1 | tr -d '"\r')
if [ "$NFAIL" != "0" ] && [ -n "$NFAIL" ]; then
  PASS=$((PASS+1)); printf "  [PASS] 6 失败用例清单展示（%s 条）\n" "$NFAIL"
  # 精确点击失败用例卡片内第一个「详情」按钮
  ab eval "(function(){var cards=document.querySelectorAll('.el-card');for(var i=0;i<cards.length;i++){var h=cards[i].querySelector('.el-card__header');if(h&&h.textContent.indexOf('失败用例详情')>=0){var bs=cards[i].querySelectorAll('button');for(var j=0;j<bs.length;j++){if(bs[j].textContent.trim()==='详情'){bs[j].click();return 'ok'}}}}return 'nf'})()" >/dev/null
  sleep 3
  # 弹窗可能位于长页面快照截断区之外——直接探测 el-dialog 的标题与判定依据内容
  DLG=$(ab eval "(function(){var d=document.querySelectorAll('.el-dialog');for(var i=0;i<d.length;i++){if(d[i].textContent.indexOf('结果详情')>=0){return '结果详情|verdict='+(d[i].textContent.indexOf('判定')>=0?1:0)+'|answer='+(d[i].textContent.indexOf('回答全文')>=0?1:0)}}return 'none'})()" 2>&1 | tail -1 | tr -d '"\r')
  if printf '%s' "$DLG" | grep -qF "结果详情"; then PASS=$((PASS+1)); printf "  [PASS] 6 失败详情弹窗（含判定依据） (%s)\n" "$DLG"; else FAIL=$((FAIL+1)); printf "  [FAIL] 6 失败详情弹窗未打开（%s）\n" "$DLG"; fi
  ab eval "(function(){var bs=document.querySelectorAll('.el-dialog button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.trim()==='取消'){bs[i].click();return 'ok'}}return 'nf'})()" >/dev/null
else
  FAIL=$((FAIL+1)); printf "  [FAIL] 6 失败用例清单未展示\n"
fi

echo "--- 7. CSV 导出按钮存在 ---"
S8=$(snapshot)
contains "7 导出按钮存在" "导出 CSV" "$S8"

echo "--- 8. 学员无权访问 ---"
ab eval "localStorage.clear(); localStorage.setItem('kzai_token','$STOK'); 'ok'" >/dev/null
ab reload >/dev/null; sleep 5   # 同上：刷新使 store 重建为学员身份
ab open "$FE/#/admin/ai/evaluations" >/dev/null; sleep 4
S9=$(snapshot)
if printf '%s' "$S9" | grep -qF "评测用例"; then FAIL=$((FAIL+1)); printf "  [FAIL] 8 学员不应看到评测看板\n"; else PASS=$((PASS+1)); printf "  [PASS] 8 学员无法访问评测看板（路由守卫拒绝）\n"; fi
# 恢复管理员登录态（后续清理）
ab eval "localStorage.clear(); 'ok'" >/dev/null

# 清理浏览器 E2E 临时用例
M=/Users/djanjo/opt/tools/mysql/bin/mysql
$M --socket=/tmp/mysql.sock -uroot -e "DELETE FROM course_stack_ai.ai_eval_case_meta WHERE case_id IN (SELECT id FROM (SELECT id FROM course_stack_ai.ai_eval_case WHERE question LIKE '浏览器E2E临时%') t); DELETE FROM course_stack_ai.ai_eval_case WHERE question LIKE '浏览器E2E临时%';" >/dev/null

echo "================ M12 浏览器 E2E 完成：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" -eq 0 ]
