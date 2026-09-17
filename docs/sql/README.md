# 数据库结构与迁移

各业务服务在启动时用 [Flyway](https://documentation.red-gate.com/fd) 创建并升级自己的数据库，不再需要手工执行 SQL。

| 服务 | 数据库 | 迁移脚本 |
| --- | --- | --- |
| user-service | `user_db` | `backend/user-service/src/main/resources/db/migration` |
| knowledge-service | `knowledge_db` | `backend/knowledge-service/src/main/resources/db/migration` |
| community-service | `community_db` | `backend/community-service/src/main/resources/db/migration`（另有 Java 迁移 `V2__Backfill_comment_roots`） |
| message-service | `message_db` | `backend/message-service/src/main/resources/db/migration` |

- `schema.sql` 只负责创建数据库，Docker 首次初始化 MySQL 时自动执行；表由服务启动时创建。
- 每个库的执行记录保存在该库的 `flyway_schema_history` 表中。
- 引入 Flyway 之前就存在的数据库会以版本 0 建立基线，随后执行 `V1__baseline`。V1 的每条语句都是幂等的，会把旧库补齐到与新库一致。
- 修改结构时，在对应服务下新增 `V<下一个版本>__<说明>.sql`，不要修改已经发布的脚本（Flyway 会校验校验和，改动后服务将拒绝启动）。
- 迁移测试：`backend/*/src/test/.../FlywayMigrationIT`，在设置 `MIGRATION_TEST_JDBC_URL` 等变量后运行，分别验证空库和旧版结构的升级。
- `legacy/` 保存引入 Flyway 之前手工执行的脚本，仅供查阅。
