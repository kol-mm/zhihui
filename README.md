# AI 知识社区平台（4 GB 单机版）

面向知识沉淀、社区交流和 AI 知识问答的一体化平台。当前分支为 `feature/4g-lightweight`，在保留完整业务功能的前提下，针对单台 4 GB 服务器减少基础设施占用；容器化不在当前范围内。

## 版本特点

- 保留用户端、管理端、知识审核、社区互动、消息反馈和 AI 问答功能。
- 业务数据使用 MySQL 8 持久化。
- 服务间使用固定地址直连，不运行 Nacos。
- 图片和知识附件存入服务器本地目录，不运行 MinIO。
- 网关使用本地限流，不依赖 Redis。
- 全文检索、事件总线和向量检索使用本地实现。
- AI 服务使用 SQLite、本地向量索引和单 Worker。
- Python 依赖只安装在 `ai-service/.venv`。
- 生产前端由 Nginx 托管，后端统一通过 `/api` 访问。

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

## 环境要求

- Windows PowerShell 5.1 或 PowerShell 7
- JDK 17
- Maven 3.6+
- Node.js 20+
- Python 3.11+
- MySQL 8
- Nginx（仅服务器生产部署需要）

无需安装 Nacos、MinIO、Redis、RabbitMQ、Elasticsearch 或 Milvus。

## 快速开始

在项目根目录打开 PowerShell，设置 MySQL 密码：

```powershell
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
```

首次运行先初始化并检查数据库：

```powershell
.\verify-mysql.ps1
```

本机开发时启动全部服务和 Vite 前端：

```powershell
.\start-lightweight.ps1 -WithDevFrontend
```

后续无需重新构建时：

```powershell
.\restart-lightweight.ps1 -WithDevFrontend -SkipBuild
```

访问地址：

- 前端：<http://127.0.0.1:5173>
- 网关：<http://127.0.0.1:8080>
- AI 健康检查：<http://127.0.0.1:8200/ai/health>

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

## 生产部署

生产环境先构建前端，然后由 Nginx 托管 `frontend/dist`：

```powershell
cd .\frontend
npm.cmd ci
npm.cmd run build
```

Nginx 模板位于 `deploy/nginx/ai-knowledge-4g.conf`。修改其中的站点根目录后执行 `nginx -t`，再加载配置。启动业务服务时默认不启动开发前端：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\start-lightweight.ps1
```

4 GB 参数、MySQL 配置和详细部署说明见 [docs/4g-single-server.md](docs/4g-single-server.md)。

## 数据目录与备份

需要同时备份 MySQL、AI SQLite、JWT 密钥和以下本地文件：

- `data/uploads`
- `data/knowledge-media`
- `data/community-media`
- `data/user-avatars`
- `ai-service/data/ai_service.db`
- `.local-secrets/jwt-secret.txt`

创建备份：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\backup-lightweight.ps1 -BackupRoot "E:\ai-knowledge-backups"
```

备份应保存到另一块磁盘或远程存储，不能只留在应用服务器。

## 安全配置

正式部署至少应通过服务器环境变量配置：

```powershell
$env:MYSQL_PASSWORD = "强数据库密码"
$env:AI_KNOWLEDGE_JWT_SECRET = "长度足够的随机密钥"
$env:AI_API_KEY = "外部 AI 服务密钥"
```

不要把密码、JWT 密钥、API Key、数据库备份或上传文件提交到 Git。对外服务应通过 Nginx 启用 HTTPS，数据库和内部服务端口只监听内网或本机。

## 常用命令

```powershell
# 停止项目登记的本地进程
.\stop-local.ps1 -KeepNacos

# 重启轻量化服务
.\restart-lightweight.ps1 -WithDevFrontend -SkipBuild

# 查看验收结果
.\verify-lightweight.ps1

# 查看日志
Get-ChildItem .\logs
```

## 相关文档

- [4 GB 单机部署](docs/4g-single-server.md)
- [本地验收清单](docs/local-acceptance-checklist.md)
- [非容器服务器部署](docs/server-deployment.md)

## 当前边界

- 当前版本不包含 Docker、Docker Compose 或 Kubernetes。
- 4 GB 模式针对中低并发单机使用；并发量明显增加时应升级内存或拆分服务。
- 本地文件模式不提供对象存储的副本和版本能力，必须建立独立备份。
