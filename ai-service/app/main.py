from __future__ import annotations

import hashlib
import os
import sqlite3
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from fastapi import FastAPI
from pydantic import BaseModel, Field


APP_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = APP_DIR.parent / "data" / "ai_service.db"

class ApiResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: dict[str, Any]


class TextRequest(BaseModel):
    text: str = Field(..., min_length=1)
    file_id: int | None = None
    title: str | None = None


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1)
    user_id: int | None = None
    session_id: int | None = None


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
            """
        )


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    yield


app = FastAPI(title="AI Knowledge Platform AI Service", version="0.2.0", lifespan=lifespan)


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


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
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT id, file_id, title, content, created_at
            FROM knowledge_chunk
            ORDER BY id DESC
            LIMIT 200
            """
        ).fetchall()

    scored = [(score_chunk(question, row), row) for row in rows]
    matched = [
        row
        for score, row in sorted(
            scored,
            key=lambda item: (item[0], item[1]["id"]),
            reverse=True,
        )
        if score > 0
    ]
    if not matched:
        matched = rows[:limit]
    return [row_to_dict(row) for row in matched[:limit]]


def ensure_session(request: ChatRequest) -> int:
    if request.session_id:
        with connect() as conn:
            existing = conn.execute(
                "SELECT id FROM ai_chat_session WHERE id = ?",
                (request.session_id,),
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
            (request.user_id, title, now_iso()),
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
        }
    )


@app.post("/ai/parse", response_model=ApiResponse)
def parse_document(request: TextRequest) -> ApiResponse:
    init_db()
    chunks = split_text(request.text)
    title = request.title or "本地解析文档"
    created_at = now_iso()
    created: list[dict[str, Any]] = []

    with connect() as conn:
        for chunk in chunks:
            cursor = conn.execute(
                """
                INSERT INTO knowledge_chunk(file_id, title, content, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (request.file_id or 0, title, chunk, created_at),
            )
            created.append(
                {
                    "id": int(cursor.lastrowid),
                    "file_id": request.file_id or 0,
                    "title": title,
                    "content": chunk,
                    "created_at": created_at,
                }
            )

    return ApiResponse(data={"chunks": created, "count": len(created)})


@app.post("/ai/embedding", response_model=ApiResponse)
def embedding(request: TextRequest) -> ApiResponse:
    digest = hashlib.sha256(request.text.encode("utf-8")).digest()
    vector = [round(byte / 255, 4) for byte in digest[:32]]
    return ApiResponse(data={"dimension": len(vector), "vector": vector})


@app.post("/ai/retrieve", response_model=ApiResponse)
def retrieve(request: ChatRequest) -> ApiResponse:
    init_db()
    return ApiResponse(data={"matches": retrieve_chunks(request.question)})


@app.post("/ai/chat", response_model=ApiResponse)
def chat(request: ChatRequest) -> ApiResponse:
    init_db()
    session_id = ensure_session(request)
    user_message = save_message(session_id, "user", request.question)
    matched = retrieve_chunks(request.question, limit=5)

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
