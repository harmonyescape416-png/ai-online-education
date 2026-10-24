#!/bin/bash
# M15 部署专项 E2E：隔离干净环境（course_stack_ai_m15 + namespace=m15 + 端口 2808x）
# 覆盖：三角色登录、角色权限隔离、核心业务、审计、持久化、服务重启恢复
# 前置：scripts 内 m15 服务已按 14-M13/15-M14 报告流程启动
set -u
G=http://127.0.0.1:28080
MYSQL="/Users/djanjo/opt/tools/mysql/bin/mysql --socket=/tmp/mysql.sock -uroot course_stack_ai_m15 -N -e"
PASS=0; FAIL=0
login() { curl -s --max-time 5 -X POST $G/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null; }
code() { curl -s -o /tmp/kzai_m15 -w "%{http_code}" "$@"; }
eq() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  [PASS] %s (%s)\n" "$1" "$3"; else FAIL=$((FAIL+1)); printf "  [FAIL] %s 期望=%s 实际=%s\n" "$1" "$2" "$3"; fi; }

echo "================ M15 部署专项 E2E（隔离干净环境） ================"
ATOK=$(login admin admin123); STOK=$(login student01 student123); TTOK=$(login teacher01 teacher123)
eq "1 三角色登录(admin/stu/tea)" 3 "$(python3 -c "print(1 if ${#ATOK}>50 else 0,1 if ${#STOK}>50 else 0,1 if ${#TTOK}>50 else 0)" 2>/dev/null | grep -c 1)"

echo "--- 安全与权限 ---"
eq "2 未登录访问课程管理 401" 401 "$(code "$G/admin/users?page=1&size=1")"
eq "3 学员访问用户管理 403" 403 "$(code -H "Authorization: Bearer $STOK" "$G/admin/users?page=1&size=1")"
eq "4 教师访问监管接口 403" 403 "$(code -H "Authorization: Bearer $TTOK" "$G/admin/supervision/sessions?page=1&size=1")"
eq "5 学员访问教师学情 403" 403 "$(code -H "Authorization: Bearer $STOK" "$G/teacher/learning-stats?page=1&size=1")"
eq "6 伪造 JWT 401" 401 "$(code -H "Authorization: Bearer fake.token.here" "$G/courses?page=1&size=1")"
BAD_HTTP=$(curl -s --max-time 5 -o /tmp/kzai_m15 -w "%{http_code}" -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
BAD_CODE=$(python3 -c "import json;print(json.load(open('/tmp/kzai_m15')).get('code'))" 2>/dev/null)
eq "7 错误密码受控拒绝(401/400 且非 500)" 1 "$(python3 -c "print(1 if '$BAD_HTTP' in ('400','401') and '$BAD_CODE' != '500' else 0)")"

echo "--- 学员闭环（干净数据） ---"
eq "8 课程列表(seed 5 门)" 5 "$(curl -s -H "Authorization: Bearer $STOK" "$G/courses?page=1&size=10" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['total'])")"
eq "9 免费课入课(1005)" 200 "$(code -X POST -H "Authorization: Bearer $STOK" -H 'Content-Type: application/json' -d '{"courseId":1005}' $G/learning/lessons)"
eq "10 学习进度上报" 200 "$(code -X PUT -H "Authorization: Bearer $STOK" -H 'Content-Type: application/json' -d '{"chapterId":5005,"videoPositionSec":30,"videoDurationSec":600}' $G/learning/lessons/1005/progress)"
SID=$(curl -s -X POST -H "Authorization: Bearer $STOK" $G/session | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['sessionId'])")
N=$(curl -s -N --max-time 25 -X POST -H "Authorization: Bearer $STOK" -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\",\"message\":\"推荐课程\"}" $G/chat 2>&1 | grep -c "^event: 1001")
eq "11 AI SSE 对话(MOCK,1001>=1)" 1 "$(python3 -c "print(1 if $N>=1 else 0)")"
HIST=$(curl -s -H "Authorization: Bearer $STOK" "$G/session/$SID" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['data']['messages']))")
eq "12 会话历史持久化(Redis)" 1 "$(python3 -c "print(1 if int('${HIST:-0}')>=2 else 0)")"
Q=$(curl -s -X POST -H "Authorization: Bearer $STOK" -H 'Content-Type: application/json' -d '{"courseId":1005,"title":"M15 部署验收提问","description":"课程内容覆盖哪些主题？"}' $G/questions | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['questionId'])" 2>/dev/null)
eq "13 课程问答创建(AI 任务同建)" 1 "$(python3 -c "print(1 if '''$Q''' not in ('','None') else 0)")"
TTS_CT=$(curl -s -X POST -H "Authorization: Bearer $STOK" --data-urlencode "text=M15 语音验证" $G/audio/tts-stream -o /tmp/m15d.wav -w "%{content_type}")
eq "14 TTS 返回 audio/wav" 1 "$(python3 -c "print(1 if 'audio' in '$TTS_CT' else 0)")"

echo "--- 教师闭环 ---"
eq "15 教师回复提问" 200 "$(code -X POST -H "Authorization: Bearer $TTOK" -H 'Content-Type: application/json' -d '{"content":"M15 验收：课程覆盖 Java 微服务全链路。"}' $G/teacher/questions/$Q/reply)"
eq "16 教师回复落库" 1 "$($MYSQL "SELECT COUNT(*)>=1 FROM interaction_reply WHERE question_id=$Q AND is_student=0;")"
AUD=$($MYSQL "SELECT COUNT(*) FROM sys_operation_log WHERE operation='AI_REPLY' AND create_time>=NOW()-INTERVAL 10 MINUTE;")
eq "17 AI 回复审计生成(10 分钟内)" 1 "$(python3 -c "print(1 if int('${AUD:-0}')>=1 else 0)")"
# 教师课程隔离：teacher01 无法回复他人课程的问题
Q2=$($MYSQL "INSERT INTO interaction_question (id,title,description,course_id,user_id,answer_times,anonymity,hidden,status,create_time,update_time) VALUES (888777001,'他人课程问题','x',1001,3,0,0,0,0,NOW(),NOW()); SELECT 888777001;")
BADR=$(code -X POST -H "Authorization: Bearer $TTOK" -H 'Content-Type: application/json' -d '{"content":"越权回复"}' $G/teacher/questions/$Q2/reply)
eq "18 教师跨课程回复被拒" 1 "$(python3 -c "print(0 if '$BADR'=='200' else 1)")"
$MYSQL "DELETE FROM interaction_question WHERE id=888777001;" > /dev/null

echo "--- 管理员闭环 ---"
eq "19 看板真实统计(用户>=4)" 1 "$(curl -s -H "Authorization: Bearer $ATOK" "$G/admin/dashboard/overview?days=1" | python3 -c "import json,sys;print(1 if int(json.load(sys.stdin)['data']['userCount'])>=4 else 0)")"
eq "20 审计日志可查" 1 "$(curl -s -H "Authorization: Bearer $ATOK" "$G/admin/operation-logs?page=1&size=1" | python3 -c "import json,sys;print(1 if int(json.load(sys.stdin)['data']['total'])>=1 else 0)")"
eq "21 评测用例(31 条 seed)" 31 "$(curl -s -H "Authorization: Bearer $ATOK" "$G/eval/cases?page=1&size=1" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['total'])")"
curl -s -X POST -H "Authorization: Bearer $ATOK" "$G/eval/run" -H 'Content-Type: application/json' -d '{"name":"M15-隔离环境-REAL","execMode":"REAL"}' > /tmp/kzai_m15d
eq "22 REAL 评测显式不可执行(不冒充)" 5 "$(python3 -c "import json;print(json.load(open('/tmp/kzai_m15d'))['data']['status'])")"

echo "--- 服务重启恢复与持久化 ---"
AIGCPID=$(lsof -ti tcp:28086 -sTCP:LISTEN 2>/dev/null | head -1); [ -n "$AIGCPID" ] && kill $AIGCPID && sleep 2
cd /Users/djanjo/CodeBuddy/20261018213055 && export JAVA_HOME=/Users/djanjo/opt/tools/jdk-17/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH && SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=m15 SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/course_stack_ai_m15?useUnicode=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" SERVER_PORT=28086 nohup java -jar kzai-aigc/target/kzai-aigc-1.0.0-SNAPSHOT.jar > logs/m15/kzai-aigc-restart.log 2>&1 &
AIGC_OK=0
for i in 1 2 3 4 5 6; do
  sleep 10
  [ -n "$(lsof -ti tcp:28086 -sTCP:LISTEN 2>/dev/null | head -1)" ] && AIGC_OK=1 && break
done
eq "23 aigc 重启恢复(≤60s)" 1 "$AIGC_OK"
STILL=$($MYSQL "SELECT COUNT(*) FROM learning_lesson WHERE user_id=3 AND course_id=1005;")
eq "24 重启后学习记录持久化" 1 "$(python3 -c "print(1 if int('${STILL:-0}')>=1 else 0)")"
HIST2=$(curl -s -H "Authorization: Bearer $STOK" "$G/session/$SID" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['data']['messages']))" 2>/dev/null)
eq "25 重启后会话历史仍在(Redis)" 1 "$(python3 -c "print(1 if int('${HIST2:-0}')>=2 else 0)")"

echo "================ M15 部署专项 E2E 完成：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" -eq 0 ]
