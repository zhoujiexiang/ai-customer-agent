#!/usr/bin/env bash
# =====================================================================
# 一键启动（现场演示用，不依赖 IDE）
#
#   bash start.sh              # 起数据库容器 -> 起后端 jar -> 起前端 preview
#   bash start.sh --rebuild    # 先重新打包前后端再启动
#   bash start.sh --reset-demo # 同时把演示订单还原（演示前建议带上）
#
#   两个参数可以组合：bash start.sh --rebuild --reset-demo
#
# 与 scripts/run-ui-check.sh 的分工：
#   那个是自动化走查，跑完会截图、断言、退出；
#   这个是给人看的演示环境，跑起来就常驻，Ctrl+C 收尾。
#
# 注意：Windows 上用 Git Bash 运行。
# =====================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 1

JAR="backend/target/agent-backend-1.0.0.jar"
BE_PORT=8080
FE_PORT=4173
BE_URL="http://127.0.0.1:${BE_PORT}/api/chat/status"
FE_URL="http://127.0.0.1:${FE_PORT}"

# 优先用项目内自带的 Maven（不依赖本机是否装过），没有则退回 PATH
MVN=".tools/apache-maven-3.9.16/bin/mvn"
[ -x "$MVN" ] || MVN="mvn"
VITE="frontend/node_modules/vite/bin/vite.js"

REBUILD=""
RESET_DEMO=""
for arg in "$@"; do
  case "$arg" in
    --rebuild)    REBUILD=yes ;;
    --reset-demo) RESET_DEMO=yes ;;
  esac
done

BE_PID=""
FE_PID=""

# ---------------------------------------------------------------------
# 收尾：Maven/npm 只是父进程，真正监听端口的是 fork 出来的子进程，
# 所以按端口找 PID 才是最可靠的停法
# ---------------------------------------------------------------------
stop_by_port() {
  local port="$1" pid
  # LC_ALL=C：Windows 的 netstat 输出带中文表头，awk 在 UTF-8 locale 下会刷
  # "Invalid multibyte data detected" 警告（不影响结果，但很难看）
  pid=$(netstat -ano 2>/dev/null \
        | LC_ALL=C awk -v p=":${port}" 'index($2, p) > 0 && $4 == "LISTENING" {print $5; exit}')
  if [ -n "${pid:-}" ]; then
    if command -v taskkill >/dev/null 2>&1; then
      taskkill //F //PID "$pid" >/dev/null 2>&1
    else
      kill -9 "$pid" >/dev/null 2>&1
    fi
    echo "  已停止端口 ${port} (pid ${pid})"
  fi
}

cleanup() {
  echo ""
  echo "--- 收尾 ---"
  [ -n "$BE_PID" ] && kill "$BE_PID" 2>/dev/null
  [ -n "$FE_PID" ] && kill "$FE_PID" 2>/dev/null
  stop_by_port "$BE_PORT"
  stop_by_port "$FE_PORT"
  echo "  完成"
}
trap cleanup EXIT

# ---------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------
for cmd in docker java node npm; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "✗ 未找到 ${cmd}，请先安装或确认已加入 PATH"
    exit 1
  fi
done
[ -f "$VITE" ] || { echo "✗ 前端依赖未安装，请先执行：cd frontend && npm install"; exit 1; }

# 端口占用前置检查：与其让后端起来再失败、白等 120 秒，不如提前说清楚
for p in "$BE_PORT" "$FE_PORT"; do
  if netstat -ano 2>/dev/null \
     | LC_ALL=C awk -v q=":${p}" 'index($2, q) > 0 && $4 == "LISTENING"' | grep -q .; then
    echo "✗ 端口 ${p} 已被占用，先停掉占用进程再运行"
    exit 1
  fi
done

# ---------------------------------------------------------------------
# 1. 数据库
# ---------------------------------------------------------------------
echo "[1/4] 启动数据库容器 ..."
docker compose up -d >/dev/null 2>&1

ready=""
for _ in $(seq 1 40); do
  health=$(docker inspect --format '{{.State.Health.Status}}' agent-postgres 2>/dev/null || echo "")
  if [ "$health" = "healthy" ]; then ready=yes; break; fi
  sleep 2
done
if [ -z "$ready" ]; then
  echo "  ✗ 容器 80 秒内未就绪，排查：docker logs agent-postgres"
  exit 1
fi
echo "  ✓ agent-postgres healthy (宿主机端口 15433)"

# 演示数据重置：跑过的演示会把 001 改成「退款中」、把 005 的地址改掉，
# 不还原的话下一次演示就没法看了（退款会变成「重复申请被拒」，
# 改地址演示也看不出前后对比）。地址取自 02-seed.sql，别凭记忆写。
if [ -n "$RESET_DEMO" ]; then
  if docker exec agent-postgres psql -U agent -d agent_db -q -c \
       "UPDATE mock_order SET status='已发货' WHERE order_no='202610010001';
        UPDATE mock_order SET receiver_address='广州市天河区体育西路 12 号维多利广场 B 塔 903'
          WHERE order_no='202610010005';" >/dev/null 2>&1; then
    echo "  ✓ 演示数据已重置（001 → 已发货，005 → 地址原值）"
  else
    echo "  ! 演示数据重置失败（不影响启动，可稍后手动执行）"
  fi
fi

# ---------------------------------------------------------------------
# 2. 后端 jar
# ---------------------------------------------------------------------
if [ ! -f "$JAR" ] || [ -n "$REBUILD" ]; then
  echo "[2/4] 打包后端（首次或 --rebuild，约 1 分钟）..."
  "$MVN" -f backend/pom.xml clean package -DskipTests \
         -Dmaven.wagon.http.ssl.insecure=true -q || {
    echo "  ✗ 打包失败，去掉 -q 重跑看详细日志"
    exit 1
  }
  echo "  ✓ $JAR"
else
  echo "[2/4] 后端 jar 已存在，跳过打包（要强制重建加 --rebuild）"
fi

# ---------------------------------------------------------------------
# 3. 前端构建产物
# ---------------------------------------------------------------------
if [ ! -d "frontend/dist" ] || [ -n "$REBUILD" ]; then
  echo "[3/4] 构建前端（首次或 --rebuild）..."
  ( cd frontend && npm run build > /tmp/agent-build.log 2>&1 ) || {
    echo "  ✗ 构建失败，日志尾部："
    tail -n 20 /tmp/agent-build.log
    exit 1
  }
  echo "  ✓ frontend/dist"
else
  echo "[3/4] 前端产物已存在，跳过构建（要强制重建加 --rebuild）"
fi

# ---------------------------------------------------------------------
# 4. 起后端 + 前端
# ---------------------------------------------------------------------
echo "[4/4] 启动服务 ..."

# 密钥已随 application-local.yml 打进了 jar，开箱即用。
# 想换密钥不用重新打包：把 application-local.yml 放在本脚本同级目录，
# application.yml 里的 optional:file:./application-local.yml 会覆盖 jar 内的同名配置。
#
# 端口用命令行参数显式指定：Spring Boot 的优先级是「命令行参数 > 环境变量 > 配置文件」，
# 所以就算环境里存在 SERVER__PORT 之类的变量也覆盖不了它。这比在脚本里 unset 更可靠 ——
# unset 只能处理已知的变量名，命令行参数是通杀的。
nohup java -jar "$JAR" --server.port="$BE_PORT" > /tmp/agent-backend.log 2>&1 &
BE_PID=$!

ready=""
for _ in $(seq 1 60); do
  if curl -s --noproxy '*' --max-time 3 "$BE_URL" >/dev/null 2>&1; then ready=yes; break; fi
  sleep 2
done
if [ -z "$ready" ]; then
  echo "  ✗ 后端 120 秒内未就绪，日志尾部："
  tail -n 30 /tmp/agent-backend.log
  exit 1
fi
echo "  ✓ 后端就绪 ${BE_URL}"

# preview 服务的是 build 之后的真实产物。
# 必须显式 --host 127.0.0.1：默认的 localhost 在部分环境会优先解析到 ::1。
( cd frontend && nohup node node_modules/vite/bin/vite.js preview \
    --host 127.0.0.1 --port "$FE_PORT" --strictPort \
    > /tmp/agent-preview.log 2>&1 & echo $! > /tmp/agent-preview.pid )
FE_PID=$(cat /tmp/agent-preview.pid 2>/dev/null || echo "")

ready=""
for _ in $(seq 1 30); do
  if curl -s --noproxy '*' --max-time 3 "$FE_URL" >/dev/null 2>&1; then ready=yes; break; fi
  sleep 1
done
if [ -z "$ready" ]; then
  echo "  ✗ 前端 30 秒内未就绪，日志尾部："
  tail -n 20 /tmp/agent-preview.log
  exit 1
fi
echo "  ✓ 前端就绪 ${FE_URL}"

echo ""
echo "======================================================================"
echo "  演示环境已就绪"
echo ""
echo "  应用入口    ${FE_URL}"
echo "  登录账号    admin / admin123"
echo ""
echo "  示例问题    首页四张卡片，点一下即可（只在空对话时显示）"
echo "              退款一般几天到账？                              → 纯知识库检索"
echo "              帮我查一下订单 202610010003 的物流到哪了          → 只读工具"
echo "              订单 202610010001 我要退款，商品有质量问题        → 写操作二次确认"
echo "              把订单 202610010005 的收货地址改成杭州市西湖区文三路 100 号 → 写操作可取消"
echo ""
echo "  演示流程    docs/演示指南.md（场景步骤 + 故障预案）"
echo "  重置数据    bash start.sh --reset-demo"
echo ""
echo "  日志        /tmp/agent-backend.log · /tmp/agent-preview.log"
echo "  停止        Ctrl+C"
echo "======================================================================"
echo ""

# 常驻，等着被 Ctrl+C 打断
while true; do sleep 3600; done
