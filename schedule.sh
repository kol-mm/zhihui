#!/usr/bin/env bash
# 安装或移除定时任务：每日备份（backup.sh）和每 5 分钟巡检（monitor.sh）。
# 用法：./schedule.sh install | remove | status | print
#   以 root 运行时安装为系统级 systemd 定时器；普通用户安装为用户级定时器（需 loginctl enable-linger 才能在未登录时运行）；
#   没有 systemd 时写入当前用户的 crontab。
# 时间可在 .env 中调整：BACKUP_SCHEDULE（systemd OnCalendar 格式，默认每天 03:30）、MONITOR_INTERVAL（默认 5min）。
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
ACTION="${1:-status}"
UNITS=(zhihui-backup.service zhihui-backup.timer zhihui-monitor.service zhihui-monitor.timer)
CRON_MARK="# zhihui-schedule"

setting() {
  local key="$1" fallback="$2" value="${!1:-}"
  if [[ -z "$value" && -f "$ENV_FILE" ]]; then
    value="$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')"
  fi
  printf '%s' "${value:-$fallback}"
}

BACKUP_SCHEDULE="$(setting BACKUP_SCHEDULE '*-*-* 03:30:00')"
MONITOR_INTERVAL="$(setting MONITOR_INTERVAL 5min)"

if [[ "$(id -u)" == "0" ]]; then
  UNIT_DIR=/etc/systemd/system
  SYSTEMCTL=(systemctl)
else
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  SYSTEMCTL=(systemctl --user)
fi

have_systemd() {
  command -v systemctl >/dev/null 2>&1 && "${SYSTEMCTL[@]}" show-environment >/dev/null 2>&1
}

unit_text() {
  local unit="$1"
  local owner=""
  [[ "$(id -u)" == "0" ]] && owner="User=$(stat -c %U "$ROOT_DIR")"
  case "$unit" in
    zhihui-backup.service) cat <<EOF
[Unit]
Description=知汇每日备份（MySQL 与上传文件）
Wants=network-online.target
After=network-online.target docker.service

[Service]
Type=oneshot
$owner
WorkingDirectory=$ROOT_DIR
Environment=ENV_FILE=$ENV_FILE
ExecStart=$ROOT_DIR/backup.sh
Nice=10
IOSchedulingClass=idle
EOF
    ;;
    zhihui-backup.timer) cat <<EOF
[Unit]
Description=知汇每日备份定时器

[Timer]
OnCalendar=$BACKUP_SCHEDULE
Persistent=true
RandomizedDelaySec=5min

[Install]
WantedBy=timers.target
EOF
    ;;
    zhihui-monitor.service) cat <<EOF
[Unit]
Description=知汇服务巡检与告警
After=docker.service

[Service]
Type=oneshot
$owner
WorkingDirectory=$ROOT_DIR
Environment=ENV_FILE=$ENV_FILE
ExecStart=$ROOT_DIR/monitor.sh
# 巡检发现问题时退出码为 1；这是预期结果，不应让单元进入失败状态。
SuccessExitStatus=1
EOF
    ;;
    zhihui-monitor.timer) cat <<EOF
[Unit]
Description=知汇服务巡检定时器

[Timer]
OnBootSec=3min
OnUnitActiveSec=$MONITOR_INTERVAL
AccuracySec=30s

[Install]
WantedBy=timers.target
EOF
    ;;
  esac
}

cron_lines() {
  local interval="${MONITOR_INTERVAL%min}"
  [[ "$interval" =~ ^[0-9]+$ ]] || interval=5
  cat <<EOF
30 3 * * * cd "$ROOT_DIR" && ENV_FILE="$ENV_FILE" ./backup.sh >> "$ROOT_DIR/.monitor/backup.log" 2>&1 $CRON_MARK
*/$interval * * * * cd "$ROOT_DIR" && ENV_FILE="$ENV_FILE" ./monitor.sh >> "$ROOT_DIR/.monitor/monitor.log" 2>&1 $CRON_MARK
EOF
}

case "$ACTION" in
  print)
    for unit in "${UNITS[@]}"; do printf '### %s\n' "$unit"; unit_text "$unit"; echo; done
    printf '### crontab（无 systemd 时）\n'; cron_lines
    ;;
  install)
    chmod +x "$ROOT_DIR/backup.sh" "$ROOT_DIR/monitor.sh"
    mkdir -p "$ROOT_DIR/.monitor"
    if have_systemd; then
      mkdir -p "$UNIT_DIR"
      for unit in "${UNITS[@]}"; do unit_text "$unit" > "$UNIT_DIR/$unit"; done
      "${SYSTEMCTL[@]}" daemon-reload
      "${SYSTEMCTL[@]}" enable --now zhihui-backup.timer zhihui-monitor.timer
      echo "已安装 systemd 定时器（$UNIT_DIR）："
      "${SYSTEMCTL[@]}" list-timers 'zhihui-*' --no-pager || true
      if [[ "$(id -u)" != "0" ]] && [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]]; then
        echo "提示：当前用户未开启 linger，注销后定时任务不会运行。可执行：sudo loginctl enable-linger $USER"
      fi
    else
      command -v crontab >/dev/null 2>&1 || { echo "既没有 systemd 也没有 crontab，无法安装定时任务。" >&2; exit 1; }
      { crontab -l 2>/dev/null | grep -v "$CRON_MARK" || true; cron_lines; } | crontab -
      echo "已写入 crontab："
      crontab -l | grep "$CRON_MARK"
    fi
    ;;
  remove)
    if have_systemd; then
      "${SYSTEMCTL[@]}" disable --now zhihui-backup.timer zhihui-monitor.timer 2>/dev/null || true
      for unit in "${UNITS[@]}"; do rm -f "$UNIT_DIR/$unit"; done
      "${SYSTEMCTL[@]}" daemon-reload
    fi
    if command -v crontab >/dev/null 2>&1 && crontab -l 2>/dev/null | grep -q "$CRON_MARK"; then
      crontab -l | grep -v "$CRON_MARK" | crontab -
    fi
    echo "已移除定时任务。"
    ;;
  status)
    if have_systemd; then
      "${SYSTEMCTL[@]}" list-timers 'zhihui-*' --no-pager || true
    fi
    if command -v crontab >/dev/null 2>&1; then crontab -l 2>/dev/null | grep "$CRON_MARK" || true; fi
    [[ -f "$ROOT_DIR/.monitor/status.json" ]] && { echo "最近巡检："; cat "$ROOT_DIR/.monitor/status.json"; }
    backup_root="$(setting BACKUP_ROOT "$ROOT_DIR/backups-docker")"
    [[ -f "$backup_root/last-success" ]] && { echo "最近备份："; cat "$backup_root/last-success"; }
    [[ -f "$backup_root/last-failure" ]] && { echo "最近失败："; cat "$backup_root/last-failure"; }
    true
    ;;
  *)
    echo "用法：./schedule.sh [install|remove|status|print]" >&2
    exit 2
    ;;
esac
