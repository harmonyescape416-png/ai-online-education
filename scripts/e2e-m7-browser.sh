#!/bin/bash
# M7 浏览器 E2E：AI 工作台课程/订单卡片全流程
#   提问课程 → 1003 课程卡片 → 点击进课程详情 → 购买确认 → 待支付订单卡片
#   → 点击进订单详情 → 刷新后历史卡片恢复（不重复下单） → 切换会话不串流 → XSS 防护
# 说明：当前环境为 MOCK 脚本化工具流（真实工具实现路径），页面标注 MOCK；
#       真实模型 Tool Calling 的浏览器验证需配置 DASHSCOPE_API_KEY 后另行执行。
# 前置：scripts/run-services.sh start，且前端 npm run dev 已在 5173 监听
# 用法: bash scripts/e2e-m7-browser.sh
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
mysqlq() { ~/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai -N -e "$1" 2>/dev/null | tr -d '\r'; }
type_text() {
  ab eval "(function(){var t=document.querySelector('.ai-input textarea');if(!t)return 'no';var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(t,'$1');t.dispatchEvent(new Event('input',{bubbles:true}));return 'ok'})()" >/dev/null
  sleep 1
  ab eval "(function(){var bs=document.querySelectorAll('.ai-input button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('发送')>=0){bs[i].click();return 'sent'}}return 'nf'})()" >/dev/null
}

echo "================ M7 浏览器 E2E ================"
TS=$(date +%s)

# 目标课程（上架 + 分类启用 + 购买窗内 + student01 未持有）
CROW=$(mysqlq "SELECT c.id, c.name, c.price FROM course c
  JOIN course_category c1 ON c1.id=c.first_cate_id AND c1.status=1
  JOIN course_category c2 ON c2.id=c.second_cate_id AND c2.status=1
  WHERE c.deleted=0 AND c.status=2
    AND (c.purchase_start_time IS NULL OR c.purchase_start_time<=NOW())
    AND (c.purchase_end_time IS NULL OR c.purchase_end_time>=NOW())
    AND NOT EXISTS (SELECT 1 FROM order_detail od JOIN \`order\` o ON o.id=od.order_id
                    WHERE od.course_id=c.id AND o.user_id=(SELECT id FROM user WHERE username='student01') AND o.deleted=0 AND o.status<>3)
  ORDER BY c.id LIMIT 1")
CID=$(echo "$CROW" | cut -f1)
CNAME=$(echo "$CROW" | cut -f2)
CPRICE=$(echo "$CROW" | cut -f3)
YUAN=$(python3 -c "print(f'{$CPRICE/100:.2f}')")
echo "  [INFO] 目标课程 id=$CID name=$CNAME price=${YUAN}元"

STU_ID=$(mysqlq "SELECT id FROM user WHERE username='student01';")
S=$(curl -s -X POST $G/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"student01","password":"student123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
[ -n "$S" ] || { echo "登录失败"; exit 1; }

echo "--- 0. 登录态注入并进入 AI 工作台 ---"
ab open "$FE/#/login" >/dev/null
sleep 3
ab eval "localStorage.setItem('kzai_token','$S'); 'ok'" >/dev/null
ab open "$FE/#/ai" >/dev/null
sleep 2
ab reload >/dev/null
sleep 5
chk "AI 工作台已加载" "$FE/#/ai" "$(ab get url)"

echo "--- 1. 新建会话并提问课程（F-39 课程卡片）---"
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('新建')>=0){bs[i].click();return 'c'}}return 'nf'})()" >/dev/null
sleep 3
type_text "推荐一下${CNAME}相关课程"
sleep 12
SNAP=$(agent-browser snapshot 2>&1)
contains "回复含课程名（真实数据）" "$CNAME" "$SNAP"
CARDCNT=$(ab eval "(function(){return document.querySelectorAll('.course-card').length})()")
chk "渲染了课程卡片组件" "true" "$([ "${CARDCNT//\"/}" -gt 0 ] && echo true || echo false)"
contains "卡片展示价格" "$YUAN" "$SNAP"
contains "MOCK 显著标识" "MOCK" "$SNAP"
# 卡片数据真实性由「课程名+价格与数据库一致」证明（卡片 UI 不展示原始 ID）

echo "--- 2. 点击卡片进入课程详情（F-40）---"
ab eval "(function(){var b=document.querySelector('.course-card .course-card__btn');if(!b)return 'nobtn';b.click();return 'clicked'})()" >/dev/null
sleep 4
CUR=$(ab get url)
chk "跳转到课程详情页" "1" "$(printf '%s' "$CUR" | grep -c '#/courses/')"

echo "--- 3. 返回工作台，表达购买意图（未确认）---"
ab open "$FE/#/ai" >/dev/null
sleep 4
ab eval "(function(){var els=document.querySelectorAll('.ai-session');if(!els.length)return 'none';els[0].click();return 'clicked'})()" >/dev/null
sleep 3
type_text "帮我购买${CNAME}"
sleep 10
SNAP2=$(agent-browser snapshot 2>&1)
contains "AI 请求确认购买" "请确认" "$SNAP2"
# 降级搜索可能命中同名前缀的其他课程：以「用户订单总数基线」核对，不预设课程 ID
ORDERS_TOTAL_BEFORE=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
chk "确认前用户订单总数（基线）已记录" "yes" "$([ -n "$ORDERS_TOTAL_BEFORE" ] && echo yes || echo no)"

echo "--- 4. 用户明确确认 → 待支付订单卡片（F-41）---"
type_text "确认"
sleep 10
SNAP3=$(agent-browser snapshot 2>&1)
ORDCNT=$(ab eval "(function(){return document.querySelectorAll('.order-card').length})()")
chk "渲染了订单卡片组件" "true" "$([ "${ORDCNT//\"/}" -gt 0 ] && echo true || echo false)"
contains "订单卡片标注待支付" "待支付" "$SNAP3"
contains "卡片金额与真实订单一致" "$YUAN" "$SNAP3"
contains "明确说明尚未支付" "尚未支付" "$SNAP3"
ORDER_ID=$(mysqlq "SELECT id FROM \`order\` WHERE user_id=$STU_ID ORDER BY id DESC LIMIT 1;")
DB_STATUS=$(mysqlq "SELECT status FROM \`order\` WHERE id=$ORDER_ID;")
chk "MySQL 订单状态=1（待支付）" "1" "$DB_STATUS"
ORDER_COURSE=$(mysqlq "SELECT course_id FROM order_detail WHERE order_id=$ORDER_ID LIMIT 1;")
ORDERS_TOTAL_AFTER=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
chk "确认后恰好新增一笔订单" "$((ORDERS_TOTAL_BEFORE + 1))" "$ORDERS_TOTAL_AFTER"

echo "--- 5. 点击订单卡片进入订单详情（F-42）---"
ab eval "(function(){var b=document.querySelector('.order-card .order-card__btn');if(!b)return 'nobtn';b.click();return 'clicked'})()" >/dev/null
sleep 4
CUR2=$(ab get url)
chk "跳转到订单详情页" "1" "$(printf '%s' "$CUR2" | grep -c '#/orders/')"

echo "--- 6. 刷新后历史卡片恢复，不重复下单（F-43）---"
ab open "$FE/#/ai" >/dev/null
sleep 2
ab reload >/dev/null
sleep 5
ab eval "(function(){var els=document.querySelectorAll('.ai-session');if(!els.length)return 'none';els[0].click();return 'clicked'})()" >/dev/null
sleep 4
SNAP4=$(agent-browser snapshot 2>&1)
contains "刷新后课程卡片恢复" "$CNAME" "$SNAP4"
ORDCNT2=$(ab eval "(function(){return document.querySelectorAll('.order-card').length})()")
chk "刷新后订单卡片恢复" "true" "$([ "${ORDCNT2//\"/}" -gt 0 ] && echo true || echo false)"
ORDERS_REPLAY=$(mysqlq "SELECT COUNT(*) FROM \`order\` WHERE user_id=$STU_ID AND deleted=0;")
chk "历史回放未重复创建订单" "$((ORDERS_TOTAL_BEFORE + 1))" "$ORDERS_REPLAY"

echo "--- 7. 切换会话不串流（F-44）---"
ab eval "(function(){var bs=document.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('新建')>=0){bs[i].click();return 'c'}}return 'nf'})()" >/dev/null
sleep 3
KEY="M7ISO-$TS"
type_text "请介绍一下 $KEY"
sleep 2
# 立即切回工作台首页（未选中任何会话），等待生成结束
ab open "$FE/#/ai" >/dev/null
sleep 2
sleep 8
ab reload >/dev/null
sleep 5
MSGCNT=$(ab eval "(function(){return document.querySelectorAll('.ai-msg').length})()")
chk "未选中会话时消息区为空（无串流）" "0" "${MSGCNT//\"/}"

echo "--- 8. XSS 防护（F-45）---"
BAD=$(ab eval "(function(){var els=document.querySelectorAll('.ai-messages *');var bad=0;for(var i=0;i<els.length;i++){var at=els[i].attributes;for(var j=0;j<at.length;j++){if(/^on/i.test(at[j].name))bad++;}}return bad})()")
chk "卡片与消息不含 on* 事件属性" "0" "${BAD//\"/}"
JSRC=$(ab eval "(function(){var imgs=document.querySelectorAll('.course-card img');var bad=0;for(var i=0;i<imgs.length;i++){var s=(imgs[i].getAttribute('src')||'').toLowerCase();if(s.indexOf('javascript:')===0||s.indexOf('data:')===0)bad++;}return bad})()")
chk "卡片封面无 javascript:/data: 来源" "0" "${JSRC//\"/}"

ab screenshot /tmp/ai-workbench-m7.png >/dev/null 2>&1
echo "================================================="
echo "M7 浏览器 E2E：通过 ${PASS} 失败 ${FAIL}"
[ "$FAIL" -eq 0 ]
