#!/usr/bin/env bash
# 创建一份一致的完整备份：全部 MySQL 数据库 + 上传文件和 AI 数据。
# 可由 ./schedule.sh 安装为每日定时任务；可选设置见 .env.example 中的 BACKUP_*。
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml")
APP_SERVICES=(frontend gateway user-service knowledge-service community-service message-service ai)

[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "需要 Docker Compose v2。" >&2; exit 1; }

# 环境变量优先，其次读取 .env；只读取备份相关的键，不执行 .env 内容。
setting() {
  local key="$1" fallback="$2" value="${!1:-}"
  if [[ -z "$value" ]]; then
    value="$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')"
  fi
  printf '%s' "${value:-$fallback}"
}

BACKUP_ROOT="$(setting BACKUP_ROOT "$ROOT_DIR/backups-docker")"
KEEP_DAYS="$(setting BACKUP_KEEP_DAYS 14)"
KEEP_MIN="$(setting BACKUP_KEEP_MIN 3)"
PAUSE_APP="$(setting BACKUP_PAUSE_APP 1)"
POST_HOOK="$(setting BACKUP_POST_HOOK "")"
[[ "$KEEP_DAYS" =~ ^[0-9]+$ && "$KEEP_MIN" =~ ^[0-9]+$ ]] || { echo "BACKUP_KEEP_DAYS 和 BACKUP_KEEP_MIN 必须是非负整数。" >&2; exit 1; }

mkdir -p "$BACKUP_ROOT"
chmod 700 "$BACKUP_ROOT"

# 同一时间只允许一个备份（定时任务与手动执行可能重叠）。
exec 9>"$BACKUP_ROOT/.backup.lock"
flock -n 9 || { echo "已有备份正在进行，跳过本次。" >&2; exit 1; }

stamp="$(date -u +%Y%m%d-%H%M%S)"
target="$BACKUP_ROOT/$stamp"
partial="$target.partial"
paused=0

resume_services() {
  if [[ "$paused" == "1" ]]; then
    "${COMPOSE[@]}" unpause "${APP_SERVICES[@]}" >/dev/null 2>&1 || true
    paused=0
  fi
}
record_failure() {
  local status=$?
  trap - ERR
  resume_services
  rm -rf "$partial"
  printf 'failed_at_utc=%s\nexit_code=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$status" > "$BACKUP_ROOT/last-failure"
  echo "备份失败（退出码 $status）。" >&2
  exit "$status"
}
trap record_failure ERR
trap resume_services EXIT

mysql_container="$("${COMPOSE[@]}" ps -q mysql)"
ai_container="$("${COMPOSE[@]}" ps -q ai)"
if [[ -z "$mysql_container" || -z "$ai_container" ]]; then
  echo "MySQL 或 AI 容器未运行，无法创建一致备份。" >&2
  false
fi

mkdir -p "$partial"
chmod 700 "$partial"

if [[ "$PAUSE_APP" == "1" ]]; then
  # 数据库导出本身是一致快照；暂停业务写入是为了让数据库与上传文件处于同一时刻。
  echo "短暂停止业务写入以创建一致备份……"
  "${COMPOSE[@]}" pause "${APP_SERVICES[@]}"
  paused=1
fi

echo "备份 MySQL……"
# 只导出业务库：MySQL 8 不允许在恢复时删除并重建 mysql 等系统库（错误 3552）。账号由初始化脚本按 .env 创建。
# shellcheck disable=SC2016 # expanded by the shell inside the MySQL container
"${COMPOSE[@]}" exec -T mysql sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  databases=$(mysql -uroot -N -e "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('"'"'mysql'"'"', '"'"'information_schema'"'"', '"'"'performance_schema'"'"', '"'"'sys'"'"')")
  exec mysqldump -uroot --databases $databases --single-transaction --routines --events --triggers --add-drop-database --set-gtid-purged=OFF' \
  | gzip -6 > "$partial/mysql-all.sql.gz"

echo "备份上传文件和 AI 数据……"
docker run --rm --volumes-from "$ai_container" -v "$partial:/backup" alpine:3.20 \
  tar czf /backup/app-data.tar.gz -C /data .

resume_services

echo "校验备份……"
gzip -t "$partial/mysql-all.sql.gz"
gzip -t "$partial/app-data.tar.gz"
# mysqldump 只有完整结束时才会写出这一行。
if ! zcat "$partial/mysql-all.sql.gz" | tail -n 1 | grep -q '^-- Dump completed'; then
  echo "MySQL 导出不完整。" >&2
  false
fi

cat > "$partial/manifest.txt" <<EOF
created_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
branch=docker-linux
commit=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
contents=mysql-all.sql.gz,app-data.tar.gz
note=.env 中的密码和密钥不在备份内，请另行妥善保存。
EOF
(cd "$partial" && sha256sum mysql-all.sql.gz app-data.tar.gz > SHA256SUMS)
mv "$partial" "$target"
size="$(du -sh "$target" | cut -f1)"
printf 'completed_at_utc=%s\npath=%s\nsize=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$target" "$size" > "$BACKUP_ROOT/last-success"
rm -f "$BACKUP_ROOT/last-failure"
echo "备份完成：$target（$size）"

# 保留策略：删除超过 KEEP_DAYS 天的备份，但始终保留最新的 KEEP_MIN 份。只处理本脚本创建的目录。
mapfile -t backups < <(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended \
  -regex '.*/[0-9]{8}-[0-9]{6}' -printf '%f\n' | sort -r)
cutoff="$(date -u -d "-$KEEP_DAYS days" +%Y%m%d-%H%M%S)"
for index in "${!backups[@]}"; do
  name="${backups[$index]}"
  if (( index >= KEEP_MIN )) && [[ "$name" < "$cutoff" ]]; then
    rm -rf "${BACKUP_ROOT:?}/$name"
    echo "已删除过期备份：$name"
  fi
done
# 中断遗留的半成品目录（超过一天）。
find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '*.partial' -mmin +1440 -exec rm -rf {} + 2>/dev/null || true

if [[ -n "$POST_HOOK" ]]; then
  # 例如 BACKUP_POST_HOOK="rclone copy {} remote:zhihui-backups/{name}"：{} 为备份目录，{name} 为目录名。
  hook="${POST_HOOK//\{name\}/$stamp}"
  hook="${hook//\{\}/$target}"
  echo "执行 BACKUP_POST_HOOK……"
  bash -c "$hook"
fi
