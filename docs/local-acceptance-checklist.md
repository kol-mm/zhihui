# 本地可用版本验收清单

本清单用于每次启动后快速确认项目是否处于可演示、可操作状态。

## 1. 启动

- 执行 `start-nacos.bat` 或确认 `http://127.0.0.1:8848/nacos` 可访问。
- 执行 `start-local.bat` 或 `./start-local.ps1`。
- 打开 `http://127.0.0.1:5173`。

## 2. 默认账号

- 普通用户：`demo / demo`
- 管理员：`admin / admin123`

## 3. 接口自动检测

在项目根目录执行：

```powershell
./smoke-test.ps1 -MaxRetries 30 -RetryWaitSeconds 10
```

成功标准：`user-service`、`knowledge-list`、`square-feed`、`message-list`、`feedback-faq`、`ai-service` 均返回 `[OK]`，最终输出 `RESULT: SUCCESS`。

## 4. 用户端验收

- 注册或登录普通用户。
- 修改个人资料并查看用户信息。
- 提交知识资源，刷新知识列表后能看到新记录。
- 对知识资源执行阅读、下载、点赞、收藏和举报。
- 发布帖子，刷新广场后能看到帖子。
- 对帖子发表评论，查看详情和评论列表。
- 保存草稿并查看“我的草稿”。
- 关注/取消关注用户并查看关注列表。
- 发送私信、查看消息、清空会话。
- 查看通知列表。
- 提交反馈工单，刷新反馈后能看到工单。
- 发起 AI 问答并拿到本地 AI 服务响应。

## 5. 管理端验收

- 使用管理员账号登录并切换到管理端。
- 点击“平台概览”，确认管理概览和平台数据统计有数据。
- 点击“待处理数据”，确认知识、举报、工单队列可加载。
- 审核知识资源，刷新列表后状态变更。
- 审核帖子，刷新广场或队列后状态变更。
- 修改用户账号状态，刷新用户列表后状态变更。
- 回复工单，用户端刷新反馈后能看到回复。
- 查看平台数据统计中的注册用户、知识资源、社区帖子、反馈工单、消息互动、知识举报和待处理事项。

## 6. 构建与测试

```powershell
cd backend
mvn -s maven-settings.xml test

cd ../ai-service
.\.venv\Scripts\python.exe -m unittest discover -s tests

cd ../frontend
npm.cmd run build
```

## 7. 当前完成边界

- 容器化部署暂不做。
- 文件存储、消息事件、全文检索、向量检索、JWT/RBAC 纳入本地可用版本验收。
- 本地优先实现可运行能力，同时保留 MinIO、RabbitMQ、Elasticsearch、Milvus/ChromaDB 的后续接入点。
