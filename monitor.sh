#!/usr/bin/env bash
# 巡检一次：容器状态、页面与网关、磁盘空间、备份时效、容器重启、证书有效期。
# 有问题时退出码为 1，并通过 ALERT_WEBHOOK_URL 发送告警（状态变化时立即发送，持续异常时按间隔提醒，恢复时再通知）。
# 用法：./monitor.sh            巡检一次
#       ./monitor.sh --test-alert   发送一条测试告警
# 可选设置见 .env.example 中的 MONITOR_* 和 ALERT_*；./schedule.sh 会把它安装为每 5 分钟执行一次。
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml")

[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE" >&2; exit 2; }

# 环境变量优先，其次读取 .env；只按键读取，不执行 .env 内容。
setting() {
  local key="$1" fallback="$2" value="${!1:-}"
  if [[ -z "$value" ]]; then
    value="$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')"
  fi
  printf '%s' "${value:-$fallback}"
}

HTTP_PORT="$(setting HTTP_PORT 80)"
BASE_URL="$(setting MONITOR_BASE_URL "http://127.0.0.1:$HTTP_PORT")"
STATE_DIR="$(setting MONITOR_STATE_DIR "$ROOT_DIR/.monitor")"
BACKUP_ROOT="$(setting BACKUP_ROOT "$ROOT_DIR/backups-docker")"
BACKUP_MAX_AGE_HOURS="$(setting MONITOR_BACKUP_MAX_AGE_HOURS 26)"
DISK_MIN_FREE_PERCENT="$(setting MONITOR_DISK_MIN_FREE_PERCENT 10)"
REPEAT_HOURS="$(setting MONITOR_REPEAT_HOURS 6)"
TLS_HOST="$(setting MONITOR_TLS_HOST "")"
TLS_MIN_DAYS="$(setting MONITOR_TLS_MIN_DAYS 14)"
WEBHOOK_URL="$(setting ALERT_WEBHOOK_URL "")"
WEBHOOK_TYPE="$(setting ALERT_WEBHOOK_TYPE json)"
SITE_NAME="$(setting ALERT_SITE_NAME "知汇")"
HOST_NAME="$(hostname 2>/dev/null || echo unknown)"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
exec 8>"$STATE_DIR/.monitor.lock"
flock -n 8 || exit 0

problems=()
notices=()
problem() { problems+=("$1"); }

json_escape() {
  local text="$1"
  text="${text//\\/\\\\}"
  text="${text//\"/\\\"}"
  text="${text//$'\t'/\\t}"
  text="${text//$'\r'/}"
  text="${text//$'\n'/\\n}"
  printf '%s' "$text"
}

send_alert() {
  local status="$1" body="$2" text payload
  text="[$SITE_NAME 监控] $status @ $HOST_NAME"$'\n'"$body"
  echo "$text"
  [[ -n "$WEBHOOK_URL" ]] || return 0
  case "$WEBHOOK_TYPE" in
    wecom|dingtalk) payload="{\"msgtype\":\"text\",\"text\":{\"content\":\"$(json_escape "$text")\"}}" ;;
    feishu) payload="{\"msg_type\":\"text\",\"content\":{\"text\":\"$(json_escape "$text")\"}}" ;;
    slack) payload="{\"text\":\"$(json_escape "$text")\"}" ;;
    *) payload="{\"site\":\"$(json_escape "$SITE_NAME")\",\"host\":\"$(json_escape "$HOST_NAME")\",\"status\":\"$(json_escape "$status")\",\"message\":\"$(json_escape "$body")\"}" ;;
  esac
  if ! curl --noproxy '*' -fsS -m 10 -H 'Content-Type: application/json' -d "$payload" "$WEBHOOK_URL" >/dev/null; then
    echo "告警发送失败：$WEBHOOK_URL" >&2
  fi
}

if [[ "${1:-}" == "--test-alert" ]]; then
  send_alert "测试" "这是一条测试告警，收到即说明告警通道可用。"
  exit 0
fi

# 1. 容器：每个服务都应在运行，带健康检查的应为 healthy。
if ! docker info >/dev/null 2>&1; then
  problem "无法连接 Docker"
else
  mapfile -t expected < <("${COMPOSE[@]}" config --services 2>/dev/null)
  declare -A seen=()
  while IFS='|' read -r service state health; do
    [[ -n "$service" ]] || continue
    seen["$service"]=1
    if [[ "$state" != "running" ]]; then
      problem "容器 $service 状态为 $state"
    elif [[ -n "$health" && "$health" != "healthy" ]]; then
      problem "容器 $service 健康检查为 $health"
    fi
  done < <("${COMPOSE[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null)
  for service in "${expected[@]}"; do
    [[ -n "${seen[$service]:-}" ]] || problem "容器 $service 不存在"
  done

  # 容器被 Docker 自动重启过：服务可能在崩溃后恢复，状态正常也要告知。
  restart_file="$STATE_DIR/restarts"
  touch "$restart_file"
  mapfile -t container_ids < <("${COMPOSE[@]}" ps -aq 2>/dev/null)
  while read -r name count; do
    [[ -n "$name" ]] || continue
    previous="$(sed -n "s|^$name ||p" "$restart_file" | tail -n 1)"
    if [[ -n "$previous" && "$count" -gt "$previous" ]]; then
      notices+=("容器 ${name#/} 自上次巡检后重启了 $((count - previous)) 次")
    fi
    printf '%s %s\n' "$name" "$count"
  done < <(((${#container_ids[@]})) && docker inspect -f '{{.Name}} {{.RestartCount}}' "${container_ids[@]}" 2>/dev/null) > "$restart_file.new" || true
  mv "$restart_file.new" "$restart_file"
fi

# 2. 页面与 API 网关。
for path in /healthz /api/gateway/status; do
  code="$(curl --noproxy '*' -s -o /dev/null -m 10 -w '%{http_code}' "$BASE_URL$path" || true)"
  [[ "$code" == "200" ]] || problem "$BASE_URL$path 返回 ${code:-无响应}"
done

# 3. 磁盘：Docker 数据目录与备份目录所在分区。
disk_paths=()
docker_root="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || true)"
[[ -n "$docker_root" && -d "$docker_root" ]] && disk_paths+=("$docker_root")
[[ -d "$BACKUP_ROOT" ]] && disk_paths+=("$BACKUP_ROOT")
for path in "${disk_paths[@]}"; do
  used="$(df -P "$path" 2>/dev/null | awk 'NR == 2 { sub("%", "", $5); print $5 }')"
  if [[ "$used" =~ ^[0-9]+$ ]] && (( 100 - used < DISK_MIN_FREE_PERCENT )); then
    problem "磁盘剩余空间不足：$path 所在分区已用 ${used}%"
  fi
done

# 4. 备份：最近一次成功备份不能太旧，也不能在其后又失败过。
if (( BACKUP_MAX_AGE_HOURS > 0 )); then
  success_file="$BACKUP_ROOT/last-success"
  failure_file="$BACKUP_ROOT/last-failure"
  if [[ ! -f "$success_file" ]]; then
    problem "还没有成功的备份（运行 ./backup.sh 或 ./schedule.sh install）"
  else
    completed="$(sed -n 's/^completed_at_utc=//p' "$success_file" | tail -n 1)"
    completed_epoch="$(date -u -d "$completed" +%s 2>/dev/null || echo 0)"
    age_hours=$(( ($(date -u +%s) - completed_epoch) / 3600 ))
    (( age_hours <= BACKUP_MAX_AGE_HOURS )) || problem "最近一次成功备份在 ${age_hours} 小时前（$completed）"
  fi
  if [[ -f "$failure_file" ]]; then
    problem "最近一次备份失败：$(tr '\n' ' ' < "$failure_file")"
  fi
fi

# 5. HTTPS 证书。
if [[ -n "$TLS_HOST" ]] && command -v openssl >/dev/null 2>&1; then
  end_date="$(echo | timeout 15 openssl s_client -servername "$TLS_HOST" -connect "$TLS_HOST:443" 2>/dev/null \
    | openssl x509 -noout -enddate 2>/dev/null | sed 's/^notAfter=//' || true)"
  if [[ -z "$end_date" ]]; then
    problem "无法读取 $TLS_HOST 的证书"
  else
    days_left=$(( ($(date -u -d "$end_date" +%s) - $(date -u +%s)) / 86400 ))
    (( days_left >= TLS_MIN_DAYS )) || problem "$TLS_HOST 的证书将在 ${days_left} 天后过期"
  fi
fi

# 结果与告警。
now="$(date -u +%s)"
status="OK"
(( ${#problems[@]} == 0 )) || status="FAIL"
signature="$(printf '%s\n' "${problems[@]}" | sed -E 's/[0-9]+/#/g' | sha256sum | cut -c1-16)"
state_file="$STATE_DIR/state"
last_status="OK"; last_signature=""; last_alert=0
if [[ -f "$state_file" ]]; then
  last_status="$(sed -n 's/^status=//p' "$state_file")"
  last_signature="$(sed -n 's/^signature=//p' "$state_file")"
  last_alert="$(sed -n 's/^last_alert=//p' "$state_file")"
fi

details="$(printf -- '- %s\n' "${problems[@]}")"
if [[ "$status" == "FAIL" ]]; then
  if [[ "$last_status" != "FAIL" || "$signature" != "$last_signature" ]] || (( now - ${last_alert:-0} >= REPEAT_HOURS * 3600 )); then
    send_alert "异常" "$details"
    last_alert="$now"
  else
    echo "仍然异常（已告警，$REPEAT_HOURS 小时内不重复）："$'\n'"$details"
  fi
elif [[ "$last_status" == "FAIL" ]]; then
  send_alert "已恢复" "所有检查均已通过。"
  last_alert="$now"
fi
if (( ${#notices[@]} > 0 )); then
  send_alert "提醒" "$(printf -- '- %s\n' "${notices[@]}")"
fi
printf 'status=%s\nsignature=%s\nlast_alert=%s\nchecked_at=%s\n' "$status" "$signature" "${last_alert:-0}" "$now" > "$state_file"

{
  printf '{"status":"%s","checked_at_utc":"%s","problems":[' "$status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  separator=""
  for item in "${problems[@]}"; do printf '%s"%s"' "$separator" "$(json_escape "$item")"; separator=","; done
  printf ']}\n'
} > "$STATE_DIR/status.json"

[[ "$status" == "OK" ]] && echo "巡检通过（$(date '+%F %T')）"
[[ "$status" == "OK" ]]
