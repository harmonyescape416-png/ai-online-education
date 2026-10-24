#!/bin/bash
# 回退 MOCK：unset 真实凭据重启 aigc
cd /Users/djanjo/CodeBuddy/20261018213055
unset DASHSCOPE_ENABLED DASHSCOPE_API_KEY
/Users/djanjo/opt/tools/maven/bin/mvn -q -pl kzai-aigc clean package -DskipTests
kill $(lsof -ti :18086 -sTCP:LISTEN) 2>/dev/null
sleep 2
nohup /Users/djanjo/opt/tools/jdk-17/Contents/Home/bin/java -Dhttp.nonProxyHosts='*' \
  -jar kzai-aigc/target/kzai-aigc-1.0.0-SNAPSHOT.jar > /Users/djanjo/opt/tools/kzai-wrap-18086.log 2>&1 &
sleep 25
nc -z -w1 127.0.0.1 18086 2>/dev/null && echo "✓ 已回退 MOCK 模式"
