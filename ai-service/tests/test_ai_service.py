import os
import math
import tempfile
import unittest
import base64
import hashlib
import hmac
import json
import time


class AiServicePersistenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "ai_service_test.db")

        from app import main

        self.main = main
        self.main.init_db()

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)

    def test_parse_retrieve_and_chat_are_persisted(self) -> None:
        parse_response = self.main.parse_document(
            self.main.TextRequest(
                file_id=101,
                title="平台功能清单",
                text="知识库支持上传和检索\n广场展示关注用户动态",
            )
        )
        self.assertEqual(parse_response.code, 0)
        self.assertEqual(parse_response.data["count"], 2)

        retrieve_response = self.main.retrieve(
            self.main.ChatRequest(question="知识库检索", user_id=1)
        )
        self.assertEqual(retrieve_response.code, 0)
        self.assertGreaterEqual(len(retrieve_response.data["matches"]), 1)

        embedding_response = self.main.embedding(
            self.main.TextRequest(text="知识库向量检索")
        )
        self.assertEqual(embedding_response.data["dimension"], 128)
        vector = embedding_response.data["vector"]
        self.assertAlmostEqual(math.sqrt(sum(value * value for value in vector)), 1.0, places=4)

        vector_status = self.main.vector_status()
        self.assertEqual(vector_status.data["mode"], "local")
        self.assertEqual(vector_status.data["indexed_chunks"], 2)
        self.assertFalse(vector_status.data["external_ready"])

        chat_response = self.main.chat(
            self.main.ChatRequest(question="知识库检索怎么做", user_id=1)
        )
        self.assertEqual(chat_response.code, 0)
        self.assertIn("session_id", chat_response.data)
        self.assertEqual(len(chat_response.data["messages"]), 2)
        self.assertGreaterEqual(len(chat_response.data["references"]), 1)

        health_response = self.main.health()
        self.assertEqual(health_response.data["chunk_count"], 2)
        self.assertEqual(health_response.data["session_count"], 1)
        self.assertEqual(health_response.data["vector_dimension"], 128)

        history = self.main.chat_history(user_id=1)
        self.assertEqual(len(history.data["sessions"]), 1)
        self.assertEqual(len(history.data["messages"]), 2)

    def test_admin_can_manage_ai_configuration(self) -> None:
        def segment(value: dict) -> str:
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

        header = segment({"alg": "HS256", "typ": "JWT"})
        payload = segment({"sub": "admin", "role": "ADMIN", "iat": int(time.time()), "exp": int(time.time()) + 600})
        signing_input = f"{header}.{payload}"
        signature = base64.urlsafe_b64encode(hmac.new(
            b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256
        ).digest()).decode().rstrip("=")
        auth = f"Bearer {signing_input}.{signature}"

        saved = self.main.save_ai_config(self.main.AiConfigRequest(match_limit=3), authorization=auth)
        self.assertEqual(saved.data["configuration"]["match_limit"], 3)
        overview = self.main.ai_admin_overview(authorization=auth)
        self.assertIn("configuration", overview.data)


if __name__ == "__main__":
    unittest.main()
