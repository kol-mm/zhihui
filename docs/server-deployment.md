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

AI review and AI 检索 (chat) can use different models, set in AI 与系统 by the super administrator: 审核模型服务 and
审核模型 under AI 内容审核. Both empty — the default — means review uses exactly what chat uses. Review can name
its own model under chat's provider (a faster, cheaper one suits its short yes-or-no decisions), use the other
provider, or use the local rules only. The endpoint address and the key belong to the provider and are shared by
both: giving review its own address under the same provider would send that provider's key to a server it was not
issued for. When review uses an OpenAI-compatible service that chat does not, the address fields must still be
filled in, and the review model named; the settings are refused otherwise. Start-up discovery (below) checks the
review model as well.

For an OpenAI-compatible service, ai-service asks `{base_url}/models` once at start-up (three-second timeout, in the
background) which models the service offers, and checks the model set in AI 与系统 against the list. The configured
model is always the one called: if the service does not list it, a warning is logged and nothing is switched. If the
list cannot be had — timeout, HTTP error, no such endpoint, an empty list — a warning says that instead. The outcome,
the models found and the model in use are written to the ai service's log
(`docker compose logs ai | grep model_discovery`). The list is not fetched again: after the upstream adds or
withdraws models, or after `base_url` is changed in the settings, restart the ai service
(`docker compose restart ai`) to check again. Set `AI_MODEL_DISCOVERY_ENABLED=false` to skip the check entirely.

Models whose name begins with `qwen3` (also after an organisation prefix, as in `Qwen/Qwen3-32B`) are sent
`"enable_thinking": false`, which keeps review within its time limit. No other model receives the parameter; the
OpenAI API rejects it.

AI review shows the model at most the first 4,000 characters of a body. Longer content is never published on the
model's approval alone: it goes to a person with the note 内容较长，AI 只审阅了开头部分. A rejection is acted on as
usual, and the sensitive-word rules always read the whole text.

### Semantic retrieval

AI 问答 answers from knowledge chunks: an approved document's text, cleaned line by line (lines the
sensitive-content rules catch are dropped) and packed into chunks of up to 500 characters. Only administrators
index — the admin page does it when a document is approved — since whatever is indexed is quoted to every member.

Without an embedding model, chunks are matched by the words they share with the question. Set 向量模型 under
AI 检索配置 (super administrator only) to an OpenAI-compatible embedding model — for example `text-embedding-v3`
on DashScope — and fill in the compatible base address: ai-service then calls `{base_url}/embeddings` with the
OpenAI-compatible key, and a question also finds passages that say the same thing in other words. Documents' text
is sent to that provider to be embedded, as questions and passages already are when chat uses a model.

Indexing never waits for the model. New chunks are stored at once and a background worker fetches their vectors
ten at a time; the settings page shows how many are done, and until a chunk has its vector it is matched by words.
Changing the embedding model re-embeds every chunk by itself, and vectors from different models are never
compared. If the model cannot be reached, the worker retries with growing pauses (up to ten minutes) and retrieval
falls back to words; nothing fails for the member asking.

Retrieval compares the question with every chunk in ai-service's own database. Measured inside the ai service's
256 MB limit, a question takes about 0.2 s at 1,000 chunks and 1.5–2 s at 5,000 with 1,024-dimension vectors. Well
beyond that — tens of thousands of chunks — a dedicated vector store (ChromaDB, per the technology plan) is the next
step.

### Document summaries and categories

With AI 摘要与自动分类 turned on (AI 与系统 → AI 检索配置; off by default), each uploaded document's text is
sent — in the background, after the upload has been answered — to the chat model set in AI 检索配置, which writes a
short summary and picks one of the existing categories. The category is applied only when the uploader chose
none; a category already chosen is never replaced, and the suggestion is shown to reviewers in 内容审核 instead.
Reviewers see the summary next to the document before approving it; members see it on the document's page. A
document can try to steer the model, so what comes back is checked rather than trusted: a category not on the list
is dropped, the summary is cut to 200 characters, and a summary the sensitive-content rules catch is dropped.

The model reads the first 6,000 characters. Nothing happens without a configured model, and a document uploaded
before the switch was turned on can be analysed from 内容审核 with 生成摘要. At most 200 documents wait in the
queue; beyond that, new uploads are skipped and can be analysed the same way later.

## Administrator password recovery

Members who forget their password ask for a reset on the login page, and an administrator issues them a one-time
code. The code is shown to whoever issues it and sets a new password, so issuing one hands the account over.
Codes for an administrator's account can therefore be issued only by the super administrator, no administrator can
issue one for their own account, and the super administrator's own password cannot be reset in the app at all.

To set an administrator's password — the super administrator's included — run this on the server, from the
directory with `compose.yaml`:

```bash
docker compose run --rm --no-deps user-service reset-admin-password admin
```

It asks for the new password twice without showing it, applies the usual password rules, ends every session the
account had, and records the change in the admin action log as 在服务器上重置管理员密码. The password is never
accepted on the command line, where it would stay in shell history; for a script, pass it as the first line of
standard input with `docker compose run -T`. Take a backup first (`./backup.sh`). If the account was locked out by
failed sign-ins, `docker compose restart user-service` lifts the lock. The command starts a second copy of
user-service for a few seconds, so on a small host run it when the site is quiet.

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
