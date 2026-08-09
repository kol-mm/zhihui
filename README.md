# AI 知识社区平台（Docker Linux 版）

面向知识沉淀、社区交流和 AI 知识问答的一体化平台。当前 `docker-linux` 分支基于 4 GB 轻量版，提供 Docker Compose 和 Linux 一键部署脚本；不运行 Nacos、MinIO、Redis、RabbitMQ、Elasticsearch 或 Milvus。

## 版本特点

- 保留用户端、管理端、知识审核、社区互动、消息反馈和 AI 问答功能。
- 业务数据使用 MySQL 8 持久化。
- 服务间使用固定地址直连，不运行 Nacos。
- 图片和知识附件存入服务器本地目录，不运行 MinIO。
- 网关使用本地限流，不依赖 Redis。
- 全文检索、事件总线和向量检索使用本地实现。
- AI 服务使用 SQLite、本地向量索引和单 Worker。
- Java、Python、Node 构建环境封装在多阶段镜像中。
- 生产前端由 Nginx 容器托管，后端统一通过 `/api` 访问。
- MySQL、上传文件和 AI SQLite 使用 Docker 持久卷。

完整版（Nacos + MinIO）位于 `master` 分支。

## 功能概览

### 用户端

- 注册、登录、图形验证码、JWT 鉴权和个人资料维护
- 知识上传、分类、检索、阅读、下载、点赞、收藏和举报
- 社区发帖、草稿、多图内容、评论、二级回复、点赞和收藏
- 关注、取消关注、拉黑、私信、通知和行为足迹
- 用户反馈、FAQ 和工单进度查询
- 基于已审核知识的 AI 问答、历史会话和参考资料

### 管理端

- 用户、知识、帖子、评论、举报和工单管理
- 待审核内容预览、通过、驳回和深度内容检索
- 平台统计、业务事件、运行模式和服务状态展示
- AI 提供商、请求地址、模型、温度、知识范围和合规规则配置
- FAQ、通知开关、社区开关和上传限制配置

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 网关 | Spring Cloud Gateway |
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus |
| AI | Python、FastAPI、Uvicorn、SQLite |
| 数据库 | MySQL 8 |
| 入口 | Nginx |
| 本地能力 | 本地文件、全文索引、事件总线、向量检索 |

## 项目结构

```text
项目/
├─ frontend/                 Vue 用户端和管理端
├─ backend/
│  ├─ gateway/              统一网关
│  ├─ user-service/         用户与社交关系
│  ├─ knowledge-service/    知识、审核、检索与文件
│  ├─ community-service/    社区、评论、草稿与互动
│  ├─ message-service/      消息、通知、反馈与事件
│  └─ common/               公共鉴权和响应模型
├─ ai-service/              AI 问答与向量检索
├─ data/                    本地上传文件（不提交到 Git）
├─ deploy/                  Nginx 与 MySQL 配置模板
├─ docs/                    部署及验收文档
└─ *.ps1                    启动、停止、验收和备份脚本
```

## Linux 环境要求

- Ubuntu 22.04/24.04、Debian 12 或其他现代 Linux 发行版
- 2 核 CPU、4 GB 内存、20 GB 以上可用磁盘
- Docker Engine 24+
- Docker Compose v2（使用 `docker compose` 命令）
- `curl`、Git 和可访问的软件镜像仓库

宿主机不需要安装 Java、Maven、Node.js、Python、MySQL 或 Nginx。

## 一键部署

```bash
git clone https://github.com/kol-mm/zhihui.git
cd zhihui
git switch docker-linux

cp .env.example .env
nano .env
chmod +x deploy.sh backup.sh restore.sh
./deploy.sh
```

必须在 `.env` 中更换数据库密码、JWT 密钥和内部通知令牌。部署脚本会构建镜像、启动服务并循环检查前端、用户服务和 AI 接口，全部成功后输出访问地址。

默认访问地址为 `http://服务器IP`。端口被占用时可在 `.env` 中修改 `HTTP_PORT`。

## 默认测试账号

仅用于本地初始化和验收，正式部署后应立即修改密码：

- 普通用户：`demo / demo`
- 管理员：`admin / admin123`

## 服务端口

| 服务 | 端口 |
| --- | ---: |
| 前端开发服务器 | 5173 |
| 网关 | 8080 |
| 用户服务 | 8101 |
| 知识服务 | 8102 |
| 社区服务 | 8103 |
| 消息服务 | 8104 |
| AI 服务 | 8200 |
| MySQL | 3306 |

## 验收与测试

查看容器状态及接口：

```bash
./deploy.sh status
curl -fsS http://127.0.0.1/healthz
curl -fsS http://127.0.0.1/api/user/health
curl -fsS http://127.0.0.1/api/ai/health
```

以下命令用于不经过容器的 Windows 源码开发验收：

检查轻量化模式是否真正生效：

```powershell
.\verify-lightweight.ps1
```

运行接口冒烟测试；遇到 503 时每 10 秒重试，最多 30 轮：

```powershell
.\smoke-test.ps1 -MaxRetries 30 -RetryWaitSeconds 10
```

运行完整测试：

```powershell
cd .\backend
mvn.cmd -s maven-settings.xml clean test

cd ..\ai-service
.\.venv\Scripts\python.exe -m unittest discover -s tests

cd ..\frontend
npm.cmd run build
```

## 更新与运维

```bash
git pull --ff-only
./deploy.sh update

# 强制拉取并刷新基础镜像
./deploy.sh refresh

./deploy.sh restart
./deploy.sh status
./deploy.sh logs
./deploy.sh stop
./deploy.sh start
```

`down` 只删除容器和网络，不删除持久卷：

```bash
./deploy.sh down
```

详细说明见 [docs/docker-linux.md](docs/docker-linux.md)。

## 数据目录与备份

容器版使用 `zhihui_mysql_data` 和 `zhihui_app_data` 两个持久卷。创建一致备份：

```bash
BACKUP_ROOT=/mnt/backup/zhihui ./backup.sh
```

恢复会覆盖当前数据库和应用文件，必须显式确认：

```bash
RESTORE_CONFIRM=YES ./restore.sh /mnt/backup/zhihui/20260808-120000
```

备份应保存到另一块磁盘或远程存储，不能只留在应用服务器。

## 安全配置

正式部署必须在不提交到 Git 的 `.env` 中配置强密码和随机密钥；可选外部 AI 密钥也只放在该文件中。

不要把密码、JWT 密钥、API Key、数据库备份或上传文件提交到 Git。对外服务应通过 Nginx 启用 HTTPS，数据库和内部服务端口只监听内网或本机。

## 常用命令

```bash
./deploy.sh status
./deploy.sh logs
docker compose --env-file .env exec mysql mysql -uzhihui -p
docker compose --env-file .env config
```

## 相关文档

- [4 GB 单机部署](docs/4g-single-server.md)
- [Docker Linux 一键部署](docs/docker-linux.md)
- [本地验收清单](docs/local-acceptance-checklist.md)
- [非容器服务器部署](docs/server-deployment.md)

## 当前边界

- 当前版本包含 Docker Compose，不包含 Kubernetes 或集群编排。
- 4 GB 容器模式针对中低并发单机使用；并发量明显增加时应升级内存或拆分服务。
- 本地文件模式不提供对象存储的副本和版本能力，必须建立独立备份。
