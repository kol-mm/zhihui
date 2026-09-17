"""Versioned schema changes for the AI service's SQLite database.

Each migration runs once, in its own transaction, and is recorded in ``schema_migrations``. Version 1 is the
schema as it stood before this runner existed, written so it also completes a database created by older releases.
Add new steps at the end with the next version number; never edit a step that has shipped.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from typing import Callable

Migration = tuple[int, str, Callable[[sqlite3.Connection], None]]


def _columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}


def _baseline(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS knowledge_chunk (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            file_id INTEGER NOT NULL DEFAULT 0,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            embedding TEXT,
            created_at TEXT NOT NULL
        )
        """
    )
    # Databases from before semantic search have no embedding column.
    if "embedding" not in _columns(conn, "knowledge_chunk"):
        conn.execute("ALTER TABLE knowledge_chunk ADD COLUMN embedding TEXT")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_file_id ON knowledge_chunk(file_id)")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS ai_chat_session (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            title TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS ai_chat_message (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(session_id) REFERENCES ai_chat_session(id)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS ai_config (
            config_key TEXT PRIMARY KEY,
            config_value TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )


def _history_indexes(conn: sqlite3.Connection) -> None:
    # A member's session list (newest first) and a session's messages (in order) no longer scan whole tables.
    conn.execute("CREATE INDEX IF NOT EXISTS idx_ai_chat_session_user ON ai_chat_session(user_id, id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session ON ai_chat_message(session_id, id)")


MIGRATIONS: list[Migration] = [
    (1, "baseline", _baseline),
    (2, "chat history indexes", _history_indexes),
]


def applied_versions(conn: sqlite3.Connection) -> list[int]:
    exists = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'"
    ).fetchone()
    if not exists:
        return []
    return [row[0] for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version")]


def migrate(conn: sqlite3.Connection, migrations: list[Migration] | None = None) -> list[int]:
    """Applies pending migrations in order and returns the versions it applied.

    A database that records a version this code does not know was written by a newer release; starting on it
    could damage data, so that is refused.
    """
    steps = sorted(migrations if migrations is not None else MIGRATIONS, key=lambda step: step[0])
    if conn.in_transaction:
        conn.commit()
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            description TEXT NOT NULL,
            applied_at TEXT NOT NULL
        )
        """
    )
    conn.commit()
    known = {version for version, _, _ in steps}
    unknown = [version for version in applied_versions(conn) if version not in known]
    if unknown:
        raise RuntimeError(f"database has schema versions {unknown} that this release does not know; refusing to start")
    done = set(applied_versions(conn))
    newly_applied: list[int] = []
    for version, description, step in steps:
        if version in done:
            continue
        # BEGIN IMMEDIATE takes the write lock up front, so two processes cannot apply the same step.
        conn.execute("BEGIN IMMEDIATE")
        try:
            if conn.execute("SELECT 1 FROM schema_migrations WHERE version = ?", (version,)).fetchone():
                conn.rollback()
                continue
            step(conn)
            conn.execute(
                "INSERT INTO schema_migrations(version, description, applied_at) VALUES (?, ?, ?)",
                (version, description, datetime.now(timezone.utc).isoformat()),
            )
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        newly_applied.append(version)
    return newly_applied
