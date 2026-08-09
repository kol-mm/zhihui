#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
ACTION="${1:-deploy}"

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

read_env() {
  local key="$1"
  local value
  value="$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')"
  printf '%s' "$value"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "缺少命令：$1" >&2
    exit 1
  }
}

validate_environment() {
  [[ -f "$ENV_FILE" ]] || {
    cp "$ROOT_DIR/.env.example" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "已创建 $ENV_FILE，请填写密码和密钥后重新执行。" >&2
    exit 1
  }

  local key value port bind_address
  for key in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD AI_KNOWLEDGE_JWT_SECRET INTERNAL_NOTIFICATION_TOKEN; do
    value="$(read_env "$key")"
    if [[ -z "$value" || "$value" == replace-with-* ]]; then
      echo "$ENV_FILE 中的 $key 尚未正确配置。" >&2
      exit 1
    fi
  done

  value="$(read_env MYSQL_USER)"
  [[ -z "$value" || "$value" =~ ^[A-Za-z0-9_]+$ ]] || {
    echo "MYSQL_USER 只能包含字母、数字和下划线。" >&2
    exit 1
  }

  bind_address="$(read_env HTTP_BIND_ADDRESS)"
  bind_address="${bind_address:-127.0.0.1}"
  [[ "$bind_address" == "0.0.0.0" || "$bind_address" == "127.0.0.1" ]] || {
    echo "HTTP_BIND_ADDRESS 只能设置为 0.0.0.0 或 127.0.0.1。" >&2
    exit 1
  }

  port="$(read_env HTTP_PORT)"
  port="${port:-8088}"
  [[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || {
    echo "HTTP_PORT 必须是 1-65535 之间的端口号。" >&2
    exit 1
  }

  chmod 600 "$ENV_FILE"
  compose config --quiet
}

wait_for_stack() {
  local port deadline bind_address
  port="$(read_env HTTP_PORT)"
  port="${port:-8088}"
  bind_address="$(read_env HTTP_BIND_ADDRESS)"
  bind_address="${bind_address:-127.0.0.1}"
  deadline=$((SECONDS + 360))

  echo "等待服务健康检查完成……"
  until curl -fsS "http://127.0.0.1:${port}/healthz" >/dev/null 2>&1 && \
        curl -fsS "http://127.0.0.1:${port}/api/user/health" >/dev/null 2>&1 && \
        curl -fsS "http://127.0.0.1:${port}/api/ai/health" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "服务未能在 360 秒内通过验收，最近日志如下：" >&2
      compose ps >&2 || true
      compose logs --tail=120 >&2 || true
      exit 1
    fi
    sleep 5
  done

  compose ps
  if [[ "$bind_address" == "127.0.0.1" ]]; then
    echo "部署完成：http://127.0.0.1:${port}（仅宿主机可访问，请配置 HTTPS 反向代理）"
  else
    local host_ip
    host_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    host_ip="${host_ip:-127.0.0.1}"
    echo "部署完成：http://${host_ip}:${port}"
  fi
}

require_command docker
docker compose version >/dev/null 2>&1 || {
  echo "需要 Docker Compose v2（docker compose）。" >&2
  exit 1
}

case "$ACTION" in
  deploy|update)
    require_command curl
    validate_environment
    compose build
    compose up -d --remove-orphans
    wait_for_stack
    ;;
  refresh)
    require_command curl
    validate_environment
    compose build --pull
    compose up -d --remove-orphans
    wait_for_stack
    ;;
  start)
    require_command curl
    validate_environment
    compose up -d --remove-orphans
    wait_for_stack
    ;;
  restart)
    require_command curl
    validate_environment
    compose restart
    wait_for_stack
    ;;
  stop)
    validate_environment
    compose stop
    ;;
  down)
    validate_environment
    compose down
    ;;
  status)
    validate_environment
    compose ps
    ;;
  logs)
    validate_environment
    compose logs --tail=200 -f
    ;;
  *)
    echo "用法：./deploy.sh [deploy|update|refresh|start|restart|stop|down|status|logs]" >&2
    exit 2
    ;;
esac
