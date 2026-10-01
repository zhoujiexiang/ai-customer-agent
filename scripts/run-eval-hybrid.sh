#!/usr/bin/env bash
# 起后端 -> 跑检索通道对比评测 -> 收尾。
# 和 run-ui-check.sh 一样，必须一条命令跑完：后台进程跨回合会被沙箱回收。
set -u

ROOT="C:/Users/share/WorkBuddy/2026-10-01-14-10-31/ai-customer-agent"
cd "$ROOT" || exit 1

unset SERVER__PORT DEEPSEEK_API_KEY SILICONFLOW_API_KEY
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy

MVN=".tools/apache-maven-3.9.16/bin/mvn"
PY="C:/Users/share/.workbuddy/binaries/python/versions/3.13.12/python.exe"

BE_PID=""

cleanup() {
  echo ""
  echo "--- 收尾 ---"
  local javapid
  javapid=$(netstat -ano 2>/dev/null | awk '/127.0.0.1:8080|0.0.0.0:8080/ && /LISTENING/ {print $5; exit}')
  if [ -n "${javapid:-}" ]; then
    taskkill //F //PID "$javapid" >/dev/null 2>&1 && echo "  已停止后端 (pid $javapid)"
  fi
  [ -n "$BE_PID" ] && kill "$BE_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

echo "[1/2] 启动后端 (8080) ..."
nohup "$MVN" -f backend/pom.xml spring-boot:run > /tmp/agent-eval-backend.log 2>&1 &
BE_PID=$!

ready=""
for _ in $(seq 1 90); do
  if curl -s --noproxy '*' --max-time 3 http://127.0.0.1:8080/api/chat/status >/dev/null 2>&1; then
    ready=yes
    break
  fi
  sleep 2
done

if [ -z "$ready" ]; then
  echo "  ✗ 后端 180 秒内未就绪，日志尾部："
  tail -n 40 /tmp/agent-eval-backend.log
  exit 1
fi
echo "  ✓ 就绪"

echo "[2/2] 跑检索通道对比 ..."
echo ""
"$PY" scripts/eval-hybrid.py
