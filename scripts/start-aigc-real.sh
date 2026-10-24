#!/bin/bash
# M15.22：AI 对话真实 Qwen 模式启动脚本（MOCK↔REAL 零代码切换）
# 用法：./scripts/start-aigc-real.sh <DASHSCOPE_API_KEY> [模型]
KEY=${1:?用法: ./scripts/start-aigc-real.sh sk-xxxx [qwen-plus]}
MODEL=${2:-qwen-plus}
cd /Users/djanjo/CodeBuddy/20261018213055

export DASHSCOPE_ENABLED=true
export DASHSCOPE_API_KEY="$KEY"
export DASHSCOPE_MODEL="$MODEL"

echo "① 打包 aigc（真实模式注入）…"
/Users/djanjo/opt/tools/jdk-17/Contents/Home/bin/true 2>/dev/null
DASHSCOPE_ENABLED=true DASHSCOPE_API_KEY="$KEY" /Users/djanjo/opt/tools/maven/bin/mvn -q -pl kzai-aigc clean package -DskipTests
[ $? -ne 0 ] && { echo "✗ 打包失败"; exit 1; }

echo "② 重启 aigc…"
kill $(lsof -ti :18086 -sTCP:LISTEN) 2>/dev/null
sleep 2
DASHSCOPE_ENABLED=true DASHSCOPE_API_KEY="$KEY" DASHSCOPE_MODEL="$MODEL" \
nohup /Users/djanjo/opt/tools/jdk-17/Contents/Home/bin/java -Dhttp.nonProxyHosts='*' \
  -jar kzai-aigc/target/kzai-aigc-1.0.0-SNAPSHOT.jar > /Users/djanjo/opt/tools/kzai-wrap-18086.log 2>&1 &

echo "③ 等待启动…"
sleep 25
if nc -z -w1 127.0.0.1 18086 2>/dev/null; then
  echo "✓ aigc 已启动（真实 Qwen 模式：model=$MODEL）"
  echo "   验证：浏览器 AI 工作台发送消息——顶部应显示「真实模型」而非 MOCK"
  echo "   回退 MOCK：./scripts/stop-aigc-real.sh（unset 环境变量重启）"
else
  echo "✗ 启动失败——查日志 /Users/djanjo/opt/tools/kzai-wrap-18086.log"
fi
