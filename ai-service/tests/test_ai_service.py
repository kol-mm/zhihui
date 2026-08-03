import os
import tempfile
import unittest


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


if __name__ == "__main__":
    unittest.main()
