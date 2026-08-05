from __future__ import annotations

import hashlib
import hmac
import base64
import json
import math
import os
import re
import sqlite3
import ipaddress
import socket
import urllib.error
import urllib.request
import urllib.parse
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field


APP_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = APP_DIR.parent / "data" / "ai_service.db"
VECTOR_DIMENSION = int(os.getenv("AI_VECTOR_DIMENSION", "128"))

class ApiResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: dict[str, Any]


class TextRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2_000_000)
    file_id: int | None = None
    title: str | None = None


class RebuildIndexRequest(BaseModel):
    documents: list[TextRequest] = Field(default_factory=list, max_length=1000)


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=4000)
    user_id: int | None = None
    session_id: int | None = None


class AiConfigRequest(BaseModel):
    data_source_scope: str = "all-approved"
    match_limit: int = Field(default=5, ge=1, le=20)
    compliance_rule: str = "answer-with-references"
    provider: str = Field(default="local", pattern="^(local|openai-compatible)$")
    model: str = Field(default="local-rag", max_length=200)
    base_url: str = Field(default="", max_length=2000)
    request_url: str = Field(default="", max_length=2000)
    temperature: float = Field(default=0.2, ge=0, le=1)
    max_upload_mb: int = Field(default=25, ge=1, le=200)
    notifications_enabled: bool = True
    community_enabled: bool = True


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def db_path() -> Path:
    configured = os.getenv("AI_DB_PATH")
    path = Path(configured) if configured else DEFAULT_DB_PATH
    return path.expanduser().resolve()


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    path = db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with connect() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS knowledge_chunk (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                embedding TEXT,
                created_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_file_id
                ON knowledge_chunk(file_id);

            CREATE TABLE IF NOT EXISTS ai_chat_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS ai_chat_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES ai_chat_session(id)
            );

            CREATE TABLE IF NOT EXISTS ai_config (
                config_key TEXT PRIMARY KEY,
                config_value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            """
        )
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(knowledge_chunk)").fetchall()}
        if "embedding" not in columns:
            conn.execute("ALTER TABLE knowledge_chunk ADD COLUMN embedding TEXT")


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    yield


app = FastAPI(title="AI Knowledge Platform AI Service", version="1.0.0", lifespan=lifespan)


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def decode_segment(value: str) -> dict[str, Any]:
    padding = "=" * (-len(value) % 4)
    return json.loads(base64.urlsafe_b64decode(value + padding))


def token_claims(authorization: str | None) -> dict[str, Any] | None:
    value = (authorization or "").strip()
    if len(value) > 8200 or not value.lower().startswith("bearer "):
        return None
    token = value[7:].strip()
    parts = token.split(".")
    if len(parts) != 3:
        return None
    secret = os.getenv("AI_KNOWLEDGE_JWT_SECRET", "local-dev-secret-change-before-production")
    signature = hmac.new(secret.encode(), f"{parts[0]}.{parts[1]}".encode(), hashlib.sha256).digest()
    expected = base64.urlsafe_b64encode(signature).decode().rstrip("=")
    try:
        header = decode_segment(parts[0])
        payload = decode_segment(parts[1])
        now = int(datetime.now().timestamp())
        issued_at = int(payload.get("iat", 0))
        expires_at = int(payload.get("exp", 0))
        role = payload.get("role")
        valid = (hmac.compare_digest(expected, parts[2]) and header.get("alg") == "HS256"
                 and int(payload.get("uid", 0)) > 0
                 and role in {"USER", "ADMIN"}
                 and issued_at <= now + 60 and expires_at > now and expires_at > issued_at
                 and expires_at - issued_at <= int(os.getenv("AI_KNOWLEDGE_JWT_EXPIRES_SECONDS", "28800")) + 60)
        return payload if valid else None
    except (ValueError, TypeError, json.JSONDecodeError):
        return None


def is_admin_token(authorization: str | None) -> bool:
    claims = token_claims(authorization)
    return claims is not None and claims.get("role") == "ADMIN"


def require_user(authorization: str | None) -> dict[str, Any]:
    claims = token_claims(authorization)
    if claims is None:
        raise HTTPException(status_code=401, detail="valid user authorization is required")
    return claims


def require_admin(authorization: str | None) -> None:
    if not is_admin_token(authorization):
        raise HTTPException(status_code=403, detail="admin authorization is required")


def read_ai_config() -> dict[str, Any]:
    defaults: dict[str, Any] = {
        "data_source_scope": "all-approved",
        "match_limit": 5,
        "compliance_rule": "answer-with-references",
        "provider": "local",
        "model": "local-rag",
        "base_url": "",
        "request_url": "",
        "temperature": 0.2,
        "max_upload_mb": 25,
        "notifications_enabled": True,
        "community_enabled": True,
    }
    with connect() as conn:
        rows = conn.execute("SELECT config_key, config_value FROM ai_config").fetchall()
    for row in rows:
        value = row["config_value"]
        if row["config_key"] == "match_limit":
            defaults[row["config_key"]] = int(value)
        elif row["config_key"] == "temperature":
            defaults[row["config_key"]] = max(0.0, min(1.0, float(value)))
        elif row["config_key"] in {"max_upload_mb"}:
            defaults[row["config_key"]] = int(value)
        elif row["config_key"] in {"notifications_enabled", "community_enabled"}:
            defaults[row["config_key"]] = value.lower() in {"1", "true", "yes", "on"}
        else:
            defaults[row["config_key"]] = value
    return defaults


def split_text(text: str) -> list[str]:
    normalized = text.replace("\r", "\n")
    line_chunks = [part.strip() for part in normalized.split("\n") if part.strip()]
    if line_chunks:
        return line_chunks

    chunk_size = 500
    return [
        text[index:index + chunk_size].strip()
        for index in range(0, len(text), chunk_size)
        if text[index:index + chunk_size].strip()
    ]


def tokenize(text: str) -> list[str]:
    normalized = text.lower().strip()
    words = re.findall(r"[a-z0-9_]+", normalized)
    chinese = re.findall(r"[\u4e00-\u9fff]", normalized)
    bigrams = ["".join(chinese[index:index + 2]) for index in range(max(0, len(chinese) - 1))]
    return words + chinese + bigrams


def build_embedding(text: str, dimension: int = VECTOR_DIMENSION) -> list[float]:
    vector = [0.0] * dimension
    for token in tokenize(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimension
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0:
        return vector
    return [round(value / norm, 6) for value in vector]


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    return sum(a * b for a, b in zip(left, right))


def score_chunk(question: str, chunk: sqlite3.Row) -> int:
    keyword = question.strip().lower()
    haystack = f"{chunk['title']}\n{chunk['content']}".lower()
    if not keyword:
        return 0

    tokens = [
        token
        for token in keyword.replace("，", " ").replace(",", " ").split()
        if token
    ]
    exact_score = haystack.count(keyword) * 10
    token_score = sum(haystack.count(token) for token in tokens)
    return exact_score + token_score


def retrieve_chunks(question: str, limit: int = 5) -> list[dict[str, Any]]:
    question_vector = build_embedding(question)
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT id, file_id, title, content, embedding, created_at
            FROM knowledge_chunk
            ORDER BY id DESC
            LIMIT 200
            """
        ).fetchall()

    scored: list[tuple[float, sqlite3.Row]] = []
    for row in rows:
        stored_vector = json.loads(row["embedding"]) if row["embedding"] else build_embedding(row["content"])
        vector_score = cosine_similarity(question_vector, stored_vector)
        lexical_score = min(score_chunk(question, row) / 20, 1.0)
        scored.append((vector_score * 0.75 + lexical_score * 0.25, row))
    matched = [row for score, row in sorted(scored, key=lambda item: (item[0], item[1]["id"]), reverse=True) if score > 0]
    if not matched:
        matched = rows[:limit]
    return [
        {key: row[key] for key in row.keys() if key != "embedding"}
        for row in matched[:limit]
    ]


def ensure_session(request: ChatRequest, user_id: int) -> int:
    if request.session_id:
        with connect() as conn:
            existing = conn.execute(
                "SELECT id FROM ai_chat_session WHERE id = ? AND user_id = ?",
                (request.session_id, user_id),
            ).fetchone()
            if existing:
                return int(existing["id"])

    title = request.question.strip()[:40] or "本地 AI 问答"
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO ai_chat_session(user_id, title, created_at)
            VALUES (?, ?, ?)
            """,
            (user_id, title, now_iso()),
        )
        return int(cursor.lastrowid)


def save_message(session_id: int, role: str, content: str) -> dict[str, Any]:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO ai_chat_message(session_id, role, content, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (session_id, role, content, now_iso()),
        )
        row = conn.execute(
            """
            SELECT id, session_id, role, content, created_at
            FROM ai_chat_message
            WHERE id = ?
            """,
            (cursor.lastrowid,),
        ).fetchone()
    return row_to_dict(row)


def local_answer(question: str, matched: list[dict[str, Any]]) -> str:
    if matched:
        context = "\n".join(chunk["content"] for chunk in matched[:2])
        return f"Based on the local knowledge base, the most relevant material for '{question}' is:\n{context}"
    return f"The local knowledge base has no matching references yet. Your question was recorded: '{question}'"


def validate_upstream_url(value: str, resolve_dns: bool) -> None:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise ValueError("AI request URL must be an absolute http or https URL")
    if parsed.username or parsed.password:
        raise ValueError("AI request URL must not contain embedded credentials")
    try:
        port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    except ValueError as error:
        raise ValueError("AI request URL contains an invalid port") from error
    if os.getenv("AI_ALLOW_PRIVATE_UPSTREAM", "").strip().lower() in {"1", "true", "yes", "on"}:
        return

    hostname = parsed.hostname.rstrip(".").lower()
    if hostname == "localhost" or hostname.endswith(".localhost") or hostname.endswith(".local"):
        raise ValueError("private AI upstream addresses are disabled")

    addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
    try:
        addresses.append(ipaddress.ip_address(hostname))
    except ValueError:
        if resolve_dns:
            try:
                for result in socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM):
                    addresses.append(ipaddress.ip_address(result[4][0]))
            except (socket.gaierror, ValueError) as error:
                raise ValueError("AI upstream hostname could not be resolved") from error

    if any(not address.is_global for address in addresses):
        raise ValueError("private AI upstream addresses are disabled")


def compatible_answer(question: str, matched: list[dict[str, Any]], config: dict[str, Any]) -> str | None:
    api_key = os.getenv("AI_API_KEY", "").strip()
    base_url = str(config.get("base_url", "")).strip().rstrip("/")
    request_url = str(config.get("request_url", "")).strip()
    endpoint = request_url or (base_url + "/chat/completions" if base_url else "")
    if not api_key or not endpoint:
        return None
    try:
        validate_upstream_url(endpoint, resolve_dns=True)
    except ValueError:
        return None
    context = "\n\n".join(
        f"[{item.get('title', 'reference')}] {item.get('content', '')}" for item in matched
    ) or "No matching knowledge references were found."
    payload = {
        "model": str(config.get("model") or "local-rag"),
        "temperature": float(config.get("temperature", 0.2)),
        "messages": [
            {"role": "system", "content": "Answer using the supplied knowledge references. Be concise and cite references when available.\n\n" + context},
            {"role": "user", "content": question},
        ],
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=float(os.getenv("AI_API_TIMEOUT", "30"))) as response:
            body = json.loads(response.read().decode("utf-8"))
        content = body.get("choices", [{}])[0].get("message", {}).get("content")
        return str(content).strip() if content else None
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError, IndexError, TypeError, json.JSONDecodeError):
        return None


@app.get("/ai/health", response_model=ApiResponse)
def health() -> ApiResponse:
    init_db()
    with connect() as conn:
        chunk_count = conn.execute(
            "SELECT COUNT(*) AS count FROM knowledge_chunk"
        ).fetchone()["count"]
        session_count = conn.execute(
            "SELECT COUNT(*) AS count FROM ai_chat_session"
        ).fetchone()["count"]

    return ApiResponse(
        data={
            "service": "ai-service",
            "time": now_iso(),
            "db_path": str(db_path()),
            "chunk_count": chunk_count,
            "session_count": session_count,
            "vector_dimension": VECTOR_DIMENSION,
            "vector_mode": os.getenv("AI_VECTOR_MODE", "local"),
        }
    )


@app.get("/ai/config/public", response_model=ApiResponse)
def public_config() -> ApiResponse:
    config = read_ai_config()
    return ApiResponse(data={
        "max_upload_mb": int(config.get("max_upload_mb", 25)),
        "notifications_enabled": bool(config.get("notifications_enabled", True)),
        "community_enabled": bool(config.get("community_enabled", True)),
    })


@app.post("/ai/parse", response_model=ApiResponse)
def parse_document(request: TextRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_user(authorization)
    init_db()
    with connect() as conn:
        created = index_document(conn, request)
    return ApiResponse(data={"chunks": created, "count": len(created)})


def index_document(conn: sqlite3.Connection, request: TextRequest) -> list[dict[str, Any]]:
    chunks = split_text(request.text)
    title = request.title or "本地解析文档"
    created_at = now_iso()
    created: list[dict[str, Any]] = []
    file_id = request.file_id or 0
    if request.file_id is not None:
        conn.execute("DELETE FROM knowledge_chunk WHERE file_id = ?", (file_id,))
    for chunk in chunks:
        cursor = conn.execute(
            """
            INSERT INTO knowledge_chunk(file_id, title, content, embedding, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (file_id, title, chunk, json.dumps(build_embedding(chunk)), created_at),
        )
        created.append(
            {
                "id": int(cursor.lastrowid),
                "file_id": file_id,
                "title": title,
                "content": chunk,
                "created_at": created_at,
            }
        )
    return created


@app.post("/ai/embedding", response_model=ApiResponse)
def embedding(request: TextRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_user(authorization)
    vector = build_embedding(request.text)
    return ApiResponse(data={"dimension": len(vector), "vector": vector})


@app.get("/ai/vector/status", response_model=ApiResponse)
def vector_status() -> ApiResponse:
    init_db()
    with connect() as conn:
        indexed = conn.execute(
            "SELECT COUNT(*) AS count FROM knowledge_chunk WHERE embedding IS NOT NULL"
        ).fetchone()["count"]
    mode = os.getenv("AI_VECTOR_MODE", "local")
    return ApiResponse(data={
        "mode": mode,
        "dimension": VECTOR_DIMENSION,
        "indexed_chunks": indexed,
        "milvus_endpoint": os.getenv("MILVUS_ENDPOINT", "http://127.0.0.1:19530"),
        "chroma_path": os.getenv("CHROMA_PATH", str(APP_DIR.parent / "data" / "chroma")),
        "external_ready": mode.lower() != "local",
    })


@app.post("/ai/retrieve", response_model=ApiResponse)
def retrieve(request: ChatRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_user(authorization)
    init_db()
    return ApiResponse(data={"matches": retrieve_chunks(request.question)})


@app.get("/ai/history", response_model=ApiResponse)
def chat_history(
    user_id: int | None = None,
    session_id: int | None = None,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    claims = require_user(authorization)
    authenticated_user_id = int(claims["uid"])
    if claims.get("role") != "ADMIN" and user_id is not None and user_id != authenticated_user_id:
        raise HTTPException(status_code=403, detail="access to this user is denied")
    effective_user_id = user_id if claims.get("role") == "ADMIN" else authenticated_user_id
    init_db()
    with connect() as conn:
        sessions = conn.execute(
            "SELECT id, user_id, title, created_at FROM ai_chat_session WHERE (? IS NULL OR user_id = ?) ORDER BY id DESC",
            (effective_user_id, effective_user_id),
        ).fetchall()
        messages = conn.execute(
            "SELECT m.id, m.session_id, m.role, m.content, m.created_at FROM ai_chat_message m "
            "JOIN ai_chat_session s ON s.id = m.session_id "
            "WHERE (? IS NULL OR s.user_id = ?) AND (? IS NULL OR m.session_id = ?) ORDER BY m.id",
            (effective_user_id, effective_user_id, session_id, session_id),
        ).fetchall()
    return ApiResponse(data={"sessions": [row_to_dict(row) for row in sessions],
                             "messages": [row_to_dict(row) for row in messages]})


@app.get("/ai/admin/overview", response_model=ApiResponse)
def ai_admin_overview(authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    health_data = health().data
    return ApiResponse(data={**health_data, "configuration": read_ai_config(),
                             "capabilities": ["data-source-scope", "matching-rules", "chat-audit", "chunk-review"]})


@app.get("/ai/admin/chunks", response_model=ApiResponse)
def admin_chunks(authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    with connect() as conn:
        rows = conn.execute("SELECT id, file_id, title, content, created_at FROM knowledge_chunk ORDER BY id DESC LIMIT 200").fetchall()
    return ApiResponse(data={"chunks": [row_to_dict(row) for row in rows]})


@app.post("/ai/admin/index/rebuild", response_model=ApiResponse)
def rebuild_index(request: RebuildIndexRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    init_db()
    created: list[dict[str, Any]] = []
    with connect() as conn:
        conn.execute("DELETE FROM knowledge_chunk")
        for document in request.documents:
            created.extend(index_document(conn, document))
    return ApiResponse(data={"documents": len(request.documents), "chunks": len(created)})


@app.delete("/ai/admin/index/file/{file_id}", response_model=ApiResponse)
def remove_indexed_file(file_id: int, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    init_db()
    with connect() as conn:
        cursor = conn.execute("DELETE FROM knowledge_chunk WHERE file_id = ?", (file_id,))
    return ApiResponse(data={"file_id": file_id, "removed_chunks": cursor.rowcount})


@app.post("/ai/admin/config", response_model=ApiResponse)
def save_ai_config(request: AiConfigRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    values = request.model_dump()
    for key in ("base_url", "request_url"):
        value = str(values.get(key, "")).strip()
        if value:
            try:
                validate_upstream_url(value, resolve_dns=False)
            except ValueError as error:
                raise HTTPException(status_code=400, detail=str(error)) from error
        values[key] = value
    with connect() as conn:
        for key, value in values.items():
            conn.execute(
                "INSERT INTO ai_config(config_key, config_value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value, updated_at=excluded.updated_at",
                (key, str(value), now_iso()),
            )
    return ApiResponse(data={"configuration": read_ai_config(), "updated": True})


@app.post("/ai/chat", response_model=ApiResponse)
def chat(request: ChatRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    claims = require_user(authorization)
    user_id = int(claims["uid"])
    init_db()
    session_id = ensure_session(request, user_id)
    user_message = save_message(session_id, "user", request.question)
    config = read_ai_config()
    matched = retrieve_chunks(request.question, limit=int(config["match_limit"]))
    answer = compatible_answer(request.question, matched, config) if config.get("provider") == "openai-compatible" else None
    if not answer:
        answer = local_answer(request.question, matched)
    assistant_message = save_message(session_id, "assistant", answer)
    return ApiResponse(
        data={
            "session_id": session_id,
            "answer": answer,
            "references": matched,
            "messages": [user_message, assistant_message],
            "created_at": assistant_message["created_at"],
        }
    )

    if matched:
        context = "；".join(chunk["content"] for chunk in matched[:2])
        answer = f"基于当前本地知识库，与“{request.question}”最相关的资料是：{context}"
    else:
        answer = f"当前本地知识库还没有可引用资料，已记录你的问题：“{request.question}”。"

    assistant_message = save_message(session_id, "assistant", answer)
    return ApiResponse(
        data={
            "session_id": session_id,
            "answer": answer,
            "references": matched,
            "messages": [user_message, assistant_message],
            "created_at": assistant_message["created_at"],
        }
    )
