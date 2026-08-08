#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups-docker}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml")

[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "需要 Docker Compose v2。" >&2; exit 1; }

stamp="$(date -u +%Y%m%d-%H%M%S)"
target="$BACKUP_ROOT/$stamp"
mkdir -p "$target"
chmod 700 "$target"

mysql_container="$("${COMPOSE[@]}" ps -q mysql)"
ai_container="$("${COMPOSE[@]}" ps -q ai)"
[[ -n "$mysql_container" && -n "$ai_container" ]] || {
  echo "MySQL 或 AI 容器未运行，无法创建一致备份。" >&2
  exit 1
}

paused=0
resume_services() {
  if [[ "$paused" == "1" ]]; then
    "${COMPOSE[@]}" unpause frontend gateway user-service knowledge-service community-service message-service ai >/dev/null 2>&1 || true
  fi
}
trap resume_services EXIT

echo "短暂停止业务写入以创建一致备份……"
"${COMPOSE[@]}" pause frontend gateway user-service knowledge-service community-service message-service ai
paused=1

echo "备份 MySQL……"
"${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases --single-transaction --routines --events --add-drop-database' \
  > "$target/mysql-all.sql"

echo "备份上传文件和 AI 数据……"
docker run --rm --volumes-from "$ai_container" -v "$target:/backup" alpine:3.20 \
  tar czf /backup/app-data.tar.gz -C /data .

cat > "$target/manifest.txt" <<EOF
created_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
branch=docker-linux
contents=mysql-all.sql,app-data.tar.gz
EOF

(cd "$target" && sha256sum mysql-all.sql app-data.tar.gz > SHA256SUMS)
"${COMPOSE[@]}" unpause frontend gateway user-service knowledge-service community-service message-service ai
paused=0
echo "备份完成：$target"
