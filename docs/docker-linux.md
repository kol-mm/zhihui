# Docker Linux 一键部署

本文档适用于 `docker-linux` 分支。该方案面向单台 4 GB Linux 服务器，使用 Docker Compose 运行 MySQL、AI 服务、五个 Java 服务以及 Vue + Nginx 前端，不启动 Nacos、MinIO、Redis、RabbitMQ、Elasticsearch 或 Milvus。

完成 Docker 安装和 `.env` 配置后，项目通过以下命令一键构建、启动和验收：

```bash
./deploy.sh
```

## 1. 部署架构

```text
公网用户
   │
   ▼
宿主机 80/443（可选 HTTPS 反代）
   │
   ▼
frontend：Vue 静态文件 + Nginx /api 反代
   │
   ▼
gateway：统一 API 网关
   ├─ user-service
   ├─ knowledge-service
   ├─ community-service
   ├─ message-service
   └─ ai

mysql：仅 Docker 内部网络访问
```

Compose 默认只发布前端端口。MySQL、网关、AI 和业务服务不会映射到宿主机端口。

## 2. 服务器配置

推荐最低配置：

- Ubuntu 22.04/24.04 或 Debian 12
- 2 核 CPU
- 4 GB 内存
- 20 GB 以上 SSD 可用空间
- 2 GB 交换空间
- 具有 `sudo` 权限的非 root 用户
- 可访问 GitHub、Docker Hub、Maven、npm 和 PyPI

先检查系统：

```bash
cat /etc/os-release
uname -m
free -h
df -h /
```

项目镜像支持常见的 `x86_64/amd64` 环境。ARM 服务器需要确认 MySQL、Java、Node 和 Python 基础镜像均能正常拉取。

## 3. 设置时区和基础工具

```bash
sudo timedatectl set-timezone Asia/Shanghai
sudo apt-get update
sudo apt-get install -y ca-certificates curl dnsutils gnupg git openssl ufw
```

确认时间正确：

```bash
date
timedatectl status
```

时间错误会影响 JWT 有效期、日志时间和 HTTPS 证书验证。

## 4. 配置交换空间

4 GB 服务器首次构建多个 Java 镜像时可能出现内存峰值。先检查是否已有交换空间：

```bash
swapon --show
```

只有在没有输出时才创建 2 GB 交换文件：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

检查结果：

```bash
free -h
swapon --show
```

## 5. 安装 Docker Engine 和 Compose

下面使用 Docker 官方 APT 仓库，适用于 Ubuntu 和 Debian：

```bash
. /etc/os-release
echo "$ID $VERSION_CODENAME"
```

`ID` 应为 `ubuntu` 或 `debian`。然后执行：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL "https://download.docker.com/linux/${ID}/gpg" \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

允许当前用户执行 Docker：

```bash
sudo usermod -aG docker "$USER"
```

退出 SSH 并重新登录，使用户组生效。重新登录后验证：

```bash
docker version
docker compose version
docker run --rm hello-world
```

不要通过开放 Docker TCP API 的方式解决权限问题。

## 6. 配置防火墙

先确保 SSH 不会被拦截：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

不要开放以下内部端口：

- `3306`：MySQL
- `8080`：网关
- `8101-8104`：业务服务
- `8200`：AI 服务

如果云服务器还配置了安全组，也需要在云控制台开放 `22`、`80` 和 `443`。

## 7. 获取 docker-linux 分支

建议部署到 `/opt/zhihui`：

```bash
sudo mkdir -p /opt/zhihui
sudo chown "$USER":"$USER" /opt/zhihui

git clone https://github.com/kol-mm/zhihui.git /opt/zhihui
cd /opt/zhihui
git switch docker-linux
git status
```

确认当前分支：

```bash
git branch --show-current
```

应输出：

```text
docker-linux
```

## 8. 生成部署密钥

复制配置模板：

```bash
cd /opt/zhihui
cp .env.example .env
chmod 600 .env
```

可以用 OpenSSL 生成只包含十六进制字符的随机值，避免 `.env` 特殊字符转义问题：

```bash
MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
MYSQL_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 48)"
INTERNAL_TOKEN="$(openssl rand -hex 32)"

cat > .env <<EOF
HTTP_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8088

MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_USER=zhihui
MYSQL_PASSWORD=${MYSQL_PASSWORD}

AI_KNOWLEDGE_JWT_SECRET=${JWT_SECRET}
AI_API_KEY=
AI_API_TIMEOUT=30
INTERNAL_NOTIFICATION_TOKEN=${INTERNAL_TOKEN}
EOF

chmod 600 .env
unset MYSQL_ROOT_PASSWORD MYSQL_PASSWORD JWT_SECRET INTERNAL_TOKEN
```

配置说明：

| 配置 | 说明 |
| --- | --- |
| `HTTP_BIND_ADDRESS` | `0.0.0.0` 表示直接对外提供 HTTP；`127.0.0.1` 表示只允许宿主机反代访问 |
| `HTTP_PORT` | 前端映射到宿主机的端口，默认 `80` |
| `MAVEN_MIRROR_URL` | Java 依赖仓库，默认使用阿里云 Maven 公共仓库 |
| `PIP_INDEX_URL` | Python 依赖仓库，默认使用阿里云 PyPI 镜像 |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码，只供初始化和备份恢复使用 |
| `MYSQL_USER` | 应用数据库账号，只能包含字母、数字和下划线 |
| `MYSQL_PASSWORD` | 应用数据库密码 |
| `AI_KNOWLEDGE_JWT_SECRET` | Java 与 AI 服务共享的 JWT 签名密钥 |
| `AI_API_KEY` | 使用外部 OpenAI 兼容接口时填写；本地 AI 模式可留空 |
| `INTERNAL_NOTIFICATION_TOKEN` | 社区服务调用消息服务时使用的内部令牌 |

检查文件权限和配置，但不要把密码输出到终端：

```bash
stat -c '%a %n' .env
grep -E '^(HTTP_BIND_ADDRESS|HTTP_PORT|MYSQL_USER|AI_API_TIMEOUT)=' .env
```

`.env` 权限应为 `600`。

如果默认国内镜像在当前地区不可用，可以修改为其他可信镜像。例如切回官方源：

```dotenv
MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
PIP_INDEX_URL=https://pypi.org/simple
```

修改镜像地址后执行 `./deploy.sh update`，Docker 会重新运行对应的依赖安装层。

## 9. 部署前检查

检查 HTTPS 公网入口和容器本机入口是否被占用：

```bash
sudo ss -ltnp | grep -E ':(80|443|8088)\s' || true
```

容器默认只监听宿主机回环地址，不直接暴露公网：

```dotenv
HTTP_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8088
```

公网的 `80/443` 由宿主机 Nginx 使用。如果 `8088` 被占用，可以改为其他未占用端口；运行 `setup-https.sh` 时同时设置相同的 `HTTPS_UPSTREAM_PORT`。

检查 Compose 配置：

```bash
docker compose --env-file .env -f compose.yaml config --quiet
```

无输出且退出码为 `0` 表示配置可以被 Compose 解析。

## 10. 首次一键部署

```bash
cd /opt/zhihui
chmod +x deploy.sh setup-https.sh backup.sh restore.sh
./deploy.sh
```

脚本依次执行：

1. 检查 Docker Compose、`curl` 和 `.env`。
2. 验证端口、数据库账号和必填密钥。
3. 拉取 MySQL、Java、Python、Node 和 Nginx 基础镜像。
4. 构建五个 Java 可执行 JAR 镜像。
5. 构建 FastAPI AI 镜像。
6. 构建 Vue 前端并复制到 Nginx 镜像。
7. 初始化 MySQL 数据库和应用账号权限。
8. 启动全部容器并等待健康检查。
9. 检查前端、用户服务和 AI 服务。
10. 输出容器状态和访问地址。

首次构建通常需要数分钟到数十分钟。SSH 断开会中止前台构建，网络不稳定时建议在 `tmux` 或 `screen` 中执行：

```bash
sudo apt-get install -y tmux
tmux new -s zhihui-deploy
cd /opt/zhihui
./deploy.sh
```

在 tmux 中按 `Ctrl+B`，再按 `D` 可退出会话；重新进入：

```bash
tmux attach -t zhihui-deploy
```

## 11. 首次验收

查看容器状态：

```bash
cd /opt/zhihui
./deploy.sh status
```

所有容器应为 `running`，带健康检查的容器应为 `healthy`。

读取配置中的端口：

```bash
HTTP_PORT="$(sed -n 's/^HTTP_PORT=//p' .env | tail -n 1)"
HTTP_PORT="${HTTP_PORT:-8088}"
```

检查前端和 API：

```bash
curl -fsS "http://127.0.0.1:${HTTP_PORT}/healthz"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/gateway/status"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/user/health"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/knowledge/health"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/post/health"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/message/health"
curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/ai/health"
```

此时只能在服务器本机通过 `http://127.0.0.1:8088` 访问，这是正常的安全状态。完成下一节的 HTTPS 配置后再从本地电脑访问域名。

本地初始化账号仅用于验收：

- 普通用户：`demo / demo`
- 管理员：`admin / admin123`

正式开放公网前，应根据实际用户管理策略处理测试账号，并确认管理员页面、知识上传、图片访问、社区互动、消息和 AI 问答都能正常使用。

## 12. 配置域名和 HTTPS

### 12.1 部署结构

```text
浏览器
  -> HTTPS :443（宿主机 Nginx，TLS 证书）
  -> HTTP 127.0.0.1:8088（Docker 前端 Nginx，不可从公网直连）
  -> /api 转发到 Docker 网关
  -> 用户、知识、社区、消息、AI 和 MySQL（仅 Docker 内网）
```

宿主机 Nginx 负责 TLS、域名入口和 HTTP 到 HTTPS 跳转；Docker 前端负责 Vue 静态文件和 `/api` 路由。数据库及后端服务没有映射宿主机端口，因此公网只能接触到 `80/443`。

### 12.2 前置条件

1. 准备一个域名，例如 `example.com`，在域名服务商处添加 `A` 记录并指向服务器公网 IPv4。
2. 云服务器安全组和系统防火墙允许入站 TCP `80`、`443`；不要对公网开放 `8088`、`3306`、`8080` 或其他内部端口。
3. 确认域名解析已经生效：

```bash
dig +short example.com
```

输出应当是当前服务器公网 IP。证书机构必须能通过公网访问域名的 `80` 端口，否则无法完成首次签发。

### 12.3 一键启用 HTTPS（Ubuntu/Debian）

更新到包含 HTTPS 脚本的版本后执行：

```bash
cd /opt/zhihui
git pull --ff-only
chmod +x deploy.sh setup-https.sh
sudo ./setup-https.sh example.com admin@example.com
```

第一个参数是域名，第二个参数是证书到期通知邮箱。脚本会：

1. 把 `.env` 的容器入口改为 `127.0.0.1:8088` 并更新容器。
2. 安装宿主机 Nginx、Certbot 和 Nginx 插件。
3. 从 `deploy/nginx/host-https.conf.template` 生成反向代理配置。
4. 申请 Let's Encrypt 证书并将 HTTP 自动跳转到 HTTPS。
5. 启用证书续期定时器并检查 HTTPS 健康接口。

如果容器本机入口使用其他端口，例如 `8090`：

```bash
sudo HTTPS_UPSTREAM_PORT=8090 ./setup-https.sh example.com admin@example.com
```

最终访问：

```text
https://example.com
```

验证配置、入口和自动续期：

```bash
sudo nginx -t
curl -I http://example.com
curl -fsS https://example.com/healthz
sudo certbot certificates
sudo certbot renew --dry-run
systemctl status certbot.timer --no-pager
```

`curl -I http://example.com` 应返回跳转到 `https://example.com`。

### 12.4 手动配置

如果不是 Ubuntu/Debian，先用发行版的软件包管理器安装 Nginx、Certbot 和 Certbot Nginx 插件。然后让 Docker 前端只监听本机端口，编辑 `.env`：

```dotenv
HTTP_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8088
```

应用配置：

```bash
cd /opt/zhihui
./deploy.sh update
curl -fsS http://127.0.0.1:8088/healthz
```

安装宿主机 Nginx 和 Certbot（Ubuntu/Debian 示例）：

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

复制模板并替换域名、端口：

```bash
sudo sed \
  -e 's/__DOMAIN__/example.com/g' \
  -e 's/__UPSTREAM_PORT__/8088/g' \
  deploy/nginx/host-https.conf.template \
  | sudo tee /etc/nginx/sites-available/zhihui.conf >/dev/null
```

将 `example.com` 替换为真实域名，然后启用站点：

```bash
sudo ln -sf /etc/nginx/sites-available/zhihui.conf /etc/nginx/sites-enabled/zhihui.conf
sudo nginx -t
sudo systemctl reload nginx
```

申请 HTTPS 证书：

```bash
sudo certbot --nginx --redirect -d example.com
sudo certbot renew --dry-run
```

## 13. 日常管理命令

```bash
cd /opt/zhihui

# 查看状态
./deploy.sh status

# 持续查看全部日志
./deploy.sh logs

# 查看单个服务最近 200 行日志
docker compose --env-file .env logs --tail=200 gateway
docker compose --env-file .env logs --tail=200 mysql

# 重启全部容器，不重新构建
./deploy.sh restart

# 停止容器
./deploy.sh stop

# 启动已有容器
./deploy.sh start

# 删除容器和网络，但保留数据卷
./deploy.sh down
```

查看资源占用：

```bash
docker stats
free -h
df -h
docker system df
```

## 14. 更新版本

更新前先备份：

```bash
cd /opt/zhihui
BACKUP_ROOT=/mnt/backup/zhihui ./backup.sh
```

确认工作区没有本地修改：

```bash
git status
```

然后更新代码和容器：

```bash
git pull --ff-only
./deploy.sh update
```

`update` 会优先复用服务器已有的基础镜像和构建缓存，重新构建发生变化的内容，并使用原有持久卷重建容器。只执行 `restart` 不会应用镜像、Compose 或 `.env` 的变化。

需要主动检查并拉取最新基础镜像时执行：

```bash
./deploy.sh refresh
```

`refresh` 会访问 Docker Hub；网络不稳定时应继续使用普通的 `update`。

## 15. 数据持久化

Compose 创建两个命名卷：

- `zhihui_mysql_data`：MySQL 数据
- `zhihui_app_data`：知识附件、知识图片、社区图片、头像和 AI SQLite

查看卷：

```bash
docker volume ls | grep zhihui
docker volume inspect zhihui_mysql_data
docker volume inspect zhihui_app_data
```

以下命令不会删除卷：

```bash
./deploy.sh down
```

不要执行 `docker compose down -v` 或手动删除上述卷，除非已经确认要永久清空业务数据并且有可用备份。

## 16. 手动备份

建议将备份保存到独立磁盘或远程挂载点：

```bash
sudo mkdir -p /mnt/backup/zhihui
sudo chown "$USER":"$USER" /mnt/backup/zhihui

cd /opt/zhihui
BACKUP_ROOT=/mnt/backup/zhihui ./backup.sh
```

备份脚本会短暂停止业务写入，并生成：

```text
mysql-all.sql
app-data.tar.gz
manifest.txt
SHA256SUMS
```

检查最近备份：

```bash
ls -lah /mnt/backup/zhihui
latest="$(find /mnt/backup/zhihui -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
cd "$latest"
sha256sum -c SHA256SUMS
```

## 17. 定时备份

编辑当前部署用户的定时任务：

```bash
crontab -e
```

每天凌晨 3 点备份：

```cron
0 3 * * * cd /opt/zhihui && BACKUP_ROOT=/mnt/backup/zhihui ./backup.sh >> /mnt/backup/zhihui/backup.log 2>&1
```

查看定时任务：

```bash
crontab -l
tail -n 100 /mnt/backup/zhihui/backup.log
```

还应将 `/mnt/backup/zhihui` 同步到另一台服务器或对象存储。只保存在同一块系统盘不算可靠备份。

## 18. 恢复备份

恢复会覆盖当前 MySQL 和应用文件。先再次备份当前状态：

```bash
cd /opt/zhihui
BACKUP_ROOT=/mnt/backup/zhihui-before-restore ./backup.sh
```

检查目标备份：

```bash
cd /mnt/backup/zhihui/20260809-030000
sha256sum -c SHA256SUMS
```

确认后执行恢复：

```bash
cd /opt/zhihui
RESTORE_CONFIRM=YES ./restore.sh /mnt/backup/zhihui/20260809-030000
```

恢复脚本会：

1. 校验备份文件 SHA-256。
2. 停止前端和业务容器。
3. 恢复 MySQL 全库。
4. 覆盖应用数据卷。
5. 重新启动并执行健康检查。

恢复完成后重新执行第 11 节的接口验收。

## 19. 代码版本回退

先查看提交：

```bash
cd /opt/zhihui
git log --oneline -10
```

回退应用代码前必须先创建备份。临时切换到指定提交：

```bash
git switch --detach <commit-id>
./deploy.sh update
```

回到最新容器分支：

```bash
git switch docker-linux
git pull --ff-only
./deploy.sh update
```

如果新版本改变了数据库结构，仅切换代码可能不够，应同时恢复与旧版本对应的数据库和应用数据备份。

## 20. 常见故障排查

### Docker 命令没有权限

```bash
groups
sudo usermod -aG docker "$USER"
```

重新登录 SSH 后再运行 `docker ps`。不要用 `chmod 777 /var/run/docker.sock`。

### 80 端口被占用

```bash
sudo ss -ltnp | grep ':80 '
```

停止冲突服务，或将 `.env` 中的 `HTTP_PORT` 改为其他端口后执行：

```bash
./deploy.sh update
```

### 镜像下载或依赖下载超时

如果日志停在下面这一类信息：

```text
resolve image config for docker.io/docker/dockerfile:1.7
```

说明 Docker 正在下载 Dockerfile 前端镜像，而不是项目代码编译失败。当前分支已经移除了这项额外依赖。先更新代码：

```bash
cd /opt/zhihui
git pull --ff-only
grep -R "docker/dockerfile" deploy/docker || true
./deploy.sh update
```

`grep` 正常情况下不应输出内容。

检查 DNS 和网络：

```bash
curl -I https://registry-1.docker.io
curl -I https://repo.maven.apache.org
curl -I https://registry.npmjs.org
curl -I https://pypi.org
```

访问 `https://registry-1.docker.io/v2/` 返回 `401 Unauthorized` 属于正常现象，表示网络已经连通但请求未登录。连接超时、DNS 失败或 TLS 握手失败才属于网络问题。

先分别测试基础镜像：

```bash
docker pull python:3.11-slim
docker pull node:20-alpine
docker pull nginx:1.27-alpine
docker pull mysql:8.0.39
docker pull maven:3.9.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre-jammy
```

如果 Docker Hub 在服务器所在地区持续不可达，应在 `/etc/docker/daemon.json` 配置云服务商提供的官方镜像加速地址。示例结构如下，地址需要替换成云厂商控制台分配的真实地址：

```json
{
  "registry-mirrors": [
    "https://你的镜像加速地址"
  ]
}
```

配置后重启 Docker：

```bash
sudo systemctl daemon-reload
sudo systemctl restart docker
docker info | sed -n '/Registry Mirrors/,+5p'
```

不要随意使用来源不明的公共镜像站，镜像加速地址应来自服务器云厂商或可信的私有镜像仓库。

基础镜像下载和项目依赖下载是两条不同链路：

- `load metadata for docker.io/library/...`：Docker 基础镜像网络问题，由 Docker daemon 镜像加速或代理解决。
- Maven 日志中的依赖下载超时：修改 `.env` 的 `MAVEN_MIRROR_URL`。
- pip 日志中的 Python 包下载超时：修改 `.env` 的 `PIP_INDEX_URL`。

检查 Compose 展开后的构建参数：

```bash
docker compose --env-file .env config | grep -E 'MAVEN_MIRROR_URL|PIP_INDEX_URL'
```

网络恢复后再次运行 `./deploy.sh`，Docker BuildKit 会复用已完成的构建缓存。

### MySQL 不健康

```bash
docker compose --env-file .env logs --tail=300 mysql
docker compose --env-file .env ps mysql
```

首次初始化失败时不要直接删除 `zhihui_mysql_data`。先保存日志并确认是否存在磁盘不足、密码格式、SQL 初始化或文件系统问题。

### 页面返回 502

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 frontend gateway
docker compose --env-file .env logs --tail=200 user-service knowledge-service community-service message-service ai
```

通常是网关或某个依赖服务尚未健康。

### 页面可以打开但接口失败

```bash
curl -i http://127.0.0.1:${HTTP_PORT:-8088}/api/gateway/status
curl -i http://127.0.0.1:${HTTP_PORT:-8088}/api/user/health
```

修改 `.env` 后必须执行 `./deploy.sh update`，仅重启容器可能不会应用 Compose 配置变化。

### 服务器内存不足

```bash
free -h
docker stats --no-stream
dmesg -T | grep -i -E 'out of memory|killed process' | tail -n 30
```

确认已配置交换空间，并停止服务器上无关的数据库、中间件或开发服务。不要在 4 GB 服务器上同时运行完整版 Nacos、MinIO 等组件。

### 磁盘空间不足

```bash
df -h
docker system df
```

可以清理未使用的构建缓存和悬空镜像：

```bash
docker builder prune
docker image prune
```

执行清理前应先确认没有仍需回退的旧镜像。不要删除 `zhihui_mysql_data` 和 `zhihui_app_data`。

## 21. 部署完成检查表

- [ ] 当前 Git 分支为 `docker-linux`
- [ ] `.env` 已替换全部占位值且权限为 `600`
- [ ] Docker 和 Compose 版本检查通过
- [ ] 服务器存在可用交换空间
- [ ] 只开放 SSH、HTTP、HTTPS
- [ ] `docker compose config --quiet` 通过
- [ ] `./deploy.sh` 成功结束
- [ ] 所有容器处于 `running/healthy`
- [ ] 前端和七个健康接口可访问
- [ ] 知识上传、图片、社区、消息和 AI 功能通过人工验收
- [ ] 域名和 HTTPS 已配置
- [ ] 备份脚本和 SHA-256 校验通过
- [ ] 已配置异机或远程备份
