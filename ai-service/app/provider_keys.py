"""
The keys ai-service uses to call its model provider, and the calls it makes to Claude with them.

Super administrators manage the keys; nobody else can read or change them. They are stored encrypted with a
key derived from AI_KEY_ENCRYPTION_KEY and are never returned, logged or audited again after they are entered —
the page sees a hint such as ``sk-ant-api03-…Ab12`` and nothing more.

Each provider has at most one active key, switched by hand. A provider with no active stored key falls back to
its environment variable (AI_API_KEY for an OpenAI-compatible upstream, ANTHROPIC_API_KEY for Claude), so a
deployment configured the old way keeps working. A stored key that is active but cannot be decrypted does not
fall back: spending would quietly move to a key nobody chose, so the model is treated as unavailable instead,
which sends chat to the local answer and review to a person.

Claude is called through Anthropic's SDK. Current models reject sampling parameters, so the configured
temperature is not sent; declined requests are retried server-side on the model Anthropic recommends
(``fallbacks: "default"``) for the models that support it; and a refusal is never mistaken for an answer.
"""
from __future__ import annotations

import base64
import hashlib
import os
import re
import sqlite3
from datetime import datetime, timezone
from typing import Any, Callable

PROVIDERS = ("anthropic", "openai-compatible")
PROVIDER_LABELS = {"anthropic": "Anthropic Claude", "openai-compatible": "OpenAI 兼容接口"}
ENVIRONMENT_FALLBACK = {"anthropic": "ANTHROPIC_API_KEY", "openai-compatible": "AI_API_KEY"}
DEVELOPMENT_ENCRYPTION_KEY = "ai-knowledge-local-provider-key-encryption"
NAME_MAX = 64

CLAUDE_DEFAULT_MODEL = "claude-opus-5"
# Server-side refusal fallback is offered on these; elsewhere the parameter is rejected.
FALLBACK_MODELS = frozenset({"claude-opus-5", "claude-fable-5-1"})
FALLBACK_BETA = "server-side-fallback-2026-07-01"
# Models that accept output_config.effort; others reject it, so it is only sent to these.
EFFORT_MODELS = frozenset({
    "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-fable-5", "claude-opus-4-8", "claude-opus-4-7",
    "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6",
})
CHAT_MAX_TOKENS = 16000
# A verdict is a line of JSON, but thinking shares this budget; too little and the answer never arrives.
REVIEW_MAX_TOKENS = 2048

_KNOWN_PREFIX = re.compile(r"^(sk-ant-[a-z]+\d*-|sk-proj-|sk-)")


class StorageUnavailable(Exception):
    """Keys cannot be stored here: no encryption key is configured outside development."""


# ---- Encryption --------------------------------------------------------------------------------------------------

def _development_allowed() -> bool:
    return os.getenv("AI_ALLOW_DEFAULT_SECRETS", "").strip().lower() in {"1", "true", "yes", "on"}


def storage_problem() -> str | None:
    """Why keys cannot be stored on this deployment, or None when they can."""
    if os.getenv("AI_KEY_ENCRYPTION_KEY", "").strip() or _development_allowed():
        return None
    return ("未配置 AI_KEY_ENCRYPTION_KEY，无法在平台中保存模型密钥。请在部署环境中设置它（一段足够长的随机字符串）"
            "后重启 AI 服务；在此之前仍使用环境变量中的密钥。")


def _cipher():
    from cryptography.fernet import Fernet

    configured = os.getenv("AI_KEY_ENCRYPTION_KEY", "").strip()
    if not configured:
        if not _development_allowed():
            raise StorageUnavailable(storage_problem())
        configured = DEVELOPMENT_ENCRYPTION_KEY
    # Any operator-chosen string becomes a valid Fernet key; it should be long and random, like the others.
    return Fernet(base64.urlsafe_b64encode(hashlib.sha256(configured.encode("utf-8")).digest()))


def encrypt(secret: str) -> str:
    return _cipher().encrypt(secret.encode("utf-8")).decode("ascii")


def decrypt(ciphertext: str) -> str | None:
    """The stored secret, or None when it cannot be read with the encryption key this deployment has now."""
    from cryptography.fernet import InvalidToken

    try:
        return _cipher().decrypt(ciphertext.encode("ascii")).decode("utf-8")
    except (InvalidToken, StorageUnavailable, ValueError):
        return None


# ---- What a key looks like ---------------------------------------------------------------------------------------

def hint(secret: str) -> str:
    """Enough to recognise a key by: its well-known prefix, if it has one, and its last four characters."""
    value = secret.strip()
    prefix = _KNOWN_PREFIX.match(value)
    lead = prefix.group(1) if prefix else ""
    return f"{lead}…{value[-4:]}" if len(value) > len(lead) + 8 else "…" + value[-4:]


def secret_problem(provider: str, secret: str) -> str | None:
    """What is wrong with a key someone is about to store, in the words the page shows."""
    if provider not in PROVIDERS:
        return "未知的模型服务"
    value = secret.strip()
    if not value:
        return "请填写密钥"
    if any(char.isspace() for char in value):
        return "密钥中不能包含空格或换行"
    if len(value) > 500:
        return "密钥过长"
    if provider == "anthropic":
        if value.startswith("sk-ant-admin"):
            return "这是 Anthropic 的管理密钥，不能用来调用模型；请使用普通 API 密钥（sk-ant-api…）"
        if not value.startswith("sk-ant-"):
            return "Anthropic 的 API 密钥以 sk-ant- 开头"
        if len(value) < 40:
            return "密钥长度不对，请确认复制完整"
    elif len(value) < 8:
        return "密钥过短，请确认复制完整"
    return None


def name_problem(name: str) -> str | None:
    value = name.strip()
    if not value:
        return "请填写密钥名称"
    if len(value) > NAME_MAX:
        return f"名称不能超过 {NAME_MAX} 个字符"
    return None


# ---- Storage -----------------------------------------------------------------------------------------------------

def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _view(row: sqlite3.Row) -> dict[str, Any]:
    """A stored key as the page may see it. The ciphertext never leaves this module."""
    return {
        "id": row["id"], "provider": row["provider"], "name": row["name"], "hint": row["hint"],
        "active": bool(row["active"]), "createdBy": row["created_by"], "createdAt": row["created_at"],
        "updatedAt": row["updated_at"], "lastTestedAt": row["last_tested_at"],
        "lastTestOk": None if row["last_test_ok"] is None else bool(row["last_test_ok"]),
        "lastTestMessage": row["last_test_message"],
    }


def list_keys(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = conn.execute("SELECT * FROM ai_provider_key ORDER BY provider, active DESC, id DESC").fetchall()
    return [_view(row) for row in rows]


def find_key(conn: sqlite3.Connection, key_id: int) -> dict[str, Any] | None:
    row = conn.execute("SELECT * FROM ai_provider_key WHERE id = ?", (key_id,)).fetchone()
    return _view(row) if row else None


def add_key(conn: sqlite3.Connection, provider: str, name: str, secret: str, created_by: int | None,
            activate: bool) -> dict[str, Any]:
    now = _now()
    ciphertext = encrypt(secret.strip())   # raises StorageUnavailable before anything is written
    if activate:
        conn.execute("UPDATE ai_provider_key SET active = 0, updated_at = ? WHERE provider = ? AND active = 1",
                     (now, provider))
    cursor = conn.execute(
        "INSERT INTO ai_provider_key (provider, name, ciphertext, hint, active, created_by, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (provider, name.strip(), ciphertext, hint(secret), 1 if activate else 0, created_by, now, now))
    return find_key(conn, cursor.lastrowid)


def rename_key(conn: sqlite3.Connection, key_id: int, name: str) -> dict[str, Any] | None:
    conn.execute("UPDATE ai_provider_key SET name = ?, updated_at = ? WHERE id = ?", (name.strip(), _now(), key_id))
    return find_key(conn, key_id)


def replace_secret(conn: sqlite3.Connection, key_id: int, secret: str) -> dict[str, Any] | None:
    """A new secret for the same entry — what an administrator does after rotating the key at the provider."""
    ciphertext = encrypt(secret.strip())
    # The last test described the old secret, so it no longer says anything.
    conn.execute("UPDATE ai_provider_key SET ciphertext = ?, hint = ?, last_tested_at = NULL, last_test_ok = NULL, "
                 "last_test_message = NULL, updated_at = ? WHERE id = ?",
                 (ciphertext, hint(secret), _now(), key_id))
    return find_key(conn, key_id)


def activate_key(conn: sqlite3.Connection, key_id: int) -> dict[str, Any] | None:
    current = find_key(conn, key_id)
    if current is None:
        return None
    now = _now()
    # One statement per step inside the caller's transaction; the partial unique index makes a second active key
    # for the same provider impossible even if two requests race.
    conn.execute("UPDATE ai_provider_key SET active = 0, updated_at = ? WHERE provider = ? AND active = 1 AND id <> ?",
                 (now, current["provider"], key_id))
    conn.execute("UPDATE ai_provider_key SET active = 1, updated_at = ? WHERE id = ?", (now, key_id))
    return find_key(conn, key_id)


def deactivate_key(conn: sqlite3.Connection, key_id: int) -> dict[str, Any] | None:
    conn.execute("UPDATE ai_provider_key SET active = 0, updated_at = ? WHERE id = ?", (_now(), key_id))
    return find_key(conn, key_id)


def delete_key(conn: sqlite3.Connection, key_id: int) -> dict[str, Any] | None:
    """Removes the secret entirely; the admin log keeps who removed which key and when."""
    current = find_key(conn, key_id)
    if current is not None:
        conn.execute("DELETE FROM ai_provider_key WHERE id = ?", (key_id,))
    return current


def record_test(conn: sqlite3.Connection, key_id: int, ok: bool, message: str) -> None:
    conn.execute("UPDATE ai_provider_key SET last_tested_at = ?, last_test_ok = ?, last_test_message = ? WHERE id = ?",
                 (_now(), 1 if ok else 0, message[:300], key_id))


def stored_secret(conn: sqlite3.Connection, key_id: int) -> str | None:
    row = conn.execute("SELECT ciphertext FROM ai_provider_key WHERE id = ?", (key_id,)).fetchone()
    return decrypt(row["ciphertext"]) if row else None


def resolve(conn: sqlite3.Connection, provider: str) -> dict[str, Any]:
    """
    The key a call to this provider will use, and where it came from.

    source is ``stored`` (an active key from the page), ``environment`` (the provider's variable),
    ``undecryptable`` (an active stored key this deployment can no longer read) or ``none``.
    """
    row = conn.execute("SELECT * FROM ai_provider_key WHERE provider = ? AND active = 1", (provider,)).fetchone()
    if row is not None:
        secret = decrypt(row["ciphertext"])
        if secret is None:
            return {"secret": None, "source": "undecryptable", "keyId": row["id"], "name": row["name"],
                    "hint": row["hint"]}
        return {"secret": secret, "source": "stored", "keyId": row["id"], "name": row["name"], "hint": row["hint"]}
    variable = ENVIRONMENT_FALLBACK.get(provider, "")
    environment = os.getenv(variable, "").strip() if variable else ""
    if environment:
        return {"secret": environment, "source": "environment", "keyId": None, "name": variable,
                "hint": hint(environment)}
    return {"secret": None, "source": "none", "keyId": None, "name": None, "hint": None}


def public_resolution(resolution: dict[str, Any]) -> dict[str, Any]:
    """The same answer without the secret, for the page."""
    return {key: value for key, value in resolution.items() if key != "secret"}


# ---- Calling Claude ----------------------------------------------------------------------------------------------

def claude_model(config: dict[str, Any]) -> str:
    model = str(config.get("model") or "").strip()
    return model if model.startswith("claude-") else CLAUDE_DEFAULT_MODEL


def make_claude_client(secret: str, timeout: float, max_retries: int):
    """
    Always Anthropic's own API. The base URL setting belongs to the OpenAI-compatible provider; reusing it here
    would send Claude requests to whatever server that setting last pointed at. An operator who needs a proxy
    sets ANTHROPIC_BASE_URL, which the SDK reads by itself.
    """
    import anthropic

    return anthropic.Anthropic(api_key=secret, timeout=timeout, max_retries=max_retries)


def claude_text(client: Any, model: str, system: str, user: str, max_tokens: int,
                effort: str | None = None) -> tuple[str | None, str | None]:
    """
    One request, one answer: (text, None), or (None, why not). Exceptions from the SDK propagate — the caller
    decides what a failure means where it is.
    """
    request: dict[str, Any] = {
        "model": model, "max_tokens": max_tokens, "system": system,
        "messages": [{"role": "user", "content": user}],
    }
    if effort and model in EFFORT_MODELS:
        request["output_config"] = {"effort": effort}
    if model in FALLBACK_MODELS:
        response = client.beta.messages.create(betas=[FALLBACK_BETA], fallbacks="default", **request)
    else:
        response = client.messages.create(**request)

    if getattr(response, "stop_reason", None) == "refusal":
        details = getattr(response, "stop_details", None)
        category = getattr(details, "category", None) if details is not None else None
        return None, f"模型拒绝回答（{category or '未说明类别'}）"
    # Thinking, fallback and other blocks carry no answer; only text does.
    text = "".join(getattr(block, "text", "") for block in response.content
                   if getattr(block, "type", None) == "text").strip()
    if not text:
        return None, f"模型没有给出文字回答（{getattr(response, 'stop_reason', None)}）"
    return text, None


def test_claude_key(secret: str, config: dict[str, Any],
                    client_factory: Callable[..., Any] | None = None) -> tuple[bool, str]:
    """
    Asks Anthropic about the configured model with this key. Looking a model up is free — no tokens are
    spent — and it proves both that the key is accepted and that the model name is right.
    """
    import anthropic

    model = claude_model(config)
    client = (client_factory or make_claude_client)(secret, 15.0, 0)
    try:
        client.models.retrieve(model)
        return True, f"密钥有效，可以使用 {model}"
    except anthropic.AuthenticationError:
        return False, "密钥无效或已被撤销"
    except anthropic.PermissionDeniedError:
        return False, "密钥没有访问该模型的权限"
    except anthropic.NotFoundError:
        return True, f"密钥有效，但找不到模型 {model}，请检查「AI 与系统」中的模型名称"
    except anthropic.RateLimitError:
        return True, "密钥有效，但当前请求过于频繁，已被限流"
    except anthropic.APITimeoutError:
        return False, "连接 Anthropic 超时"
    except anthropic.APIConnectionError:
        return False, "无法连接到 Anthropic，请检查服务器网络"
    except anthropic.APIStatusError as error:
        return False, f"Anthropic 返回错误（HTTP {error.status_code}）"
