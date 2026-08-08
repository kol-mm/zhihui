# AI 知识社区平台（完整版）

面向知识沉淀、社区交流、内容治理和 AI 知识问答的一体化平台。当前 `master` 分支保留完整的多服务架构，可在 Windows 本地运行，也可通过 Nginx 部署到单台服务器；容器化不在当前范围内。

## 核心能力

### 用户端

- 注册、登录、图形验证码、JWT 鉴权和个人资料维护
- 知识上传、分类、全文检索、阅读、下载、点赞、收藏和举报
- 社区发帖、草稿、多图内容、评论、二级回复、点赞和收藏
- 关注、取消关注、拉黑、私信、通知和行为足迹
- 用户反馈、FAQ 和工单进度查询
- 基于已审核知识的 AI 问答、历史会话和参考资料

### 管理端

- 用户、知识、帖子、评论、举报和工单管理
- 待审核内容预览、通过、驳回和深度内容检索
- 平台数据统计、业务事件和服务运行模式展示
- AI 提供商、请求地址、模型、生成温度、知识范围和合规规则配置
- FAQ、通知开关、社区开关和上传限制配置

### 平台能力

- Nacos 服务注册与发现
- MinIO 图片和知识附件存储
- MySQL 业务数据持久化
- 本地或 Redis 网关限流
- 本地或 Elasticsearch 全文检索
- 本地或 RabbitMQ 事件处理
- 本地、Milvus 或 ChromaDB 向量检索配置入口
- JWT、角色权限、输入校验、上传限制和通用错误脱敏

4 GB 单服务器轻量版位于 `feature/4g-lightweight` 分支，该分支不运行 Nacos 和 MinIO。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 网关 | Spring Cloud Gateway |
| 后端 | Java 17、Spring Boot 3.2、Spring Cloud、MyBatis-Plus |
| 服务发现 | Nacos 2.x |
| AI | Python、FastAPI、Uvicorn、SQLite |
| 数据存储 | MySQL 8、MinIO |
| 可选组件 | Redis、RabbitMQ、Elasticsearch、Milvus、ChromaDB |
| 生产入口 | Nginx |

## 项目结构

```text
项目/
├─ frontend/                 Vue 用户端和管理端
├─ backend/
│  ├─ gateway/              统一网关和路由
│  ├─ user-service/         用户与社交关系
│  ├─ knowledge-service/    知识、审核、检索与文件
│  ├─ community-service/    社区、评论、草稿与互动
│  ├─ message-service/      消息、通知、反馈与事件
│  └─ common/               公共鉴权和响应模型
├─ ai-service/              AI 问答与向量检索
├─ data/                    本地运行数据（不提交到 Git）
├─ deploy/                  Nginx 配置模板
├─ docs/                    SQL、部署和验收文档
└─ *.ps1 / *.bat            启动、停止、验收和备份脚本
```

## 环境要求

- Windows PowerShell 5.1 或 PowerShell 7
- JDK 17
- Maven 3.6+
- Node.js 20+
- Python 3.11+
- Nacos 2.x
- MinIO
- MySQL 8（长期运行必须，本地体验可选）
- Nginx（仅生产部署需要）

Python 依赖由脚本安装到 `ai-service/.venv`，不会写入全局 Python 环境。

## 快速开始

### 1. 准备 Nacos

设置 Nacos 目录后启动：

```powershell
$env:NACOS_HOME = "D:\software\nacos-server-2.3.2\nacos"
.\start-nacos.bat
```

也可以在 Nacos 的 `bin` 目录执行：

```powershell
.\startup.cmd -m standalone
```

### 2. 启动完整本地环境

双击：

```text
start-local.bat
```

或在 PowerShell 中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1
```

启动器会构建并启动 MinIO、AI 服务、四个业务服务、网关和前端，健康检查及接口验收通过后才返回成功。

后续启动可跳过完整构建：

```powershell
.\restart-local.ps1 -SkipBuild
```

访问地址：

- 前端：<http://127.0.0.1:5173>
- 网关：<http://127.0.0.1:8080>
- Nacos：<http://127.0.0.1:8848/nacos>
- MinIO API：<http://127.0.0.1:9000>
- MinIO 控制台：<http://127.0.0.1:9001>
- AI 健康检查：<http://127.0.0.1:8200/ai/health>

## 使用外部 MinIO

如果 MinIO 已经由你手动启动在 `9000` 端口，使用外部服务模式。启动器只复用该服务，不会停止它：

```powershell
$env:MINIO_ACCESS_KEY = "你的 MinIO 账号"
$env:MINIO_SECRET_KEY = "你的 MinIO 密码"
.\start-local.ps1 -UseExternalMinio
```

完全不使用 MinIO、改用本地文件目录时：

```powershell
.\start-local.ps1 -SkipMinio
```

`-SkipMinio` 和 `-UseExternalMinio` 不能同时使用。

## MySQL 长期运行模式

默认本地体验模式允许使用本地数据实现。准备长期运行或多用户使用时必须切换到 MySQL：

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\verify-mysql.ps1
.\restart-local.ps1 -SkipBuild
```

`verify-mysql.ps1` 会幂等创建所需数据库和表，不会删除已有业务数据。

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
| Nacos | 8848 |
| MinIO API / 控制台 | 9000 / 9001 |
| MySQL | 3306 |

## 常用启动参数

```powershell
.\start-local.ps1 -SkipBuild
.\start-local.ps1 -SkipNacos
.\start-local.ps1 -SkipMinio
.\start-local.ps1 -UseExternalMinio
.\start-local.ps1 -SkipFrontend
.\start-local.ps1 -SkipSmokeTest
```

停止脚本只会结束 `logs/local-processes.json` 中登记且启动时间匹配的项目进程：

```powershell
.\stop-local.ps1
```

## 验收与测试

接口冒烟测试支持遇到 503 自动重试：

```powershell
.\smoke-test.ps1 -MaxRetries 30 -RetryWaitSeconds 10
```

MySQL 模式检查：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\verify-mysql.ps1
```

生产配置只读验收：

```powershell
.\verify-production.ps1
```

完整测试：

```powershell
cd .\backend
mvn.cmd -s maven-settings.xml clean test

cd ..\ai-service
.\.venv\Scripts\python.exe -m unittest discover -s tests

cd ..\frontend
npm.cmd run build
```

## 生产部署

生产前端默认使用同源 `/api`，由 Nginx 反向代理到 `127.0.0.1:8080`：

```powershell
cd .\frontend
npm.cmd ci
npm.cmd run build
```

Nginx 模板位于 `deploy/nginx/ai-knowledge.conf`。修改域名、证书和 `frontend/dist` 路径后，先执行 `nginx -t` 再加载配置。

长期运行必须通过服务器环境变量提供以下配置：

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:MYSQL_PASSWORD = "强数据库密码"
$env:MINIO_ACCESS_KEY = "MinIO 账号"
$env:MINIO_SECRET_KEY = "MinIO 强密码"
$env:AI_KNOWLEDGE_JWT_SECRET = "长度足够的随机密钥"
$env:AI_API_KEY = "外部 AI 服务密钥"
```

详细流程见 [docs/server-deployment.md](docs/server-deployment.md)。

## 数据与备份

生产备份至少应包含：

- MySQL 业务数据库
- MinIO 应用存储桶
- `ai-service/data/ai_service.db`
- `.local-secrets/jwt-secret.txt`

配置好 MinIO Client 的别名后执行：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\backup-production.ps1 -BackupRoot "E:\ai-knowledge-backups"
```

备份应保存到另一块磁盘或远程存储，并定期执行恢复演练。

## 安全建议

- 不要把密码、JWT 密钥、API Key、数据库备份或上传文件提交到 Git。
- 正式环境必须修改初始化账号密码和 MinIO 默认凭据。
- 对外访问统一经过启用 HTTPS 的 Nginx，不直接暴露业务服务端口。
- MySQL、Nacos、MinIO 和内部服务仅监听本机或可信内网。
- 根据并发量启用 Redis 限流，并在入口层增加连接数、请求体大小和超时限制。

## 日志与故障排查

运行日志位于 `logs/`：

```text
logs/<service>.log
logs/<service>.error.log
logs/local-processes.json
```

启动失败时先查看对应的 `*.error.log`，确认 `3306`、`8080`、`8101-8104`、`8200`、`8848` 和 `9000` 端口是否被其他程序占用，然后运行：

```powershell
.\stop-local.ps1
.\restart-local.ps1 -SkipBuild
```

## 相关文档

- [本地验收清单](docs/local-acceptance-checklist.md)
- [非容器服务器部署](docs/server-deployment.md)
- [数据库初始化脚本](docs/sql/schema.sql)

## 当前边界

- 当前版本不包含 Docker、Docker Compose 或 Kubernetes。
- 默认本地能力适合开发和演示；长期多用户运行必须使用 MySQL 和可靠备份。
- Redis、RabbitMQ、Elasticsearch、Milvus/ChromaDB 为可选增强能力，不是本地启动的必需条件。
