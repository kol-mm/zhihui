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

## Model provider keys

Super administrators can store the keys ai-service uses to call its model provider (Claude, or an
OpenAI-compatible service) from the admin panel, under 模型密钥. They are encrypted at rest with a key derived from
`AI_KEY_ENCRYPTION_KEY`, which must be set in the ai service's environment to a long random value — generate one
the same way as the other secrets. Without it, storing keys from the panel is refused (outside development) and
the environment variables below remain the only way to supply a key.

Treat `AI_KEY_ENCRYPTION_KEY` like the database password: back it up with the other secrets and do not change it.
Keys stored under one value cannot be read under another; ai-service then reports the active key as unreadable and
stops calling the model — chat falls back to local retrieval and content review to a person — until the keys are
entered again.

A provider with no active stored key uses its environment variable: `ANTHROPIC_API_KEY` for Claude, `AI_API_KEY`
for an OpenAI-compatible service. Claude is always called at Anthropic's own API; if the server needs a proxy to
reach it, set `ANTHROPIC_BASE_URL`, which the Anthropic SDK reads.

## Narrowing the application database account

The application account is granted only the four databases a service actually connects to: `user_db`,
`knowledge_db`, `community_db` and `message_db`. `ai_db`, `audit_db` and `statistics_db` were granted in
earlier deployments but no service has ever connected to them — the AI service keeps its own SQLite file, and
the audit log lives in `user_db`.

A deployment created before this change keeps the old grants until they are revoked by hand. Check what the
account currently holds, as root:

```sql
SHOW GRANTS FOR 'zhihui'@'%';
```

If `ai_db`, `audit_db` or `statistics_db` appear, remove them:

```sql
REVOKE ALL PRIVILEGES ON ai_db.* FROM 'zhihui'@'%';
REVOKE ALL PRIVILEGES ON audit_db.* FROM 'zhihui'@'%';
REVOKE ALL PRIVILEGES ON statistics_db.* FROM 'zhihui'@'%';
FLUSH PRIVILEGES;
```

Substitute the account name in `MYSQL_USER` if it is not `zhihui`. Nothing needs restarting, and no service
loses access, because none of them opens those databases. Revoking a database that was never granted is an
error rather than a no-op, so run `SHOW GRANTS` first and revoke only what it lists.

## Reverse proxy and HTTPS

The production frontend uses the same-origin `/api` path by default. A ready-to-edit Nginx template is
available at `deploy/nginx/ai-knowledge.conf`. Replace the domain, certificate paths, and frontend `root`,
then validate the configuration with `nginx -t` before reloading Nginx.

For a different API origin, set `VITE_API_BASE_URL` before running the frontend production build.

After all services are running, execute `verify-production.ps1`. It performs read-only checks for required
secrets, infrastructure ports, gateway routes, AI health, and MinIO health. A non-zero exit code means the
server is not ready to receive production traffic.

Run `backup-production.ps1` on a schedule after configuring an `mc` alias for MinIO. It creates a timestamped
MySQL dump and mirrors the three application buckets. Use `-SkipMinio` only when object storage is backed up
by a separate system.
