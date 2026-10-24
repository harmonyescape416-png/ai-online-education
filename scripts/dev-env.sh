#!/bin/bash
# 课栈智伴 本地开发环境管理（用户级安装，无需 sudo）
# 用法: source scripts/dev-env.sh && devenv start|stop|status
set -u

export TOOLS_HOME="$HOME/opt/tools"
export JAVA_HOME="$TOOLS_HOME/jdk-17/Contents/Home"
export MAVEN_HOME="$TOOLS_HOME/maven"
export NODE_HOME="$TOOLS_HOME/node"
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$NODE_HOME/bin:$PATH"

MYSQL_BASE="$TOOLS_HOME/mysql"
NACOS_HOME="$TOOLS_HOME/nacos-server"
REDIS_BIN="$TOOLS_HOME/redis/bin"

devenv_start_mysql() {
  "$MYSQL_BASE/bin/mysqladmin" --socket=/tmp/mysql.sock -uroot ping >/dev/null 2>&1 && { echo "mysql  already running"; return 0; }
  nohup "$MYSQL_BASE/bin/mysqld" \
    --basedir="$MYSQL_BASE" --datadir="$MYSQL_BASE/data" \
    --port=3306 --socket=/tmp/mysql.sock --pid-file="$MYSQL_BASE/tmp/mysqld.pid" \
    --mysqlx=0 --log-error="$MYSQL_BASE/log/mysqld.log" >/dev/null 2>&1 &
  for i in $(seq 1 40); do
    "$MYSQL_BASE/bin/mysqladmin" --socket=/tmp/mysql.sock -uroot ping >/dev/null 2>&1 && { echo "mysql  started (3306)"; return 0; }
    sleep 1
  done
  echo "mysql  start FAILED, see $MYSQL_BASE/log/mysqld.log"; return 1
}

devenv_start_redis() {
  "$REDIS_BIN/redis-cli" -p 6379 ping >/dev/null 2>&1 && { echo "redis  already running"; return 0; }
  mkdir -p "$TOOLS_HOME/redis/data"
  nohup "$REDIS_BIN/redis-server" --port 6379 --dir "$TOOLS_HOME/redis/data" > "$TOOLS_HOME/redis/data/redis.log" 2>&1 &
  sleep 2
  "$REDIS_BIN/redis-cli" -p 6379 ping >/dev/null 2>&1 && { echo "redis  started (6379)"; return 0; }
  echo "redis  start FAILED"; return 1
}

devenv_start_nacos() {
  curl -s --max-time 3 http://127.0.0.1:8848/nacos/ >/dev/null 2>&1 && { echo "nacos  already running"; return 0; }
  (cd "$NACOS_HOME/bin" && nohup sh startup.sh -m standalone >/tmp/nacos-boot.log 2>&1 &)
  for i in $(seq 1 60); do
    grep -q "Nacos started successfully" "$NACOS_HOME/logs/start.out" 2>/dev/null && { echo "nacos  started (8848)"; return 0; }
    sleep 2
  done
  echo "nacos  start FAILED, see $NACOS_HOME/logs/start.out"; return 1
}

devenv() {
  case "${1:-status}" in
    start)  devenv_start_mysql; devenv_start_redis; devenv_start_nacos ;;
    stop)
      "$MYSQL_BASE/bin/mysqladmin" --socket=/tmp/mysql.sock -uroot shutdown >/dev/null 2>&1 && echo "mysql  stopped"
      "$REDIS_BIN/redis-cli" -p 6379 shutdown nosave >/dev/null 2>&1 && echo "redis  stopped"
      (cd "$NACOS_HOME/bin" && sh shutdown.sh >/dev/null 2>&1) && echo "nacos  stopped"
      ;;
    status)
      printf "java   : "; java -version 2>&1 | head -1
      printf "maven  : "; mvn -v 2>/dev/null | head -1
      printf "node   : "; node -v 2>/dev/null
      printf "mysql  : "; "$MYSQL_BASE/bin/mysqladmin" --socket=/tmp/mysql.sock -uroot ping 2>/dev/null || echo "down"
      printf "redis  : "; "$REDIS_BIN/redis-cli" -p 6379 ping 2>/dev/null || echo "down"
      printf "nacos  : "; curl -s -o /dev/null -w "%{http_code}\n" --max-time 3 http://127.0.0.1:8848/nacos/ || echo "down"
      ;;
    cli) shift; "$MYSQL_BASE/bin/mysql" --socket=/tmp/mysql.sock -uroot course_stack_ai "$@" ;;
    *) echo "usage: devenv start|stop|status|cli" ;;
  esac
}

echo "[dev-env] JAVA_HOME=$JAVA_HOME"
