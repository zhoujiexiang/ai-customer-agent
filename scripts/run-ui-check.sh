#!/usr/bin/env bash
# 一键跑前端 UI 走查。
#
# 为什么必须一条命令跑完：沙箱会在回合结束时回收后台进程，
# 所以「起后端 -> 起前端 -> 浏览器走查 -> 收尾」必须在一个进程树里完成。
#
# 用法：  bash scripts/run-ui-check.sh
set -u

ROOT="C:/Users/share/WorkBuddy/2026-10-01-14-10-31/ai-customer-agent"
cd "$ROOT" || exit 1

# 沙箱会注入 SERVER__PORT，Spring 的宽松绑定会拿它覆盖 server.port；
# 代理变量则会把 127.0.0.1 的请求拐到透明代理上，一并清掉。
unset SERVER__PORT DEEPSEEK_API_KEY SILICONFLOW_API_KEY
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy

MVN=".tools/apache-maven-3.9.16/bin/mvn"
NODE="C:/Users/share/.workbuddy/binaries/node/versions/22.22.2-5/node.exe"
export NODE_PATH="C:/Users/share/.workbuddy/binaries/node/workspace/node_modules"

# 沙箱只放行部分监听端口，5173 会被 EACCES 挡掉，这里默认换成 4173。
# 想用别的端口：FE_PORT=3000 bash scripts/run-ui-check.sh
FE_PORT="${FE_PORT:-4173}"
export APP_URL="http://127.0.0.1:${FE_PORT}"

BE_PID=""
FE_PID=""

cleanup() {
  echo ""
  echo "--- 收尾 ---"
  local javapid fepid
  # Maven 只是父进程，真正监听 8080 的是它 fork 出来的 java，得按端口找 PID
  javapid=$(netstat -ano 2>/dev/null | awk '/127.0.0.1:8080|0.0.0.0:8080/ && /LISTENING/ {print $5; exit}')
  if [ -n "${javapid:-}" ]; then
    taskkill //F //PID "$javapid" >/dev/null 2>&1 && echo "  已停止后端 (pid $javapid)"
  fi
  # 前端同样按端口回收：nohup 拿到的 $! 是子 shell 的 pid，杀它不一定能停掉 vite
  fepid=$(netstat -ano 2>/dev/null | awk "/:${FE_PORT}/ && /LISTENING/ {print \$5; exit}")
  if [ -n "${fepid:-}" ]; then
    taskkill //F //PID "$fepid" >/dev/null 2>&1 && echo "  已停止前端 (pid $fepid)"
  fi
  [ -n "$BE_PID" ] && kill "$BE_PID" 2>/dev/null
  [ -n "$FE_PID" ] && kill "$FE_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

# ---------- 0. 重置演示数据 ----------
# 走查里的「退款」场景会把订单改成「退款中」，不重置的话第二次跑就变成
# 「重复申请被拒」，看到的现象和预期对不上。
echo "[0/4] 重置演示订单 ..."
if docker exec agent-postgres psql -U agent -d agent_db -q -c \
  "UPDATE mock_order SET status='已发货' WHERE order_no='202610010001';
   UPDATE mock_order SET receiver_address='广州市天河区体育西路 12 号维多利广场 B 塔 903' WHERE order_no='202610010005';" >/dev/null 2>&1; then
  echo "  ✓ 202610010001 已回置为「已发货」"
else
  echo "  ! 重置失败 —— 确认 Docker Desktop 已启动、容器 agent-postgres 在跑"
fi

# ---------- 1. 后端 ----------
echo "[1/4] 启动后端 (8080) ..."
nohup "$MVN" -f backend/pom.xml spring-boot:run > /tmp/agent-backend.log 2>&1 &
BE_PID=$!

ok=""
for _ in $(seq 1 90); do
  if curl -s --noproxy '*' --max-time 3 http://127.0.0.1:8080/api/chat/status >/dev/null 2>&1; then
    ok=yes
    break
  fi
  sleep 2
done

if [ -z "$ok" ]; then
  echo "  ✗ 后端 180 秒内未就绪，日志尾部："
  tail -n 40 /tmp/agent-backend.log
  exit 1
fi
echo "  ✓ 后端就绪：$(curl -s --noproxy '*' http://127.0.0.1:8080/api/chat/status)"

# ---------- 2. 前端 ----------
echo "[2/4] 启动前端 (${FE_PORT}) ..."
# --host 127.0.0.1：默认的 localhost 会让 Node 优先绑到 ::1，这台机器上探测不通
( cd frontend && nohup "$NODE" node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$FE_PORT" --strictPort > /tmp/agent-frontend.log 2>&1 & echo $! > /tmp/agent-fe.pid )
FE_PID=$(cat /tmp/agent-fe.pid 2>/dev/null || echo "")

ok=""
for _ in $(seq 1 45); do
  if curl -s --noproxy '*' --max-time 3 "$APP_URL" >/dev/null 2>&1; then
    ok=yes
    break
  fi
  sleep 2
done

if [ -z "$ok" ]; then
  echo "  ✗ 前端 90 秒内未就绪，日志尾部："
  tail -n 30 /tmp/agent-frontend.log
  echo "  实际监听："
  netstat -ano 2>/dev/null | grep -E ":${FE_PORT}" | head
  exit 1
fi
echo "  ✓ 前端就绪"

# ---------- 3. 浏览器走查 ----------
echo "[3/4] 浏览器走查 ..."
echo ""
"$NODE" scripts/ui-check.mjs
UI_RC=$?

# ---------- 4. 汇总 ----------
echo ""
echo "[4/4] 截图清单："
ls -1 docs/screenshots/ 2>/dev/null | sed 's/^/  /' || echo "  (无)"

exit $UI_RC
