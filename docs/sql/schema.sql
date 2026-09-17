-- Creates the databases. Each service creates and upgrades its own tables when it starts, using Flyway and the
-- versioned scripts under backend/<service>/src/main/resources/db/migration (see docs/sql/README.md).
CREATE DATABASE IF NOT EXISTS user_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS knowledge_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS community_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS message_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Reserved; no service stores data here yet (the AI service keeps its data in SQLite).
CREATE DATABASE IF NOT EXISTS ai_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS audit_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS statistics_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
