import sqlite3
import tempfile
import unittest
from pathlib import Path

from app import migrations


class MigrationRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        self.conn = sqlite3.connect(Path(self.tmpdir.name) / "ai.db")

    def tearDown(self) -> None:
        self.conn.close()
        self.tmpdir.cleanup()

    def tables(self) -> set[str]:
        return {row[0] for row in self.conn.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')")}

    def test_an_empty_database_gets_every_version_once(self) -> None:
        self.assertEqual([1, 2], migrations.migrate(self.conn))
        self.assertTrue({"knowledge_chunk", "ai_chat_session", "ai_chat_message", "ai_config",
                         "idx_ai_chat_session_user", "idx_ai_chat_message_session"} <= self.tables())
        self.assertEqual([], migrations.migrate(self.conn))
        self.assertEqual([1, 2], migrations.applied_versions(self.conn))

    def test_a_database_from_before_the_runner_is_completed_and_keeps_its_rows(self) -> None:
        self.conn.executescript(
            """
            CREATE TABLE knowledge_chunk (id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL);
            CREATE TABLE ai_chat_session (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, title TEXT NOT NULL,
                created_at TEXT NOT NULL);
            INSERT INTO knowledge_chunk (file_id, title, content, created_at) VALUES (7, 'kept', 'body', '2026-01-01');
            INSERT INTO ai_chat_session (user_id, title, created_at) VALUES (1, 'old chat', '2026-01-01');
            """
        )
        self.assertEqual([1, 2], migrations.migrate(self.conn))
        columns = {row[1] for row in self.conn.execute("PRAGMA table_info(knowledge_chunk)")}
        self.assertIn("embedding", columns)
        self.assertEqual(("kept", None), self.conn.execute("SELECT title, embedding FROM knowledge_chunk").fetchone())
        self.assertEqual("old chat", self.conn.execute("SELECT title FROM ai_chat_session").fetchone()[0])
        self.assertIn("ai_chat_message", self.tables())

    def test_a_failing_step_leaves_nothing_behind(self) -> None:
        def broken(conn: sqlite3.Connection) -> None:
            conn.execute("CREATE TABLE half_done (id INTEGER)")
            raise ValueError("boom")

        steps = migrations.MIGRATIONS + [(3, "broken", broken)]
        with self.assertRaises(ValueError):
            migrations.migrate(self.conn, steps)
        self.assertEqual([1, 2], migrations.applied_versions(self.conn))
        self.assertNotIn("half_done", self.tables())

        # Once fixed, the step applies on the next start.
        fixed = migrations.MIGRATIONS + [(3, "fixed", lambda conn: conn.execute("CREATE TABLE half_done (id INTEGER)"))]
        self.assertEqual([3], migrations.migrate(self.conn, fixed))
        self.assertIn("half_done", self.tables())

    def test_a_database_from_a_newer_release_is_refused(self) -> None:
        migrations.migrate(self.conn)
        self.conn.execute("INSERT INTO schema_migrations(version, description, applied_at) VALUES (9, 'future', 'now')")
        self.conn.commit()
        with self.assertRaises(RuntimeError):
            migrations.migrate(self.conn)


if __name__ == "__main__":
    unittest.main()
