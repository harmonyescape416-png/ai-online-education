#!/bin/bash
# 本地服务从独立 JAR 快照运行，避免 mvn package/clean 改写运行中的归档。
# 用法：source scripts/dev-env.sh && bash scripts/run-services.sh start|stop|restart|status [learning|kzai-learning]
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/logs"
RUNTIME_DIR="$ROOT/.runtime/services"
SERVICES="kzai-gateway kzai-auth kzai-user kzai-course kzai-trade kzai-learning kzai-aigc"
port_of() {
  case "$1" in
    kzai-gateway) echo 18080 ;; kzai-auth) echo 18081 ;;
    kzai-user) echo 18082 ;; kzai-course) echo 18083 ;;
    kzai-trade) echo 18084 ;; kzai-learning) echo 18085 ;;
    kzai-aigc) echo 18086 ;; *) return 1 ;;
  esac
}
listener() { lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null || true; }
# 401/403/404 也证明 HTTP 分发正常；仅端口监听不足以证明服务可用。
responds() {
  local code
  code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$1/internal/__readiness__" || true)
  case "$code" in 2??|3??|401|403|404) return 0 ;; *) return 1 ;; esac
}
java_bin() {
  local executable="${KZAI_JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
  executable="${executable:-$(command -v java)}"
  # 本机 java 是会改写端口、脱离父进程的测试包装器；真实 JVM 保持 PID/日志可追踪。
  if [ -z "${KZAI_JAVA_BIN:-}" ] && [ -x "${executable}.real" ]; then executable="${executable}.real"; fi
  printf '%s\n' "$executable"
}
start_one() {
  local service="$1" port jar snapshot pid attempt
  port=$(port_of "$service")
  if [ -n "$(listener "$port")" ]; then
    if responds "$port"; then echo "$service UP :$port"; return 0; fi
    echo "$service :$port is listening but HTTP is unresponsive; use restart $service" >&2
    return 1
  fi
  jar="$ROOT/$service/target/$service-1.0.0-SNAPSHOT.jar"
  if [ ! -f "$jar" ]; then echo "$service jar missing; run mvn package first" >&2; return 1; fi
  mkdir -p "$LOG_DIR" "$RUNTIME_DIR/$service"
  snapshot="$RUNTIME_DIR/$service/$(date +%Y%m%d%H%M%S)-$$.jar"
  cp "$jar" "$snapshot"
  # 独立进程会话，避免启动终端关闭或工具会话结束时杀死后台 JVM。
  python3 - "$ROOT" "$(java_bin)" "$snapshot" "$port" "$LOG_DIR/$service.log" "$RUNTIME_DIR/$service/service.pid" "$RUNTIME_DIR/$service/runtime-env.json" <<'PY'
import json
import os
import pathlib
import subprocess
import sys
root, java, jar, port, log_path, pid_path, env_path = sys.argv[1:]
environment = os.environ.copy()
private_env = pathlib.Path(env_path)
if private_env.exists():
    settings = json.loads(private_env.read_text())
    if not isinstance(settings, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in settings.items()):
        raise ValueError('runtime-env.json must contain string environment values')
    environment.update(settings)
with open(log_path, 'ab') as log:
    process = subprocess.Popen([java, '-jar', jar, '--server.port=' + port], cwd=root,
                               stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True, close_fds=True, env=environment)
pathlib.Path(pid_path).write_text(str(process.pid) + '\n')
PY
  pid=$(cat "$RUNTIME_DIR/$service/service.pid")
  for attempt in $(seq 1 45); do
    if ! kill -0 "$pid" 2>/dev/null; then echo "$service exited; see $LOG_DIR/$service.log" >&2; return 1; fi
    if responds "$port"; then echo "$service READY pid=$pid port=$port snapshot=$snapshot"; return 0; fi
    sleep 1
  done
  echo "$service failed HTTP readiness check; see $LOG_DIR/$service.log" >&2
  return 1
}
stop_one() {
  local service="$1" port pid attempt
  port=$(port_of "$service")
  pid=$(listener "$port")
  if [ -z "$pid" ]; then echo "$service DOWN"; return 0; fi
  kill "$pid"
  for attempt in $(seq 1 25); do
    if [ -z "$(listener "$port")" ]; then echo "$service stopped"; return 0; fi
    sleep 1
  done
  echo "$service did not stop gracefully; not starting a second instance" >&2
  return 1
}
ACTION="${1:-status}"
case "$ACTION" in start|stop|restart|status) ;; *) echo "usage: $0 start|stop|restart|status [service]" >&2; exit 2 ;; esac
if [ -n "${2:-}" ]; then
  SELECTED="${2#kzai-}"
  SERVICES="kzai-$SELECTED"
  if ! port_of "$SERVICES" > /dev/null; then echo "unknown service: $2" >&2; exit 2; fi
fi
for service in $SERVICES; do
  case "$ACTION" in
    start) start_one "$service" ;;
    stop) stop_one "$service" ;;
    restart) stop_one "$service"; start_one "$service" ;;
    status)
      port=$(port_of "$service")
      if [ -z "$(listener "$port")" ]; then echo "$service DOWN :$port"
      elif responds "$port"; then echo "$service UP :$port"
      else echo "$service UNRESPONSIVE :$port"; fi ;;
  esac
 done
