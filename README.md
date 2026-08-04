# AI 知识社区平台（本地可用版本）

本项目先完成本地可运行版本，暂不做容器化。当前架构保留微服务拆分，并接入本机 Nacos；Python 依赖只安装在 ai-service/.venv，不污染全局环境。

## 当前覆盖范围

- 用户端六大模块：知识库、论坛、消息、广场、个人中心、用户反馈。
- 管理端六大模块：知识库管理、论坛管理、消息互动管理、平台数据统计、用户账号管理、工单反馈管理。
- 后端：Spring Cloud Gateway、Nacos Discovery、用户服务、知识库服务、论坛/广场服务、消息/反馈服务。
- AI 服务：FastAPI，本地 SQLite 持久化知识切片、向量、会话和聊天消息。
- 前端：Vue 3 + TypeScript + Vite + Element Plus，本地页面已拆分用户端 / 管理端两个入口，并可分别联调接口。
- 本地基础能力：文件存储、事件总线、全文索引、向量检索均可直接运行，并保留 MinIO、RabbitMQ、Elasticsearch、Milvus/ChromaDB 配置入口。
- 鉴权：登录签发 HMAC-SHA256 JWT，包含用户、角色、签发时间和过期时间；管理端接口校验 ADMIN 角色。
- 完整交互：粉丝/拉黑/用户举报/行为足迹、多图帖子/关注动态/帖子点赞、单条消息删除和会话清理。
- 管理闭环：知识举报结案、评论和草稿清理、FAQ 运维、AI 数据源与匹配规则配置、问答历史和切片审查。

## 本机依赖

- JDK 17
- Maven 3.6+
- Node.js 20+（PowerShell 下建议使用 npm.cmd）
- Python 3.11+（当前代码已在 Python 3.14 环境下验证）
- Nacos 2.x 单机版
- MySQL 8 可选；默认本地可用版本使用本地 JSON/内存数据和 AI SQLite

## 启动 Nacos

在 Nacos 目录执行：

    startup.cmd -m standalone

默认控制台：

    http://127.0.0.1:8848/nacos

默认账号密码通常是 nacos / nacos。本项目暂不启动容器版 Nacos。

## 一键启动本地可用版本

项目根目录提供 start-local.ps1，会启动 AI 服务、后端 5 个微服务、网关和前端，并把日志写入 logs 目录。

Windows 下如果想双击启动，请使用：

    start-local.bat

如果只想单独启动 Nacos，可以双击：

    start-nacos.bat

如果在 PowerShell 中启动，请使用：

    .\start-local.ps1

如果你的 Nacos 已经启动，脚本会检测 8848 端口并跳过。本机已检测到的默认 Nacos 目录是：

    D:\software\nacos-server-2.3.2\nacos

若你换了其他安装目录，请先设置 NACOS_HOME：

    $env:NACOS_HOME = "D:\software\nacos-server-2.3.2\nacos"
    .\start-local.ps1

常用参数：

    .\start-local.ps1 -SkipBuild
    .\start-local.ps1 -SkipNacos
    .\start-local.ps1 -SkipFrontend

## 启动后端

后端使用 backend/maven-settings.xml，本地 Maven 仓库固定到英文路径，避免 Windows 中文路径下 Java 读取 jar 的权限问题。

    cd .\backend
    mvn -s maven-settings.xml clean install -DskipTests

建议分别打开 5 个终端启动服务：

    mvn -s maven-settings.xml -pl user-service spring-boot:run
    mvn -s maven-settings.xml -pl knowledge-service spring-boot:run
    mvn -s maven-settings.xml -pl community-service spring-boot:run
    mvn -s maven-settings.xml -pl message-service spring-boot:run
    mvn -s maven-settings.xml -pl gateway spring-boot:run

默认端口：

- Gateway: 8080
- user-service: 8101
- knowledge-service: 8102
- community-service: 8103
- message-service: 8104
- ai-service: 8200
- frontend: 5173

## 启动 AI 服务

    cd .\ai-service
    .\run.ps1

run.ps1 会自动创建并使用 ai-service/.venv，依赖不会安装到全局 Python。默认 SQLite 文件为 ai-service/data/ai_service.db。

可用 AI_DB_PATH 指定其他 SQLite 路径。

## 启动前端

    cd .\frontend
    npm.cmd install
    npm.cmd run dev

访问：

    http://127.0.0.1:5173

页面默认进入用户端，可切换到管理端。用户端和管理端使用同一个本地前端工程，避免本地运行阶段维护两套启动命令。

## 默认账号

- 普通用户：demo / demo
- 管理员：admin / admin123

管理员登录后可进入管理端，执行知识审核、帖子审核、用户状态调整、工单回复，并查看平台数据统计。

## 快速验证

后端测试：

    cd .\backend
    mvn -s maven-settings.xml clean test

AI 测试：

    cd .\ai-service
    .\.venv\Scripts\python.exe -m unittest discover -s tests

前端构建：

    cd .\frontend
    npm.cmd run build

接口 smoke test：

    .\smoke-test.ps1

带 503 自动重试的接口循环检测：

    .\smoke-test.ps1 -MaxRetries 30 -RetryWaitSeconds 10

也可以手动检查：

    curl http://127.0.0.1:8080/user/health
    curl http://127.0.0.1:8080/knowledge/list
    curl http://127.0.0.1:8080/square/feed
    curl http://127.0.0.1:8080/feedback/faqs
    curl http://127.0.0.1:8200/ai/health

## 可选 MySQL 模式

默认本地可用版本使用本地 JSON/内存数据。需要切换 MySQL 时，先执行：

    mysql -uroot -p < .\docs\sql\schema.sql

再设置环境变量启动服务：

    $env:SPRING_PROFILES_ACTIVE = "mysql"
    $env:MYSQL_USERNAME = "root"
    $env:MYSQL_PASSWORD = "你的本地 MySQL 密码"
    mvn -s maven-settings.xml -pl user-service spring-boot:run

密码只通过环境变量传入，不写进项目配置。也可以通过 MYSQL_URL 分别指定对应业务库。

## 当前完成边界

- 容器化部署：按要求暂不做。
- 文件存储、消息事件、全文检索、向量检索、JWT/RBAC：已完成本地可运行实现。
- MinIO、RabbitMQ、Elasticsearch、Milvus/ChromaDB：已保留配置入口；默认不依赖外部服务即可启动。

## 可选能力配置

    $env:AI_KNOWLEDGE_JWT_SECRET = "请替换为长随机字符串"
    $env:AI_KNOWLEDGE_JWT_EXPIRES_SECONDS = "28800"
    $env:AI_VECTOR_MODE = "local"
    $env:AI_VECTOR_DIMENSION = "128"
    $env:MILVUS_ENDPOINT = "http://127.0.0.1:19530"
    $env:CHROMA_PATH = ".\\ai-service\\data\\chroma"

本地配置文件还提供 `knowledge.storage.minio`、`message.event.rabbitmq` 和 `knowledge.search.elasticsearch` 节点。默认 `mode: local`，因此不安装外部中间件也能使用对应功能。
