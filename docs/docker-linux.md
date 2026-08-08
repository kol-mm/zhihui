# Docker Linux 一键部署

本文档适用于 `docker-linux` 分支。该方案为单台 4 GB Linux 服务器保留完整业务功能，使用 MySQL、本地文件、AI SQLite、本地检索和固定服务地址，不启动 Nacos 或 MinIO。

## 1. 服务器准备

推荐配置：

- Ubuntu 22.04/24.04 或 Debian 12
- 2 核 CPU
- 4 GB 内存
- 20 GB 以上 SSD
- Docker Engine 24+
- Docker Compose v2

确认环境：

```bash
docker version
docker compose version
curl --version
```

防火墙只需要开放 SSH、HTTP 和 HTTPS。`3306`、`8080`、`8101-8104`、`8200` 不应暴露到公网。

## 2. 获取项目

```bash
git clone https://github.com/kol-mm/zhihui.git
cd zhihui
git switch docker-linux
```

## 3. 配置密钥

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

必须修改：

- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `AI_KNOWLEDGE_JWT_SECRET`
- `INTERNAL_NOTIFICATION_TOKEN`

推荐使用密码管理器生成随机值。不要提交 `.env`。

## 4. 首次部署

```bash
chmod +x deploy.sh backup.sh restore.sh
./deploy.sh
```

脚本会执行 Compose 配置检查、镜像构建、容器启动和接口健康检查。首次构建需要下载 Maven、Node、Python 和 MySQL 镜像，耗时取决于网络速度。

查看状态：

```bash
./deploy.sh status
./deploy.sh logs
```

默认访问：

```text
http://服务器IP
```

## 5. 容器组成

| 服务 | 说明 | 对外端口 |
| --- | --- | --- |
| frontend | Vue 静态文件和 Nginx 反代 | `${HTTP_PORT}:80` |
| gateway | Spring Cloud Gateway | 无 |
| user-service | 用户与社交关系 | 无 |
| knowledge-service | 知识与本地文件 | 无 |
| community-service | 社区与互动 | 无 |
| message-service | 消息、通知和反馈 | 无 |
| ai | FastAPI、SQLite 和本地向量 | 无 |
| mysql | 业务数据库 | 无 |

Compose 只发布前端端口，其他服务只能通过内部 `app` 网络访问。

## 6. 持久化

Docker 创建两个命名卷：

- `zhihui_mysql_data`：MySQL 数据
- `zhihui_app_data`：上传文件、知识图片、社区图片、头像和 AI SQLite

执行 `docker compose down` 不会删除数据。不要使用 `docker compose down -v`，除非明确要永久删除全部数据。

## 7. 更新

```bash
git pull --ff-only
./deploy.sh update
```

更新会重新构建发生变化的镜像，并在原有持久卷上重建容器。

## 8. 备份与恢复

建议将备份目录放在独立磁盘或远程挂载点：

```bash
BACKUP_ROOT=/mnt/backup/zhihui ./backup.sh
```

备份脚本会短暂停止业务写入，生成 MySQL 全库备份、应用数据压缩包和 SHA-256 校验文件。

恢复前先保留一份当前备份。恢复指定快照：

```bash
RESTORE_CONFIRM=YES ./restore.sh /mnt/backup/zhihui/20260808-120000
```

## 9. HTTPS

当前容器默认提供 HTTP。正式公网部署应在服务器入口增加 HTTPS，可以使用云负载均衡、Caddy 或宿主机 Nginx，将请求转发到 `127.0.0.1:${HTTP_PORT}`。

启用 HTTPS 后，应只对公网开放 `443`，并将 `HTTP_PORT` 绑定到受信任接口或通过防火墙限制访问。

## 10. 故障排查

```bash
./deploy.sh status
./deploy.sh logs
docker compose --env-file .env logs --tail=200 mysql
docker compose --env-file .env logs --tail=200 gateway
```

常见原因：

- `.env` 仍包含占位密码
- `HTTP_PORT` 已被占用
- 服务器无法访问 Docker Hub、Maven、npm 或 PyPI
- 4 GB 服务器同时运行了其他高内存服务
- 磁盘空间不足导致 MySQL 或镜像构建失败

部署脚本在 360 秒内未通过接口检查时会自动打印容器状态和最近日志，并以非零状态退出。

