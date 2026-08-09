#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
ENV_OWNER="$(stat -c '%u:%g' "$ENV_FILE" 2>/dev/null || true)"
DOMAIN="${1:-}"
EMAIL="${2:-}"
UPSTREAM_PORT="${HTTPS_UPSTREAM_PORT:-8088}"
NGINX_SITE="/etc/nginx/sites-available/zhihui.conf"

usage() {
  echo "用法：sudo ./setup-https.sh <域名> <证书通知邮箱>" >&2
}

upsert_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s/^${key}=.*/${key}=${value}/" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

[[ "$EUID" -eq 0 ]] || {
  echo "请使用 sudo 运行该脚本。" >&2
  usage
  exit 1
}

[[ "$DOMAIN" =~ ^([A-Za-z0-9][A-Za-z0-9-]*\.)+[A-Za-z]{2,63}$ ]] || {
  echo "域名格式不正确，不要填写 http://、https:// 或路径。" >&2
  usage
  exit 1
}

[[ "$EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
  echo "证书通知邮箱格式不正确。" >&2
  usage
  exit 1
}

[[ "$UPSTREAM_PORT" =~ ^[0-9]+$ ]] && (( UPSTREAM_PORT >= 1 && UPSTREAM_PORT <= 65535 )) || {
  echo "HTTPS_UPSTREAM_PORT 必须是 1-65535 之间的端口号。" >&2
  exit 1
}

[[ -f "$ENV_FILE" ]] || {
  echo "找不到 $ENV_FILE，请先执行 ./deploy.sh deploy 并配置环境变量。" >&2
  exit 1
}

command -v apt-get >/dev/null 2>&1 || {
  echo "当前脚本支持 Debian/Ubuntu；其他发行版请按 docs/docker-linux.md 手动配置。" >&2
  exit 1
}

upsert_env HTTP_BIND_ADDRESS 127.0.0.1
upsert_env HTTP_PORT "$UPSTREAM_PORT"
chmod 600 "$ENV_FILE"
if [[ -n "$ENV_OWNER" ]]; then
  chown "$ENV_OWNER" "$ENV_FILE"
fi

echo "正在把应用入口迁移到 127.0.0.1:${UPSTREAM_PORT}……"
"$ROOT_DIR/deploy.sh" update
curl -fsS "http://127.0.0.1:${UPSTREAM_PORT}/healthz" >/dev/null

echo "正在安装并配置 Nginx 与 Certbot……"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y nginx certbot python3-certbot-nginx

sed \
  -e "s/__DOMAIN__/${DOMAIN}/g" \
  -e "s/__UPSTREAM_PORT__/${UPSTREAM_PORT}/g" \
  "$ROOT_DIR/deploy/nginx/host-https.conf.template" > "$NGINX_SITE"
ln -sfn "$NGINX_SITE" /etc/nginx/sites-enabled/zhihui.conf

nginx -t
systemctl enable --now nginx
systemctl reload nginx

echo "正在申请证书并启用 HTTP 到 HTTPS 跳转……"
certbot --nginx \
  --non-interactive \
  --agree-tos \
  --redirect \
  --email "$EMAIL" \
  -d "$DOMAIN"

systemctl enable --now certbot.timer >/dev/null 2>&1 || true
curl -fsS "https://${DOMAIN}/healthz" >/dev/null

echo "HTTPS 已启用：https://${DOMAIN}"
echo "证书续期检查：sudo certbot renew --dry-run"
