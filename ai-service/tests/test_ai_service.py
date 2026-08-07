import os
import math
import tempfile
import unittest
import base64
import hashlib
import hmac
import json
import time
from unittest import mock


class AiServicePersistenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "ai_service_test.db")

        from app import main

        self.main = main
        self.main.init_db()
        self.user_auth = self.issue_token("demo", 1, "USER")

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)
        os.environ.pop("AI_API_KEY", None)

    @staticmethod
    def issue_token(username: str, user_id: int, role: str) -> str:
        def segment(value: dict) -> str:
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

        header = segment({"alg": "HS256", "typ": "JWT"})
        payload = segment({"sub": username, "uid": user_id, "role": role, "iat": int(time.time()), "exp": int(time.time()) + 600})
        signing_input = f"{header}.{payload}"
        signature = base64.urlsafe_b64encode(hmac.new(
            b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256
        ).digest()).decode().rstrip("=")
        return f"Bearer {signing_input}.{signature}"

    def test_parse_retrieve_and_chat_are_persisted(self) -> None:
        parse_response = self.main.parse_document(
            self.main.TextRequest(
                file_id=101,
                title="平台功能清单",
                text="知识库支持上传和检索\n广场展示关注用户动态",
            ),
            authorization=self.user_auth,
        )
        self.assertEqual(parse_response.code, 0)
        self.assertEqual(parse_response.data["count"], 2)

        retrieve_response = self.main.retrieve(
            self.main.ChatRequest(question="知识库检索", user_id=999),
            authorization=self.user_auth,
        )
        self.assertEqual(retrieve_response.code, 0)
        self.assertGreaterEqual(len(retrieve_response.data["matches"]), 1)

        embedding_response = self.main.embedding(
            self.main.TextRequest(text="知识库向量检索"),
            authorization=self.user_auth,
        )
        self.assertEqual(embedding_response.data["dimension"], 128)
        vector = embedding_response.data["vector"]
        self.assertAlmostEqual(math.sqrt(sum(value * value for value in vector)), 1.0, places=4)

        vector_status = self.main.vector_status()
        self.assertEqual(vector_status.data["mode"], "local")
        self.assertEqual(vector_status.data["indexed_chunks"], 2)
        self.assertFalse(vector_status.data["external_ready"])

        chat_response = self.main.chat(
            self.main.ChatRequest(question="知识库检索怎么做", user_id=999),
            authorization=self.user_auth,
        )
        self.assertEqual(chat_response.code, 0)
        self.assertIn("session_id", chat_response.data)
        self.assertEqual(len(chat_response.data["messages"]), 2)
        self.assertGreaterEqual(len(chat_response.data["references"]), 1)

        health_response = self.main.health()
        self.assertEqual(health_response.data["chunk_count"], 2)
        self.assertEqual(health_response.data["session_count"], 1)
        self.assertEqual(health_response.data["vector_dimension"], 128)

        history = self.main.chat_history(user_id=1, authorization=self.user_auth)
        self.assertEqual(len(history.data["sessions"]), 1)
        self.assertEqual(len(history.data["messages"]), 2)

    def test_user_can_rename_and_delete_own_chat_session(self) -> None:
        created = self.main.chat(
            self.main.ChatRequest(question="session lifecycle"), authorization=self.user_auth
        )
        session_id = created.data["session_id"]

        renamed = self.main.rename_chat_session(
            session_id, self.main.SessionTitleRequest(title="  检索方案讨论  "), authorization=self.user_auth
        )
        self.assertTrue(renamed.data["renamed"])
        history = self.main.chat_history(user_id=1, authorization=self.user_auth)
        self.assertEqual(history.data["sessions"][0]["title"], "检索方案讨论")

        other_auth = self.issue_token("other", 8, "USER")
        with self.assertRaises(self.main.HTTPException) as denied:
            self.main.delete_chat_session(session_id, authorization=other_auth)
        self.assertEqual(denied.exception.status_code, 403)

        deleted = self.main.delete_chat_session(session_id, authorization=self.user_auth)
        self.assertTrue(deleted.data["removed"])
        self.assertEqual(deleted.data["removed_messages"], 2)
        history = self.main.chat_history(user_id=1, authorization=self.user_auth)
        self.assertEqual(history.data["sessions"], [])
        self.assertEqual(history.data["messages"], [])

    def test_admin_can_manage_ai_configuration(self) -> None:
        auth = self.issue_token("admin", 2, "ADMIN")

        saved = self.main.save_ai_config(self.main.AiConfigRequest(
            match_limit=3, request_url="https://example.test/v1/chat/completions"
        ), authorization=auth)
        self.assertEqual(saved.data["configuration"]["match_limit"], 3)
        self.assertEqual(saved.data["configuration"]["request_url"], "https://example.test/v1/chat/completions")
        overview = self.main.ai_admin_overview(authorization=auth)
        self.assertIn("configuration", overview.data)

    def test_temperature_is_limited_to_one(self) -> None:
        with self.assertRaises(ValueError):
            self.main.AiConfigRequest(temperature=1.1)

    def test_reindex_replaces_old_file_chunks(self) -> None:
        first = self.main.parse_document(
            self.main.TextRequest(file_id=8, title="Old", text="first line\nsecond line"),
            authorization=self.user_auth,
        )
        self.assertEqual(first.data["count"], 2)
        second = self.main.parse_document(
            self.main.TextRequest(file_id=8, title="New", text="replacement"),
            authorization=self.user_auth,
        )
        self.assertEqual(second.data["count"], 1)
        self.assertEqual(self.main.vector_status().data["indexed_chunks"], 1)

    def test_admin_can_rebuild_and_remove_index(self) -> None:
        auth = self.issue_token("admin", 2, "ADMIN")
        rebuilt = self.main.rebuild_index(self.main.RebuildIndexRequest(documents=[
            self.main.TextRequest(file_id=10, title="One", text="knowledge one"),
            self.main.TextRequest(file_id=11, title="Two", text="knowledge two"),
        ]), authorization=auth)
        self.assertEqual(rebuilt.data["documents"], 2)
        self.assertEqual(rebuilt.data["chunks"], 2)
        removed = self.main.remove_indexed_file(10, authorization=auth)
        self.assertEqual(removed.data["removed_chunks"], 1)

    def test_openai_compatible_provider_uses_chat_completions(self) -> None:
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            @staticmethod
            def read() -> bytes:
                return json.dumps({"choices": [{"message": {"content": "provider answer"}}]}).encode()

        os.environ["AI_API_KEY"] = "test-key"
        config = {
            "provider": "openai-compatible",
            "model": "test-model",
            "base_url": "https://example.test/v1",
            "temperature": 0.3,
        }
        with mock.patch.object(self.main.urllib.request, "urlopen", return_value=FakeResponse()) as urlopen:
            with mock.patch.object(self.main, "validate_upstream_url"):
                answer = self.main.compatible_answer("question", [], config)

        self.assertEqual(answer, "provider answer")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://example.test/v1/chat/completions")
        self.assertEqual(request.headers["Authorization"], "Bearer test-key")

    def test_openai_compatible_provider_uses_full_request_url(self) -> None:
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            @staticmethod
            def read() -> bytes:
                return json.dumps({"choices": [{"message": {"content": "provider answer"}}]}).encode()

        os.environ["AI_API_KEY"] = "test-key"
        endpoint = "https://example.test/custom/chat"
        config = {"provider": "openai-compatible", "request_url": endpoint, "base_url": "https://ignored.test/v1"}
        with mock.patch.object(self.main.urllib.request, "urlopen", return_value=FakeResponse()) as urlopen:
            with mock.patch.object(self.main, "validate_upstream_url"):
                self.main.compatible_answer("question", [], config)
        self.assertEqual(urlopen.call_args.args[0].full_url, endpoint)

    def test_admin_cannot_configure_private_ai_upstream(self) -> None:
        auth = self.issue_token("admin", 2, "ADMIN")
        with self.assertRaises(self.main.HTTPException) as raised:
            self.main.save_ai_config(self.main.AiConfigRequest(
                provider="openai-compatible", request_url="http://127.0.0.1:11434/v1/chat/completions"
            ), authorization=auth)
        self.assertEqual(raised.exception.status_code, 400)

    def test_openai_compatible_provider_without_key_falls_back(self) -> None:
        config = {"provider": "openai-compatible", "base_url": "https://example.test/v1"}
        with mock.patch.object(self.main.urllib.request, "urlopen") as urlopen:
            answer = self.main.compatible_answer("question", [], config)
        self.assertIsNone(answer)
        urlopen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
