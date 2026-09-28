from __future__ import annotations

import hashlib
import hmac
import base64
import json
import math
import os
import re
import random
import sqlite3
import threading
import ipaddress
import logging
import socket
import time
import urllib.error
import urllib.request
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from app import migrations
from app import provider_keys
from app import model_discovery


APP_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = APP_DIR.parent / "data" / "ai_service.db"
VECTOR_DIMENSION = int(os.getenv("AI_VECTOR_DIMENSION", "128"))
SENSITIVE_CONTENT_PATTERNS = (
    re.compile(r"\$env:", re.IGNORECASE),
    re.compile(r"\b(?:export|set)\s+[A-Z][A-Z0-9_]*\s*=", re.IGNORECASE),
    re.compile(r"\b[A-Z][A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN|API_KEY|PRIVATE_KEY|_PATH)\b\s*=", re.IGNORECASE),
    re.compile(r"(?:https?://|jdbc:)(?:localhost|127\.0\.0\.1|0\.0\.0\.0|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+)", re.IGNORECASE),
    re.compile(r"\b[a-z]:[\\/]", re.IGNORECASE),
    re.compile(r"(?:^|\s)\.[\\/](?:ai-service|backend|frontend|data|logs)\b", re.IGNORECASE),
    re.compile(r"\b(?:mvn(?:\.cmd)?|npm(?:\.cmd)?|powershell|java\s+-jar|uvicorn|mysql(?:\.exe)?)\b", re.IGNORECASE),
    re.compile(r"\b(?:start-local|restart-local|stop-local)\.(?:ps1|cmd|bat)\b", re.IGNORECASE),
    re.compile(r"/(?:internal|actuator)/", re.IGNORECASE),
    re.compile(r"^\s*```"),
)
INTERNAL_TITLE_PATTERN = re.compile(r"(?:\bacceptance\b|\bsmoke\s*test\b|\binternal\b|验收|内部测试)", re.IGNORECASE)

class ApiResponse(BaseModel):
    code: int = 0
    message: str = "成功"
    data: dict[str, Any]


class TextRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2_000_000)
    file_id: int | None = None
    title: str | None = None


class RebuildIndexRequest(BaseModel):
    documents: list[TextRequest] = Field(default_factory=list, max_length=1000)
    # A rebuild arrives in batches: the first clears the index, the following ones only add to it.
    reset: bool = True


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=4000)
    user_id: int | None = None
    session_id: int | None = None


class SessionTitleRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=100)


class AiConfigRequest(BaseModel):
    platform_name: str = Field(default="知汇", min_length=1, max_length=60)
    platform_notice: str = Field(default="", max_length=500)
    registration_enabled: bool = True
    ai_chat_enabled: bool = True
    knowledge_upload_enabled: bool = True
    user_ranking_enabled: bool = True
    comments_enabled: bool = True
    private_messages_enabled: bool = True
    feedback_enabled: bool = True
    post_audit_required: bool = True
    profile_audit_required: bool = False
    # Off until an operator turns it on: nobody should find their content auto-reviewed by an upgrade.
    ai_audit_enabled: bool = False
    # Publishing by mistake is worse than holding something back, so the bar to publish is the higher one.
    ai_audit_approve_confidence: float = Field(default=0.9, ge=0.5, le=1)
    ai_audit_reject_confidence: float = Field(default=0.85, ge=0.5, le=1)
    # A share of what the model passes goes to a person anyway, so its judgement keeps being checked.
    ai_audit_sample_percent: int = Field(default=10, ge=0, le=100)
    default_publish_policy: str = Field(default="STANDARD", pattern="^(STANDARD|PRE_REVIEW|BLOCKED)$")
    max_post_images: int = Field(default=9, ge=0, le=9)
    max_comment_length: int = Field(default=2000, ge=100, le=5000)
    max_message_length: int = Field(default=2000, ge=100, le=5000)
    draft_retention_days: int = Field(default=30, ge=1, le=3650)
    data_source_scope: str = "all-approved"
    match_limit: int = Field(default=5, ge=1, le=20)
    compliance_rule: str = "answer-with-references"
    provider: str = Field(default="local", pattern="^(local|openai-compatible|anthropic)$")
    model: str = Field(default="local-rag", max_length=200)
    base_url: str = Field(default="", max_length=2000)
    request_url: str = Field(default="", max_length=2000)
    # What AI review calls, when it should differ from AI 检索 (chat). Empty means "the same as chat"; the endpoint
    # and key stay those of the provider, shared by both — see review_settings.
    review_provider: str = Field(default="", pattern="^(|local|openai-compatible|anthropic)$")
    review_model: str = Field(default="", max_length=200)
    temperature: float = Field(default=0.2, ge=0, le=1)
    max_upload_mb: int = Field(default=25, ge=1, le=200)
    pdf_max_upload_mb: int = Field(default=200, ge=1, le=200)
    notifications_enabled: bool = True
    community_enabled: bool = True
    selected_file_ids: list[int] = Field(default_factory=list, max_length=1000)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def contains_sensitive_content(value: str) -> bool:
    text = value or ""
    return any(pattern.search(text) for pattern in SENSITIVE_CONTENT_PATTERNS)


def public_title(value: str | None) -> str:
    title = (value or "").strip()
    return "平台知识文档" if not title or INTERNAL_TITLE_PATTERN.search(title) else title


def sanitize_answer(value: str) -> str:
    text = (value or "").strip()
    text = re.sub(
        r"(?is)^Based on the local knowledge base, the most relevant material for .*? is:\s*",
        "根据知识库内容，相关信息如下：\n",
        text,
    )
    if text.lower().startswith("the local knowledge base has no matching references yet"):
        return "知识库中暂未找到可安全展示的相关内容。"
    safe_lines = [line for line in text.splitlines() if not contains_sensitive_content(line)]
    cleaned = "\n".join(safe_lines).strip()
    return cleaned or "知识库中暂未找到可安全展示的相关内容。"


def public_platform_answer(question: str) -> str | None:
    normalized = re.sub(r"\s+", "", question.lower())
    if "知识" in normalized and any(keyword in normalized for keyword in ("格式", "文件类型", "上传类型")):
        return "平台知识库支持 TXT、Markdown（.md）、PDF 和 Word（.docx）格式，单个文件大小不能超过管理员设置的上传上限。"
    if "搜索" in normalized and any(keyword in normalized for keyword in ("全文", "知识", "怎么", "如何")):
        return "在知识库页面输入标题或正文关键词即可搜索，也可以按 Word、PDF、TXT、Markdown 格式筛选结果。"
    if "社区" in normalized and any(keyword in normalized for keyword in ("功能", "可以", "支持")):
        return "社区支持发布和审核帖子、点赞与取消点赞、收藏、评论与回复、关注作者以及举报不当内容。"
    return None


def purge_sensitive_chunks(conn: sqlite3.Connection) -> int:
    rows = conn.execute("SELECT id, title, content FROM knowledge_chunk").fetchall()
    sensitive_ids = [int(row["id"]) for row in rows if contains_sensitive_content(row["content"])]
    if not sensitive_ids:
        return 0
    placeholders = ",".join("?" for _ in sensitive_ids)
    return conn.execute(f"DELETE FROM knowledge_chunk WHERE id IN ({placeholders})", sensitive_ids).rowcount


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


_initialized_databases: set[Path] = set()


def init_db() -> None:
    """Apply pending schema migrations (app/migrations.py) and drop legacy sensitive chunks.

    Runs once per database file per process.

    Every request path calls this. The purge reads every indexed chunk, so running it each time made each
    request cost grow with the index (about 3 s per request at 200k chunks). New chunks are already filtered
    when they are indexed and again when they are retrieved. A database file that disappears is set up again.
    """
    path = db_path()
    if path in _initialized_databases and path.exists():
        return
    with connect() as conn:
        applied = migrations.migrate(conn)
        if applied:
            print(f"AI database migrated to version {applied[-1]} (applied {applied})", flush=True)
        purge_sensitive_chunks(conn)
    _initialized_databases.add(path)


DEVELOPMENT_JWT_SECRET = "local-dev-secret-change-before-production"
DEVELOPMENT_INTERNAL_TOKEN = "ai-knowledge-local-internal"


def deployment_secret_problem(jwt_secret: str | None, internal_token: str | None,
                              allow_defaults: bool) -> str | None:
    """
    Why this deployment must not serve traffic, or None when it may.

    Both secrets have a development default so that a checkout runs without configuration. Anywhere else that
    is a hole: the signing key is published here, so anyone could mint an administrator session for a service
    that trusts it. Compose supplies real values; this catches the runs that bypass it. Set
    AI_ALLOW_DEFAULT_SECRETS when the defaults are what you actually want, as the local run script does.
    """
    if allow_defaults:
        return None
    if not jwt_secret or not jwt_secret.strip() or jwt_secret.strip() == DEVELOPMENT_JWT_SECRET:
        return ("AI_KNOWLEDGE_JWT_SECRET is missing or still the development value, which is published in this "
                "repository. Set it to a secret of your own, or set AI_ALLOW_DEFAULT_SECRETS for local work.")
    if internal_token and internal_token.strip() == DEVELOPMENT_INTERNAL_TOKEN:
        return ("PLATFORM_INTERNAL_USER_TOKEN is still the development value, which is published in this "
                "repository. Set it to a secret of your own, or set AI_ALLOW_DEFAULT_SECRETS for local work.")
    return None


@asynccontextmanager
async def lifespan(_: FastAPI):
    problem = deployment_secret_problem(
        os.getenv("AI_KNOWLEDGE_JWT_SECRET"),
        os.getenv("PLATFORM_INTERNAL_USER_TOKEN"),
        os.getenv("AI_ALLOW_DEFAULT_SECRETS", "").strip().lower() in {"1", "true", "yes", "on"},
    )
    if problem:
        raise RuntimeError(problem)
    init_db()
    start_model_discovery()
    yield


def start_model_discovery() -> None:
    """Asks the OpenAI-compatible endpoint for its models once, in the background; see model_discovery."""
    try:
        config = read_ai_config()
        review = review_settings(config)
        chat_uses = config.get("provider") == "openai-compatible"
        review_uses = review.get("provider") == "openai-compatible"
        provider = "openai-compatible" if chat_uses or review_uses else str(config.get("provider"))
        api_key = resolve_provider_key(provider)["secret"] if provider == "openai-compatible" else None
    except Exception as error:  # noqa: BLE001 - discovery is advisory; the service starts regardless
        logging.getLogger("ai-service").warning("model discovery not started: %s", type(error).__name__)
        return
    model_discovery.run_in_background(
        provider=provider, base_url=config.get("base_url"), api_key=api_key,
        configured_model=config.get("model") if chat_uses else None,
        review_model=review.get("model") if review_uses and review.get("model") != config.get("model") else None,
        validate=lambda url: validate_upstream_url(url, resolve_dns=True))


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


def require_admin(authorization: str | None) -> dict[str, Any]:
    claims = token_claims(authorization)
    if claims is None or claims.get("role") != "ADMIN":
        raise HTTPException(status_code=403, detail="admin authorization is required")
    return claims


def is_super_admin(claims: dict[str, Any] | None) -> bool:
    """The claim only counts on an administrator's token; on its own it grants nothing."""
    return bool(claims) and claims.get("role") == "ADMIN" and claims.get("sa") is True


def require_super_admin(authorization: str | None) -> dict[str, Any]:
    claims = require_admin(authorization)
    if not is_super_admin(claims):
        raise HTTPException(status_code=403, detail="需要超级管理员权限")
    return claims


def resolve_provider_key(provider: str) -> dict[str, Any]:
    init_db()
    with connect() as conn:
        return provider_keys.resolve(conn, provider)


# Where the API key gets sent. An ordinary administrator keeps every other setting but may not repoint these.
SUPER_ADMIN_ONLY_SETTINGS = ("provider", "model", "base_url", "request_url", "review_provider", "review_model")


# ---- Admin action log -------------------------------------------------------------------------------------------
# Settings changes and index rebuilds are reported to the user service, which keeps the platform's action log.
# Delivery happens in the background and never fails the request; undeliverable entries go to this service's log.
audit_log = logging.getLogger("ai.admin_audit")
_audit_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="admin-audit")
AUDIT_RETRY_DELAYS = (0, 1, 5)
AI_CONFIG_LABELS = {
    "platform_name": "平台名称",
    "platform_notice": "平台公告",
    "registration_enabled": "开放注册",
    "ai_chat_enabled": "AI 问答",
    "knowledge_upload_enabled": "用户上传知识",
    "user_ranking_enabled": "用户排行",
    "comments_enabled": "评论",
    "private_messages_enabled": "私信",
    "feedback_enabled": "反馈工单",
    "post_audit_required": "帖子先审后发",
    "profile_audit_required": "资料修改先审后改",
    "ai_audit_enabled": "AI 内容审核",
    "ai_audit_approve_confidence": "AI 自动通过门槛",
    "ai_audit_reject_confidence": "AI 自动驳回门槛",
    "ai_audit_sample_percent": "AI 通过抽样复核比例",
    "default_publish_policy": "默认发帖策略",
    "max_post_images": "帖子配图上限",
    "max_comment_length": "评论字数上限",
    "max_message_length": "私信字数上限",
    "draft_retention_days": "草稿保留天数",
    "data_source_scope": "AI 知识范围",
    "match_limit": "检索片段数",
    "compliance_rule": "回答规则",
    "provider": "模型服务",
    "model": "模型",
    "base_url": "接口地址",
    "request_url": "请求地址",
    "review_provider": "审核模型服务",
    "review_model": "审核模型",
    "temperature": "温度",
    "max_upload_mb": "上传大小上限",
    "pdf_max_upload_mb": "PDF 上传大小上限",
    "notifications_enabled": "通知",
    "community_enabled": "社区",
    "selected_file_ids": "指定知识文件",
}


def _audit_value(key: str, value: Any) -> Any:
    if key == "selected_file_ids":
        return f"{len(value or [])} 个文件"
    if key in {"base_url", "request_url"} and value:
        # Credentials written into a URL are not kept.
        parts = urllib.parse.urlsplit(str(value))
        if parts.username or parts.password:
            host = parts.hostname or ""
            if parts.port:
                host = f"{host}:{parts.port}"
            return urllib.parse.urlunsplit((parts.scheme, host, parts.path, "", ""))
        return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    return value


def config_changes(before: dict[str, Any], after: dict[str, Any]) -> list[dict[str, Any]]:
    changes = []
    for key, label in AI_CONFIG_LABELS.items():
        old, new = _audit_value(key, before.get(key)), _audit_value(key, after.get(key))
        if key == "selected_file_ids":
            if sorted(before.get(key) or []) == sorted(after.get(key) or []):
                continue
        elif old == new:
            continue
        changes.append({"field": key, "label": label, "before": old, "after": new})
    return changes


def build_audit_entry(claims: dict[str, Any], client_ip: str | None, action: str, target_type: str,
                      target_id: str | None, target_label: str, summary: str,
                      detail: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "actorId": int(claims["uid"]),
        "actorName": str(claims.get("sub", ""))[:64],
        "action": action,
        "category": "SYSTEM",
        "targetType": target_type,
        "targetId": target_id,
        "targetLabel": target_label,
        "subjectUserId": None,
        "summary": summary[:500],
        "detail": detail or {},
        "source": "ai-service",
        "clientIp": client_ip.strip()[:64] or None if isinstance(client_ip, str) else None,
    }


def deliver_audit_entry(entry: dict[str, Any]) -> bool:
    url = os.getenv("PLATFORM_AUDIT_URL", "http://127.0.0.1:8101/user/internal/audit")
    token = os.getenv("PLATFORM_INTERNAL_USER_TOKEN", "ai-knowledge-local-internal")
    body = json.dumps(entry, ensure_ascii=False).encode("utf-8")
    problem = ""
    for delay in AUDIT_RETRY_DELAYS:
        if delay:
            time.sleep(delay)
        try:
            request = urllib.request.Request(url, data=body, method="POST", headers={
                "Content-Type": "application/json", "X-Internal-Token": token})
            with urllib.request.urlopen(request, timeout=5) as response:
                if json.loads(response.read().decode("utf-8")).get("code") == 0:
                    return True
                problem = "rejected"
        except (OSError, ValueError) as error:
            problem = str(error)
    audit_log.error("Admin audit entry could not be delivered (%s): %s", problem, body.decode("utf-8"))
    return False


def record_admin_action(claims: dict[str, Any], client_ip: str | None, action: str, target_type: str,
                        target_id: str | None, target_label: str, summary: str,
                        detail: dict[str, Any] | None = None) -> None:
    try:
        entry = build_audit_entry(claims, client_ip, action, target_type, target_id, target_label, summary, detail)
        _audit_executor.submit(deliver_audit_entry, entry)
    except Exception as error:  # the action itself must never fail because of the log
        audit_log.error("Admin audit entry could not be prepared: %s", error)


def read_ai_config() -> dict[str, Any]:
    defaults: dict[str, Any] = {
        "platform_name": "知汇",
        "platform_notice": "",
        "registration_enabled": True,
        "ai_chat_enabled": True,
        "knowledge_upload_enabled": True,
        "user_ranking_enabled": True,
        "comments_enabled": True,
        "private_messages_enabled": True,
        "feedback_enabled": True,
        "post_audit_required": True,
        "profile_audit_required": False,
        "ai_audit_enabled": False,
        "ai_audit_approve_confidence": 0.9,
        "ai_audit_reject_confidence": 0.85,
        "ai_audit_sample_percent": 10,
        "default_publish_policy": "STANDARD",
        "max_post_images": 9,
        "max_comment_length": 2000,
        "max_message_length": 2000,
        "draft_retention_days": 30,
        "data_source_scope": "all-approved",
        "match_limit": 5,
        "compliance_rule": "answer-with-references",
        "provider": "local",
        "model": "local-rag",
        "base_url": "",
        "request_url": "",
        "review_provider": "",
        "review_model": "",
        "temperature": 0.2,
        "max_upload_mb": 25,
        "pdf_max_upload_mb": 200,
        "notifications_enabled": True,
        "community_enabled": True,
        "selected_file_ids": [],
    }
    with connect() as conn:
        rows = conn.execute("SELECT config_key, config_value FROM ai_config").fetchall()
    for row in rows:
        value = row["config_value"]
        if row["config_key"] == "match_limit":
            defaults[row["config_key"]] = int(value)
        elif row["config_key"] in {"temperature", "ai_audit_approve_confidence", "ai_audit_reject_confidence"}:
            defaults[row["config_key"]] = max(0.0, min(1.0, float(value)))
        elif row["config_key"] in {
            "max_upload_mb", "pdf_max_upload_mb", "max_post_images", "max_comment_length", "max_message_length",
            "draft_retention_days", "ai_audit_sample_percent"
        }:
            defaults[row["config_key"]] = int(value)
        elif row["config_key"] in {
            "registration_enabled", "ai_chat_enabled", "knowledge_upload_enabled", "user_ranking_enabled",
            "comments_enabled", "private_messages_enabled", "feedback_enabled", "post_audit_required",
            "profile_audit_required", "ai_audit_enabled",
            "notifications_enabled", "community_enabled"
        }:
            defaults[row["config_key"]] = value.lower() in {"1", "true", "yes", "on"}
        elif row["config_key"] == "selected_file_ids":
            try:
                parsed = json.loads(value)
                defaults[row["config_key"]] = [int(item) for item in parsed if int(item) > 0]
            except (TypeError, ValueError, json.JSONDecodeError):
                defaults[row["config_key"]] = []
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

    question_tokens = {token for token in tokenize(keyword) if len(token) >= 2}
    content_tokens = {token for token in tokenize(haystack) if len(token) >= 2}
    overlap = question_tokens & content_tokens
    exact_score = haystack.count(keyword) * 10
    return exact_score + len(overlap) * 2


def retrieve_chunks(question: str, limit: int = 5, allowed_file_ids: set[int] | None = None) -> list[dict[str, Any]]:
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
        if allowed_file_ids is not None and int(row["file_id"]) not in allowed_file_ids:
            continue
        if contains_sensitive_content(row["content"]):
            continue
        lexical_raw = score_chunk(question, row)
        if lexical_raw <= 0:
            continue
        stored_vector = json.loads(row["embedding"]) if row["embedding"] else build_embedding(row["content"])
        vector_score = cosine_similarity(question_vector, stored_vector)
        lexical_score = min(lexical_raw / 20, 1.0)
        scored.append((vector_score * 0.75 + lexical_score * 0.25, row))
    matched = [row for _, row in sorted(scored, key=lambda item: (item[0], item[1]["id"]), reverse=True)]
    result: list[dict[str, Any]] = []
    for row in matched[:limit]:
        item = {key: row[key] for key in row.keys() if key != "embedding"}
        item["title"] = public_title(item.get("title"))
        result.append(item)
    return result


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
    if role == "assistant":
        content = sanitize_answer(content)
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
        return sanitize_answer(f"根据知识库内容，相关信息如下：\n{context}")
    return "知识库中暂未找到与该问题相关的公开内容。"


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

    # Without TLS there is nothing tying the connection to the hostname that was checked: a name that answers
    # differently a moment later would receive the API key over a plain socket. With TLS the certificate has
    # to match before the request is sent, so a rebound name fails the handshake instead.
    if parsed.scheme.lower() != "https":
        raise ValueError("AI request URL must use https")

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


# A review must never hold up publishing for long; past this the decision goes to a person.
REVIEW_TIMEOUT_SECONDS = float(os.getenv("AI_REVIEW_TIMEOUT_SECONDS", "8"))

REVIEW_DECISIONS = ("APPROVE", "REJECT", "ESCALATE")

# How much of a body the model is shown. Longer content is judged on its beginning only, so an approval of it is
# never final (see review_content); the sensitive-word rules still read all of it.
MODEL_PREVIEW_CHARS = 4000
LONG_CONTENT_NOTE = "内容较长，AI 只审阅了开头部分"

# The local rules only look for sensitive words and a plausible length. They must not clear a bar meant for a
# model's judgement, so they claim a fixed, modest confidence: raise the threshold above this and everything
# goes to a person, which is the point of being able to raise it.
LOCAL_RULE_CONFIDENCE = 0.8

_review_lock = threading.Lock()
_review_health: dict[str, Any] = {
    "requested": 0, "approved": 0, "rejected": 0, "escalated": 0, "sampled": 0, "model_failures": 0,
    "last_decision": None, "last_decision_at": None, "last_failure": None, "last_failure_at": None,
}


def record_review_failure(reason: str) -> None:
    """A model that could not be reached or understood, counted apart from having no model at all."""
    with _review_lock:
        _review_health["model_failures"] += 1
        _review_health["last_failure"] = str(reason)[:200]
        _review_health["last_failure_at"] = now_iso()


def record_review_decision(decision: str, sampled: bool = False) -> None:
    with _review_lock:
        _review_health["requested"] += 1
        _review_health[{"APPROVE": "approved", "REJECT": "rejected"}.get(decision, "escalated")] += 1
        if sampled:
            _review_health["sampled"] += 1
        _review_health["last_decision"] = decision
        _review_health["last_decision_at"] = now_iso()


def review_health(config: dict[str, Any] | None = None) -> dict[str, Any]:
    """
    Enough to tell working review from review that is quietly doing nothing.

    Both failure modes are invisible from outside: a model that always errors escalates everything, and a
    switch that never reaches the services means nothing is ever asked. Counters make the difference legible.
    """
    settings = review_settings(config if config is not None else read_ai_config())
    with _review_lock:
        snapshot = dict(_review_health)
    snapshot["enabled"] = bool(settings.get("ai_audit_enabled", False))
    snapshot["provider"] = str(settings.get("provider"))
    # The model actually called: Claude falls back to its default when none is named.
    snapshot["model"] = (provider_keys.claude_model(settings) if snapshot["provider"] == "anthropic"
                         else str(settings.get("model") or ""))
    provider = str(settings.get("provider"))
    key = resolve_provider_key(provider) if provider in provider_keys.PROVIDERS else {"source": "none"}
    snapshot["key_source"] = key["source"]
    snapshot["model_configured"] = bool(
        key["source"] in ("stored", "environment")
        and (provider == "anthropic"
             or (str(settings.get("request_url", "")).strip() or str(settings.get("base_url", "")).strip())))
    discovered = model_discovery.current()
    snapshot["model_discovery"] = {"outcome": discovered.outcome, "reason": discovered.reason,
                                   "models": len(discovered.models)}
    if provider == "openai-compatible":
        snapshot["active_model"] = model_discovery.select_model(settings.get("model"), settings.get("base_url"))[0]
    snapshot["decided_automatically"] = snapshot["approved"] + snapshot["rejected"]
    snapshot["idle"] = snapshot["enabled"] and snapshot["requested"] == 0
    return snapshot

REVIEW_PROMPT = (
    "你是知识社区的内容审核员。判断下面的内容是否可以公开发布。\n"
    "只输出 JSON：{\"decision\": \"APPROVE\" 或 \"REJECT\", \"confidence\": 0 到 1 的小数, \"reason\": \"简短中文理由\"}。\n"
    "违反法律法规、包含辱骂攻击、色情暴力、广告垃圾或泄露他人隐私的内容应当 REJECT；\n"
    "正常的知识、讨论与提问应当 APPROVE。无法判断时给出较低的 confidence。\n\n"
)


def escalate(reason: str) -> dict[str, Any]:
    """Hand the decision back to a person. Every failure path ends here."""
    record_review_decision("ESCALATE")
    return {"decision": "ESCALATE", "confidence": 0.0, "reason": reason}


def parse_verdict(content: str) -> dict[str, Any] | None:
    """The model's JSON verdict, or None — with the reason recorded — when there is none to act on."""
    try:
        start, end = content.find("{"), content.rfind("}")
        verdict = json.loads(content[start:end + 1]) if start >= 0 < end else None
    except (ValueError, TypeError):
        verdict = None
    if not isinstance(verdict, dict) or verdict.get("decision") not in ("APPROVE", "REJECT"):
        record_review_failure("模型返回的内容无法解析为审核结论")
        return None
    try:
        confidence = max(0.0, min(1.0, float(verdict.get("confidence", 0))))
    except (TypeError, ValueError):
        record_review_failure("模型返回的置信度无法解析")
        return None
    return {"decision": verdict["decision"], "confidence": confidence,
            "reason": str(verdict.get("reason", ""))[:200]}


def review_settings(config: dict[str, Any]) -> dict[str, Any]:
    """
    The settings AI review calls its model with: chat's, with review_provider and review_model laid over them.
    Both empty means review uses exactly what chat uses. A review model left empty under a provider other than
    chat's is left empty too — chat's model name means nothing to another provider (Claude then uses its
    default). The endpoint and the key are the provider's and are shared: giving review its own address under the
    same provider would send that provider's key to a server it was not issued for.
    """
    settings = dict(config)
    provider = str(config.get("review_provider") or "").strip()
    model = str(config.get("review_model") or "").strip()
    if provider and provider != config.get("provider"):
        settings["provider"] = provider
        settings["model"] = model
    elif model:
        settings["model"] = model
    return settings


def review_settings_problem(values: dict[str, Any]) -> str | None:
    """Why these settings would leave review unable to call its model, or None."""
    review = review_settings(values)
    if review.get("provider") != "openai-compatible" or values.get("provider") == "openai-compatible":
        return None
    if not str(review.get("model") or "").strip():
        return "审核使用 OpenAI 兼容接口时，请填写审核模型名称"
    if not (str(values.get("request_url") or "").strip() or str(values.get("base_url") or "").strip()):
        return "审核使用 OpenAI 兼容接口时，请填写接口地址"
    return None


def model_review(title: str, body: str, config: dict[str, Any]) -> dict[str, Any] | None:
    """Ask the configured model. Returns None when there is no usable answer, so the caller can escalate."""
    provider = str(config.get("provider"))
    if provider not in provider_keys.PROVIDERS:
        return None
    key = resolve_provider_key(provider)
    if key["source"] == "undecryptable":
        record_review_failure("启用的模型密钥无法解密，可能是 AI_KEY_ENCRYPTION_KEY 已更换")
        return None
    if not key["secret"]:
        return None
    if provider == "anthropic":
        return claude_review(title, body, config, key["secret"])
    api_key = key["secret"]
    base_url = str(config.get("base_url", "")).strip().rstrip("/")
    request_url = str(config.get("request_url", "")).strip()
    endpoint = request_url or (base_url + "/chat/completions" if base_url else "")
    if not endpoint:
        return None
    try:
        validate_upstream_url(endpoint, resolve_dns=True)
    except ValueError:
        return None

    payload = compatible_payload(
        config, str(config.get("model", "")),
        [{"role": "user", "content": f"{REVIEW_PROMPT}标题：{title}\n正文：{body[:MODEL_PREVIEW_CHARS]}"}], 0)
    request = urllib.request.Request(
        endpoint, data=json.dumps(payload).encode("utf-8"), method="POST",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=REVIEW_TIMEOUT_SECONDS) as response:
            answer = json.loads(response.read().decode("utf-8"))
        content = answer["choices"][0]["message"]["content"]
    except Exception as error:  # noqa: BLE001 - an unreachable or surprising model must never block publishing
        record_review_failure(f"{type(error).__name__}: {error}")
        return None
    return parse_verdict(str(content))


def claude_review(title: str, body: str, config: dict[str, Any], secret: str,
                  client_factory: Any = None) -> dict[str, Any] | None:
    """
    The same question put to Claude. The instructions go in the system prompt and the content in the user turn,
    so a document that tries to instruct the reviewer is at least speaking from the wrong side. Effort is low:
    this is a short classification on the publishing path, where waiting is what hurts.
    """
    factory = client_factory or provider_keys.make_claude_client
    try:
        client = factory(secret, REVIEW_TIMEOUT_SECONDS, 0)
        text, problem = provider_keys.claude_text(
            client, provider_keys.claude_model(config), REVIEW_PROMPT,
            f"标题：{title}\n正文：{body[:MODEL_PREVIEW_CHARS]}",
            provider_keys.REVIEW_MAX_TOKENS, effort="low")
    except Exception as error:  # noqa: BLE001 - an unreachable or surprising model must never block publishing
        status = getattr(error, "status_code", None)
        record_review_failure(f"{type(error).__name__}" + (f"（HTTP {status}）" if status else ""))
        return None
    if text is None:
        record_review_failure(problem or "模型没有给出回答")
        return None
    return parse_verdict(text)


def review_content(title: str, body: str, config: dict[str, Any] | None = None) -> dict[str, Any]:
    """
    The verdict on one piece of content. REJECT and APPROVE are acted on; ESCALATE means a person decides,
    and is also what every failure returns, so an unavailable model never publishes anything by itself.
    """
    settings = config if config is not None else read_ai_config()
    if not settings.get("ai_audit_enabled", True):
        return escalate("AI 审核未开启")

    combined = f"{title or ''}\n{body or ''}".strip()
    if not combined:
        return escalate("没有可供审核的内容")
    if contains_sensitive_content(combined):
        return decided({"decision": "REJECT", "confidence": 1.0, "reason": "命中平台敏感词规则"})

    approve_at = float(settings.get("ai_audit_approve_confidence", 0.9))
    reject_at = float(settings.get("ai_audit_reject_confidence", 0.85))

    verdict = model_review(title or "", body or "", review_settings(settings))
    if verdict is None:
        # No model configured, or it could not be reached. The local rules cannot judge meaning, so they claim
        # only LOCAL_RULE_CONFIDENCE; if the bar for publishing is above that, a person decides.
        if len(combined) < 20:
            return escalate("内容过短，无法自动判断")
        if LOCAL_RULE_CONFIDENCE < approve_at:
            return escalate(f"仅有本地规则判断（{LOCAL_RULE_CONFIDENCE:.2f}），低于自动通过门槛"
                            f"（{approve_at:.2f}）")
        verdict = {"decision": "APPROVE", "confidence": LOCAL_RULE_CONFIDENCE,
                   "reason": "未命中敏感规则（本地规则判断）"}
    else:
        threshold = approve_at if verdict["decision"] == "APPROVE" else reject_at
        if verdict["confidence"] < threshold:
            return escalate(f"模型把握不足（{verdict['confidence']:.2f}）：{verdict['reason']}")
        # The model saw only the beginning. What it found there is enough to reject on, but not to publish the rest.
        if verdict["decision"] == "APPROVE" and len(body or "") > MODEL_PREVIEW_CHARS:
            return escalate(f"{LONG_CONTENT_NOTE}（前 {MODEL_PREVIEW_CHARS} 字，共 {len(body or '')} 字）。"
                            f"{verdict['reason']}")

    # A share of what would be published goes to a person anyway. Content can talk to the model — a document
    # that tells it to approve itself is the obvious attack — so its approvals keep being spot-checked.
    if verdict["decision"] == "APPROVE" and sampled_for_review(settings):
        percent = int(settings.get("ai_audit_sample_percent", 10))
        record_review_decision("ESCALATE", sampled=True)
        return {"decision": "ESCALATE", "confidence": verdict["confidence"],
                "reason": f"抽样复核（{percent}%）：AI 判定通过，仍由人工确认。{verdict['reason']}"}
    return decided(verdict)


def decided(verdict: dict[str, Any]) -> dict[str, Any]:
    record_review_decision(verdict["decision"])
    return verdict


def sampled_for_review(settings: dict[str, Any]) -> bool:
    """True for the share of approvals that a person should look at anyway."""
    try:
        percent = int(settings.get("ai_audit_sample_percent", 10))
    except (TypeError, ValueError):
        percent = 10
    if percent <= 0:
        return False
    if percent >= 100:
        return True
    return random.random() * 100 < percent


def answer_system_prompt(matched: list[dict[str, Any]], config: dict[str, Any]) -> str:
    context = "\n\n".join(
        f"[{item.get('title', 'reference')}] {item.get('content', '')}" for item in matched
        if not contains_sensitive_content(str(item.get("content", "")))
    ) or "未找到匹配的公开知识内容。"
    compliance = str(config.get("compliance_rule") or "answer-with-references")
    compliance_instruction = {
        "answer-with-references": "回答结尾列出使用的资料标题。",
        "strict-factual": "只陈述资料中能够直接支持的事实；资料不足时明确说明。",
        "concise": "用不超过三段的简洁中文回答。",
    }.get(compliance, "遵守平台内容规范并只依据公开资料回答。")
    return ("请仅根据提供的公开知识内容回答；不得输出环境变量、密钥、内部地址、服务器路径或部署命令。"
            + compliance_instruction + "\n\n" + context)


def model_answer(question: str, matched: list[dict[str, Any]], config: dict[str, Any]) -> str | None:
    """The configured model's answer, or None so chat falls back to the local answer."""
    provider = str(config.get("provider"))
    if provider not in provider_keys.PROVIDERS:
        return None
    key = resolve_provider_key(provider)
    if not key["secret"]:
        return None
    if provider == "anthropic":
        return claude_answer(question, matched, config, key["secret"])
    return compatible_answer(question, matched, config, key["secret"])


def claude_answer(question: str, matched: list[dict[str, Any]], config: dict[str, Any], secret: str,
                  client_factory: Any = None) -> str | None:
    factory = client_factory or provider_keys.make_claude_client
    try:
        client = factory(secret, float(os.getenv("AI_CLAUDE_TIMEOUT", "120")), 1)
        text, problem = provider_keys.claude_text(
            client, provider_keys.claude_model(config), answer_system_prompt(matched, config), question,
            provider_keys.CHAT_MAX_TOKENS)
    except Exception as error:  # noqa: BLE001 - chat falls back to the local answer rather than failing
        logging.getLogger("ai-service").warning("Claude answer failed: %s", type(error).__name__)
        return None
    if text is None:
        logging.getLogger("ai-service").info("Claude gave no answer: %s", problem)
    return text


def compatible_payload(config: dict[str, Any], configured_model: str, messages: list[dict[str, str]],
                       temperature: float) -> dict[str, Any]:
    """
    A chat request for the OpenAI-compatible provider, for the model start-up discovery settled on. Qwen3 is told
    not to think; no other model is sent the parameter, which the OpenAI API itself would reject.
    """
    model = model_discovery.active_model(configured_model, config.get("base_url"))
    payload: dict[str, Any] = {"model": model, "messages": messages, "temperature": temperature}
    if model_discovery.wants_thinking_off(model):
        payload["enable_thinking"] = False
    return payload


def compatible_answer(question: str, matched: list[dict[str, Any]], config: dict[str, Any],
                      api_key: str | None = None) -> str | None:
    api_key = (api_key if api_key is not None else resolve_provider_key("openai-compatible")["secret"] or "").strip()
    base_url = str(config.get("base_url", "")).strip().rstrip("/")
    request_url = str(config.get("request_url", "")).strip()
    endpoint = request_url or (base_url + "/chat/completions" if base_url else "")
    if not api_key or not endpoint:
        return None
    try:
        validate_upstream_url(endpoint, resolve_dns=True)
    except ValueError:
        return None
    payload = compatible_payload(config, str(config.get("model") or "local-rag"), [
        {"role": "system", "content": answer_system_prompt(matched, config)},
        {"role": "user", "content": question},
    ], float(config.get("temperature", 0.2)))
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
            "chunk_count": chunk_count,
            "session_count": session_count,
            "database_mode": "sqlite",
            "vector_mode": os.getenv("AI_VECTOR_MODE", "local"),
        }
    )


@app.get("/ai/config/public", response_model=ApiResponse)
def public_config() -> ApiResponse:
    config = read_ai_config()
    return ApiResponse(data={
        "platform_name": str(config.get("platform_name", "知汇")),
        "platform_notice": str(config.get("platform_notice", "")),
        "registration_enabled": bool(config.get("registration_enabled", True)),
        "ai_chat_enabled": bool(config.get("ai_chat_enabled", True)),
        "knowledge_upload_enabled": bool(config.get("knowledge_upload_enabled", True)),
        "user_ranking_enabled": bool(config.get("user_ranking_enabled", True)),
        "comments_enabled": bool(config.get("comments_enabled", True)),
        "private_messages_enabled": bool(config.get("private_messages_enabled", True)),
        "feedback_enabled": bool(config.get("feedback_enabled", True)),
        "post_audit_required": bool(config.get("post_audit_required", True)),
        "profile_audit_required": bool(config.get("profile_audit_required", False)),
        # The knowledge and community services read this to decide whether to ask for a review at all.
        "ai_audit_enabled": bool(config.get("ai_audit_enabled", False)),
        "default_publish_policy": str(config.get("default_publish_policy", "STANDARD")),
        "max_post_images": int(config.get("max_post_images", 9)),
        "max_comment_length": int(config.get("max_comment_length", 2000)),
        "max_message_length": int(config.get("max_message_length", 2000)),
        "draft_retention_days": int(config.get("draft_retention_days", 30)),
        "max_upload_mb": int(config.get("max_upload_mb", 25)),
        "pdf_max_upload_mb": int(config.get("pdf_max_upload_mb", 200)),
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
    chunks = [chunk for chunk in split_text(request.text) if not contains_sensitive_content(chunk)]
    title = public_title(request.title or "本地解析文档")
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
        "external_ready": mode.lower() != "local",
    })


@app.post("/ai/retrieve", response_model=ApiResponse)
def retrieve(request: ChatRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_user(authorization)
    init_db()
    config = read_ai_config()
    allowed_file_ids = None
    if config.get("data_source_scope") == "admin-selected":
        allowed_file_ids = {int(item) for item in config.get("selected_file_ids", []) if int(item) > 0}
    return ApiResponse(data={"matches": retrieve_chunks(
        request.question,
        limit=int(config.get("match_limit", 5)),
        allowed_file_ids=allowed_file_ids,
    )})


@app.get("/ai/history", response_model=ApiResponse)
def chat_history(
    user_id: int | None = None,
    session_id: int | None = None,
    include_messages: bool = True,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    """Sessions plus their messages. Pass include_messages=false to list sessions only: without a session_id
    the messages cover every conversation in scope, which the session lists never display."""
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
        ).fetchall() if include_messages else []
    public_messages = []
    for row in messages:
        item = row_to_dict(row)
        if item.get("role") == "assistant":
            item["content"] = sanitize_answer(str(item.get("content", "")))
        public_messages.append(item)
    return ApiResponse(data={"sessions": [row_to_dict(row) for row in sessions],
                             "messages": public_messages})


@app.put("/ai/session/{session_id}", response_model=ApiResponse)
def rename_chat_session(
    session_id: int,
    request: SessionTitleRequest,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    claims = require_user(authorization)
    title = request.title.strip()
    if not title:
        raise HTTPException(status_code=400, detail="session title is required")
    init_db()
    with connect() as conn:
        session = conn.execute(
            "SELECT id, user_id, title, created_at FROM ai_chat_session WHERE id = ?", (session_id,)
        ).fetchone()
        if session is None:
            raise HTTPException(status_code=404, detail="AI session not found")
        if claims.get("role") != "ADMIN" and int(session["user_id"] or 0) != int(claims["uid"]):
            raise HTTPException(status_code=403, detail="access to this AI session is denied")
        conn.execute("UPDATE ai_chat_session SET title = ? WHERE id = ?", (title, session_id))
    return ApiResponse(data={"id": session_id, "title": title, "renamed": True})


@app.delete("/ai/session/{session_id}", response_model=ApiResponse)
def delete_chat_session(
    session_id: int,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    claims = require_user(authorization)
    init_db()
    with connect() as conn:
        session = conn.execute(
            "SELECT id, user_id FROM ai_chat_session WHERE id = ?", (session_id,)
        ).fetchone()
        if session is None:
            raise HTTPException(status_code=404, detail="AI session not found")
        if claims.get("role") != "ADMIN" and int(session["user_id"] or 0) != int(claims["uid"]):
            raise HTTPException(status_code=403, detail="access to this AI session is denied")
        removed_messages = conn.execute(
            "DELETE FROM ai_chat_message WHERE session_id = ?", (session_id,)
        ).rowcount
        conn.execute("DELETE FROM ai_chat_session WHERE id = ?", (session_id,))
    return ApiResponse(data={"id": session_id, "removed": True, "removed_messages": removed_messages})


@app.get("/ai/admin/overview", response_model=ApiResponse)
def ai_admin_overview(authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    health_data = health().data
    configuration = read_ai_config()
    return ApiResponse(data={**health_data, "configuration": configuration,
                             "reviewHealth": review_health(configuration)})


@app.get("/ai/admin/chunks", response_model=ApiResponse)
def admin_chunks(authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    with connect() as conn:
        rows = conn.execute("SELECT id, file_id, title, content, created_at FROM knowledge_chunk ORDER BY id DESC LIMIT 200").fetchall()
    return ApiResponse(data={"chunks": [row_to_dict(row) for row in rows]})


ADMIN_PAGE_MAX = 100


def admin_page(
    conn: sqlite3.Connection,
    table: str,
    columns: str,
    keyword_columns: tuple[str, ...],
    keyword: str | None,
    cursor: int | None,
    limit: int,
) -> dict[str, Any]:
    """Newest-first keyset page for the governance tables.

    table and columns are fixed by the callers, never taken from the request. The total is only counted for
    a first page that is full: a short first page is its own total, and later pages send None.
    """
    size = min(max(limit, 1), ADMIN_PAGE_MAX)
    conditions: list[str] = []
    params: list[Any] = []
    text = (keyword or "").strip()
    if text:
        conditions.append("(" + " OR ".join(f"CAST({column} AS TEXT) LIKE ?" for column in keyword_columns) + ")")
        params.extend([f"%{text}%"] * len(keyword_columns))
    page_conditions = [*conditions, *(["id < ?"] if cursor is not None else [])]
    page_params = [*params, *([cursor] if cursor is not None else [])]
    page_where = f" WHERE {' AND '.join(page_conditions)}" if page_conditions else ""
    rows = conn.execute(
        f"SELECT {columns} FROM {table}{page_where} ORDER BY id DESC LIMIT ?", (*page_params, size + 1)
    ).fetchall()
    has_more = len(rows) > size
    items = [row_to_dict(row) for row in rows[:size]]
    total = None
    if cursor is None:
        if has_more:
            where = f" WHERE {' AND '.join(conditions)}" if conditions else ""
            total = conn.execute(f"SELECT COUNT(*) AS count FROM {table}{where}", params).fetchone()["count"]
        else:
            total = len(items)
    return {"items": items, "nextCursor": items[-1]["id"] if items else None, "hasMore": has_more, "total": total}


@app.get("/ai/admin/sessions/page", response_model=ApiResponse)
def admin_sessions_page(
    keyword: str | None = None,
    cursor: int | None = None,
    limit: int = 20,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    """One page of every user's AI conversations, newest first, without their messages."""
    require_admin(authorization)
    if cursor is not None and cursor < 0:
        raise HTTPException(status_code=400, detail="cursor must not be negative")
    init_db()
    with connect() as conn:
        # Timestamps are left out of the search: every one of them contains digits, so a search for an id
        # or a user would match all sessions.
        page = admin_page(conn, "ai_chat_session", "id, user_id, title, created_at",
                          ("id", "user_id", "title"), keyword, cursor, limit)
    return ApiResponse(data=page)


@app.get("/ai/admin/chunks/page", response_model=ApiResponse)
def admin_chunks_page(
    keyword: str | None = None,
    cursor: int | None = None,
    limit: int = 20,
    authorization: str | None = Header(default=None),
) -> ApiResponse:
    """One page of indexed knowledge chunks, newest first; unlike /ai/admin/chunks it reaches every chunk."""
    require_admin(authorization)
    if cursor is not None and cursor < 0:
        raise HTTPException(status_code=400, detail="cursor must not be negative")
    init_db()
    with connect() as conn:
        page = admin_page(conn, "knowledge_chunk", "id, file_id, title, content, created_at",
                          ("id", "file_id", "title", "content"), keyword, cursor, limit)
    return ApiResponse(data=page)


@app.post("/ai/admin/index/rebuild", response_model=ApiResponse)
def rebuild_index(
    request: RebuildIndexRequest,
    authorization: str | None = Header(default=None),
    x_client_ip: str | None = Header(default=None),
) -> ApiResponse:
    claims = require_admin(authorization)
    init_db()
    created: list[dict[str, Any]] = []
    with connect() as conn:
        if request.reset:
            conn.execute("DELETE FROM knowledge_chunk")
        for document in request.documents:
            created.extend(index_document(conn, document))
    # A rebuild arrives in batches; the first one, which clears the index, stands for the whole rebuild.
    if request.reset:
        record_admin_action(claims, x_client_ip, "AI_INDEX_REBUILD", "AI_INDEX", None, "AI 知识索引",
                            "重建 AI 知识索引", {"firstBatchDocuments": len(request.documents)})
    return ApiResponse(data={"documents": len(request.documents), "chunks": len(created)})


@app.delete("/ai/admin/index/file/{file_id}", response_model=ApiResponse)
def remove_indexed_file(file_id: int, authorization: str | None = Header(default=None)) -> ApiResponse:
    require_admin(authorization)
    init_db()
    with connect() as conn:
        cursor = conn.execute("DELETE FROM knowledge_chunk WHERE file_id = ?", (file_id,))
    return ApiResponse(data={"file_id": file_id, "removed_chunks": cursor.rowcount})


class ReviewRequest(BaseModel):
    kind: str = Field(default="KNOWLEDGE", max_length=32)
    title: str = Field(default="", max_length=500)
    # As much as /ai/parse accepts: a longer document used to fail here and reach a person as "AI 审核暂不可用".
    text: str = Field(default="", max_length=2_000_000)


@app.post("/ai/internal/review", response_model=ApiResponse)
def internal_review(request: ReviewRequest, x_internal_token: str | None = Header(default=None)) -> ApiResponse:
    """Called by the knowledge and community services before content is published."""
    expected = os.getenv("PLATFORM_INTERNAL_USER_TOKEN", "ai-knowledge-local-internal")
    if not x_internal_token or not hmac.compare_digest(x_internal_token, expected):
        raise HTTPException(status_code=403, detail="internal authorization is required")
    return ApiResponse(data=review_content(request.title, request.text))


@app.post("/ai/admin/config", response_model=ApiResponse)
def save_ai_config(
    request: AiConfigRequest,
    authorization: str | None = Header(default=None),
    x_client_ip: str | None = Header(default=None),
) -> ApiResponse:
    claims = require_admin(authorization)
    before = read_ai_config()
    values = request.model_dump()
    if not is_super_admin(claims):
        # Settings are saved as one object, so an ordinary administrator's form carries these fields even when
        # it never showed them. Their stored values are put back instead of the save being refused, which keeps
        # every other setting editable while the upstream stays where the super administrator left it.
        for key in SUPER_ADMIN_ONLY_SETTINGS:
            if key in before:
                values[key] = before[key]
    values["selected_file_ids"] = sorted({int(item) for item in values.get("selected_file_ids", []) if int(item) > 0})
    for key in ("base_url", "request_url"):
        value = str(values.get(key, "")).strip()
        if value:
            try:
                validate_upstream_url(value, resolve_dns=False)
            except ValueError as error:
                raise HTTPException(status_code=400, detail=str(error)) from error
        values[key] = value
    values["review_model"] = str(values.get("review_model") or "").strip()
    problem = review_settings_problem(values)
    if problem:
        raise HTTPException(status_code=400, detail=problem)
    with connect() as conn:
        for key, value in values.items():
            stored_value = json.dumps(value, ensure_ascii=False) if key == "selected_file_ids" else str(value)
            conn.execute(
                "INSERT INTO ai_config(config_key, config_value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value, updated_at=excluded.updated_at",
                (key, stored_value, now_iso()),
            )
    after = read_ai_config()
    changes = config_changes(before, after)
    if changes:
        labels = "、".join(change["label"] for change in changes)
        record_admin_action(claims, x_client_ip, "AI_CONFIG_SAVE", "PLATFORM_CONFIG", None, "平台与 AI 设置",
                            f"修改了{labels}", {"changes": changes})
    return ApiResponse(data={"configuration": after, "updated": True})


# ---- Model provider keys (super administrators only) --------------------------------------------------------------

class ProviderKeyAddRequest(BaseModel):
    provider: str
    name: str = Field(default="", max_length=200)
    # Longer than any real key, so an over-long paste gets the page's own message rather than a bare 422.
    secret: str = Field(default="", max_length=4000)
    activate: bool = True


class ProviderKeyUpdateRequest(BaseModel):
    key_id: int = Field(alias="keyId")
    name: str | None = Field(default=None, max_length=200)
    secret: str | None = Field(default=None, max_length=4000)

    model_config = {"populate_by_name": True}


class ProviderKeyRef(BaseModel):
    key_id: int | None = Field(default=None, alias="keyId")
    provider: str | None = None

    model_config = {"populate_by_name": True}


def rule_failed(message: str) -> ApiResponse:
    """A refusal in the platform's usual shape, shown as written. (The page also reads FastAPI's `detail` now.)"""
    return ApiResponse(code=400, message=message, data={})


def provider_key_label(key: dict[str, Any]) -> str:
    return f"{key['name']}（{key['hint']}）"


def test_compatible_key(secret: str, config: dict[str, Any]) -> tuple[bool, str]:
    """Lists the upstream's models with this key: free on OpenAI-compatible services, and it proves the key."""
    base_url = str(config.get("base_url", "")).strip().rstrip("/")
    if not base_url:
        return False, "请先在「AI 与系统」中填写接口地址（base_url），才能测试这个密钥"
    url = base_url + "/models"
    try:
        validate_upstream_url(url, resolve_dns=True)
    except ValueError as error:
        return False, f"接口地址不可用：{error}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {secret}"}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=15):
            return True, "密钥有效"
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            return False, "密钥无效或没有权限"
        if error.code == 404:
            return False, "该服务没有 /models 接口，无法单独测试密钥"
        if error.code == 429:
            return True, "密钥有效，但当前请求过于频繁，已被限流"
        return False, f"服务返回错误（HTTP {error.code}）"
    except (urllib.error.URLError, TimeoutError, OSError):
        return False, "无法连接到接口地址"


def test_provider_secret(provider: str, secret: str, config: dict[str, Any]) -> tuple[bool, str]:
    if provider == "anthropic":
        return provider_keys.test_claude_key(secret, config)
    return test_compatible_key(secret, config)


@app.get("/ai/admin/provider-keys", response_model=ApiResponse)
def list_provider_keys(authorization: str | None = Header(default=None)) -> ApiResponse:
    require_super_admin(authorization)
    init_db()
    config = read_ai_config()
    with connect() as conn:
        keys = provider_keys.list_keys(conn)
        providers = [{
            "provider": provider,
            "label": provider_keys.PROVIDER_LABELS[provider],
            "environmentVariable": provider_keys.ENVIRONMENT_FALLBACK[provider],
            "inUse": provider_keys.public_resolution(provider_keys.resolve(conn, provider)),
        } for provider in provider_keys.PROVIDERS]
    problem = provider_keys.storage_problem()
    return ApiResponse(data={
        "keys": keys, "providers": providers,
        "storage": {"available": problem is None, "problem": problem},
        "configured": {"provider": config.get("provider"), "model": config.get("model")},
        "claudeDefaultModel": provider_keys.CLAUDE_DEFAULT_MODEL,
    })


@app.post("/ai/admin/provider-keys/add", response_model=ApiResponse)
def add_provider_key(request: ProviderKeyAddRequest, authorization: str | None = Header(default=None),
                     x_client_ip: str | None = Header(default=None)) -> ApiResponse:
    claims = require_super_admin(authorization)
    init_db()
    problem = (provider_keys.name_problem(request.name)
               or provider_keys.secret_problem(request.provider, request.secret)
               or provider_keys.storage_problem())
    if problem:
        return rule_failed(problem)
    with connect() as conn:
        key = provider_keys.add_key(conn, request.provider, request.name, request.secret, int(claims["uid"]),
                                    request.activate)
    label = provider_keys.PROVIDER_LABELS[key["provider"]]
    # The secret is recorded nowhere: not here, not in the log line, not in the detail.
    record_admin_action(claims, x_client_ip, "AI_PROVIDER_KEY_ADD", "AI_PROVIDER_KEY", str(key["id"]),
                        provider_key_label(key), f"添加{label}模型密钥「{key['name']}」" + ("并启用" if key["active"] else ""),
                        {"provider": key["provider"], "active": key["active"]})
    return ApiResponse(data={"key": key})


@app.post("/ai/admin/provider-keys/update", response_model=ApiResponse)
def update_provider_key(request: ProviderKeyUpdateRequest, authorization: str | None = Header(default=None),
                        x_client_ip: str | None = Header(default=None)) -> ApiResponse:
    claims = require_super_admin(authorization)
    init_db()
    with connect() as conn:
        current = provider_keys.find_key(conn, request.key_id)
    if current is None:
        return rule_failed("模型密钥不存在")
    changes: list[dict[str, Any]] = []
    name = request.name.strip() if request.name is not None else None
    secret = request.secret.strip() if request.secret else ""
    if name is not None and name != current["name"]:
        problem = provider_keys.name_problem(name)
        if problem:
            return rule_failed(problem)
    if secret:
        problem = provider_keys.secret_problem(current["provider"], secret) or provider_keys.storage_problem()
        if problem:
            return rule_failed(problem)
    with connect() as conn:
        if name is not None and name != current["name"]:
            provider_keys.rename_key(conn, current["id"], name)
            changes.append({"field": "name", "label": "名称", "before": current["name"], "after": name})
        if secret:
            provider_keys.replace_secret(conn, current["id"], secret)
            changes.append({"field": "secret", "label": "密钥", "hidden": True})
        updated = provider_keys.find_key(conn, current["id"])
    if changes:
        labels = "、".join(change["label"] for change in changes)
        record_admin_action(claims, x_client_ip, "AI_PROVIDER_KEY_UPDATE", "AI_PROVIDER_KEY", str(updated["id"]),
                            provider_key_label(updated), f"修改模型密钥「{updated['name']}」的{labels}", {"changes": changes})
    return ApiResponse(data={"key": updated})


def _switch(request: ProviderKeyRef, authorization: str | None, x_client_ip: str | None, activate: bool) -> ApiResponse:
    claims = require_super_admin(authorization)
    init_db()
    if request.key_id is None:
        return rule_failed("模型密钥不存在")
    with connect() as conn:
        current = provider_keys.find_key(conn, request.key_id)
        if current is None:
            return rule_failed("模型密钥不存在")
        previous = provider_keys.resolve(conn, current["provider"]) if activate else None
        key = (provider_keys.activate_key if activate else provider_keys.deactivate_key)(conn, current["id"])
    label = provider_keys.PROVIDER_LABELS[key["provider"]]
    if activate:
        replaced = previous.get("name") if previous and previous.get("keyId") not in (None, key["id"]) else None
        record_admin_action(claims, x_client_ip, "AI_PROVIDER_KEY_ACTIVATE", "AI_PROVIDER_KEY", str(key["id"]),
                            provider_key_label(key), f"启用{label}模型密钥「{key['name']}」"
                            + (f"，替换「{replaced}」" if replaced else ""), {"provider": key["provider"]})
    else:
        record_admin_action(claims, x_client_ip, "AI_PROVIDER_KEY_DEACTIVATE", "AI_PROVIDER_KEY", str(key["id"]),
                            provider_key_label(key), f"停用{label}模型密钥「{key['name']}」", {"provider": key["provider"]})
    return ApiResponse(data={"key": key})


@app.post("/ai/admin/provider-keys/activate", response_model=ApiResponse)
def activate_provider_key(request: ProviderKeyRef, authorization: str | None = Header(default=None),
                          x_client_ip: str | None = Header(default=None)) -> ApiResponse:
    return _switch(request, authorization, x_client_ip, True)


@app.post("/ai/admin/provider-keys/deactivate", response_model=ApiResponse)
def deactivate_provider_key(request: ProviderKeyRef, authorization: str | None = Header(default=None),
                            x_client_ip: str | None = Header(default=None)) -> ApiResponse:
    return _switch(request, authorization, x_client_ip, False)


@app.post("/ai/admin/provider-keys/delete", response_model=ApiResponse)
def delete_provider_key(request: ProviderKeyRef, authorization: str | None = Header(default=None),
                        x_client_ip: str | None = Header(default=None)) -> ApiResponse:
    claims = require_super_admin(authorization)
    init_db()
    if request.key_id is None:
        return rule_failed("模型密钥不存在")
    with connect() as conn:
        removed = provider_keys.delete_key(conn, request.key_id)
    if removed is None:
        return rule_failed("模型密钥不存在")
    record_admin_action(claims, x_client_ip, "AI_PROVIDER_KEY_DELETE", "AI_PROVIDER_KEY", str(removed["id"]),
                        provider_key_label(removed), f"删除模型密钥「{removed['name']}」"
                        + ("（删除前正在使用）" if removed["active"] else ""),
                        {"provider": removed["provider"], "wasActive": removed["active"]})
    return ApiResponse(data={"key": removed, "removed": True})


@app.post("/ai/admin/provider-keys/test", response_model=ApiResponse)
def test_provider_key(request: ProviderKeyRef, authorization: str | None = Header(default=None)) -> ApiResponse:
    """Tests a stored key, or with just a provider, the key that provider would use now (including one from the
    environment). Nothing is changed but the stored key's last-test record, so this is not audited."""
    require_super_admin(authorization)
    init_db()
    config = read_ai_config()
    with connect() as conn:
        if request.key_id is not None:
            current = provider_keys.find_key(conn, request.key_id)
            if current is None:
                return rule_failed("模型密钥不存在")
            provider, secret = current["provider"], provider_keys.stored_secret(conn, current["id"])
        elif request.provider in provider_keys.PROVIDERS:
            provider = request.provider
            secret = provider_keys.resolve(conn, provider)["secret"]
        else:
            return rule_failed("请指定要测试的密钥")
    if not secret:
        ok, message = False, "密钥无法读取：没有可用的密钥，或 AI_KEY_ENCRYPTION_KEY 已更换"
    else:
        ok, message = test_provider_secret(provider, secret, config)
    with connect() as conn:
        if request.key_id is not None:
            provider_keys.record_test(conn, request.key_id, ok, message)
        key = provider_keys.find_key(conn, request.key_id) if request.key_id is not None else None
    return ApiResponse(data={"ok": ok, "message": message, "key": key})


@app.post("/ai/chat", response_model=ApiResponse)
def chat(request: ChatRequest, authorization: str | None = Header(default=None)) -> ApiResponse:
    claims = require_user(authorization)
    user_id = int(claims["uid"])
    init_db()
    config = read_ai_config()
    if not config.get("ai_chat_enabled", True):
        raise HTTPException(status_code=403, detail="AI 问答功能当前未开放")
    session_id = ensure_session(request, user_id)
    user_message = save_message(session_id, "user", request.question)
    public_answer = public_platform_answer(request.question)
    allowed_file_ids = None
    if config.get("data_source_scope") == "admin-selected":
        allowed_file_ids = {int(item) for item in config.get("selected_file_ids", []) if int(item) > 0}
    matched = [] if public_answer else retrieve_chunks(
        request.question,
        limit=int(config["match_limit"]),
        allowed_file_ids=allowed_file_ids,
    )
    answer = public_answer
    if not answer:
        answer = model_answer(request.question, matched, config)
    if not answer:
        answer = local_answer(request.question, matched)
    answer = sanitize_answer(answer)
    if config.get("compliance_rule") == "concise" and len(answer) > 800:
        answer = answer[:800].rsplit("。", 1)[0] + "。"
    if config.get("compliance_rule") == "answer-with-references" and matched:
        titles = "、".join(dict.fromkeys(str(item.get("title", "资料")) for item in matched[:3]))
        if titles and "参考资料" not in answer:
            answer = f"{answer}\n\n参考资料：{titles}"
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
