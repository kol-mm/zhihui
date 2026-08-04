# Server Deployment (without containers)

This project keeps the local multi-service layout. MinIO and Nacos are external processes.

## Required services

- Java 17 and Maven 3.6+
- Node.js 20+ for building the frontend
- Python 3.11+ with `ai-service/.venv`
- Nacos 2.x on port 8848
- MinIO on port 9000 (API) and 9001 (console)
- MySQL 8 for persistent business data
- Redis 6+ for the optional gateway rate limiter

## Build

```powershell
cd backend
mvn.cmd -s maven-settings.xml clean install -DskipTests
cd ..\frontend
npm.cmd ci
npm.cmd run build
```

## Start services

Start Nacos and MinIO manually first. Then start the AI service and the five Spring services.
For a server using Redis, start the gateway with the `redis` Spring profile:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local,redis"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
$env:GATEWAY_RATE_LIMIT_REPLENISH = "20"
$env:GATEWAY_RATE_LIMIT_BURST = "40"
mvn.cmd -s maven-settings.xml -pl gateway spring-boot:run
```

The limiter is disabled in the default local profile, so local development does not require Redis.
Rate-limit keys use the authenticated token hash, or the remote IP for unauthenticated requests.

Initialize and verify MySQL before starting the business services:

```powershell
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "your-password"
.\verify-mysql.ps1
```

The script is idempotent. It creates all databases and tables, preserves existing business data,
and ensures the default `demo` and `admin` accounts and basic lookup data exist.

## Production secrets

Set `AI_KNOWLEDGE_JWT_SECRET`, `MYSQL_PASSWORD`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, and
`AI_API_KEY` through the server environment. Do not store these values in the repository.
