# 4 GB 单机部署

本分支面向一台 4 GB 服务器，保留完整业务功能，默认不启动 Nacos、MinIO、Redis、RabbitMQ、Elasticsearch 或 Milvus。

## 运行组成

- Nginx：托管 `frontend/dist` 并反向代理 `/api/` 到网关。
- Java 服务：仍按用户、知识、社区、消息、网关五个模块运行，使用直连地址，不依赖 Nacos。
- MySQL：保存四个业务库。
- AI 服务：使用项目的 `ai-service/.venv`、SQLite 和本地向量检索，单 Worker 运行。
- 本地文件目录：`data/uploads`、`data/knowledge-media`、`data/community-media`、`data/user-avatars`。

## 启动

在项目根目录打开 PowerShell，先设置数据库密码：

```powershell
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
.\start-lightweight.ps1
```

脚本默认不启动开发前端，生产环境由 Nginx 托管构建后的 `frontend/dist`。本地临时查看页面可以使用：

```powershell
.\start-lightweight.ps1 -WithDevFrontend
```

第一次启动可以省略 `-SkipBuild`，后续启动可使用：

```powershell
.\start-lightweight.ps1 -SkipBuild
```

## 内存策略

脚本为 Java 服务设置 384 MB 堆上限、128 MB 元空间上限，MySQL 连接池限制为 4 个连接，Tomcat 最大线程数为 40。AI 服务保持单 Worker。实际并发过高时应优先增加服务器内存，不要盲目放大这些上限。

## 文件备份

本地文件存储不会提供 MinIO 的对象版本能力，必须同时备份：

- MySQL 四个数据库
- `data/` 下的四个文件目录
- `ai-service/data/ai_service.db`
- `.local-secrets/jwt-secret.txt`

备份任务应写入另一块磁盘或远程存储，不能只保存在本机。
