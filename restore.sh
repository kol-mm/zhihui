#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
SOURCE="${1:-}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml")

if [[ -z "$SOURCE" || ! -d "$SOURCE" ]]; then
  echo "用法：RESTORE_CONFIRM=YES ./restore.sh /path/to/backup-directory" >&2
  exit 2
fi
if [[ "${RESTORE_CONFIRM:-}" != "YES" ]]; then
  echo "恢复会覆盖当前数据库和应用文件。确认后设置 RESTORE_CONFIRM=YES。" >&2
  exit 1
fi
[[ -f "$SOURCE/mysql-all.sql" && -f "$SOURCE/app-data.tar.gz" && -f "$SOURCE/SHA256SUMS" ]] || {
  echo "备份目录缺少 mysql-all.sql、app-data.tar.gz 或 SHA256SUMS。" >&2
  exit 1
}

(cd "$SOURCE" && sha256sum -c SHA256SUMS)

ai_container="$("${COMPOSE[@]}" ps -aq ai)"
[[ -n "$ai_container" ]] || { echo "找不到 AI 容器，请先执行 ./deploy.sh start。" >&2; exit 1; }

echo "停止业务容器……"
"${COMPOSE[@]}" stop frontend gateway user-service knowledge-service community-service message-service ai

echo "恢复 MySQL……"
"${COMPOSE[@]}" exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < "$SOURCE/mysql-all.sql"

echo "恢复上传文件和 AI 数据……"
docker run --rm --volumes-from "$ai_container" -v "$(cd "$SOURCE" && pwd):/backup:ro" alpine:3.20 \
  sh -c 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar xzf /backup/app-data.tar.gz -C /data'

echo "重新启动并验收……"
"$ROOT_DIR/deploy.sh" start
