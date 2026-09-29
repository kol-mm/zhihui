"""A summary and a category for an uploaded document: asked of the chat model, and checked rather than trusted."""
import contextlib
import json
import os
import tempfile
import unittest
from unittest import mock

BASE = "https://models.example.test/v1"
CATEGORIES = [{"id": 3, "name": "人工智能"}, {"id": 7, "name": "数据库"}]


class AnalysisTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "analysis_test.db")
        os.environ["AI_API_KEY"] = "compatible-key"
        from app import analysis, main

        self.main = main
        self.analysis = analysis
        self.main.init_db()

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        for name in ("AI_DB_PATH", "AI_API_KEY"):
            os.environ.pop(name, None)

    def config(self, **overrides) -> dict:
        values = self.main.read_ai_config()
        values.update({"provider": "openai-compatible", "model": "qwen-max", "base_url": BASE, "ai_analysis_enabled": True})
        values.update(overrides)
        return values

    @contextlib.contextmanager
    def model(self, reply: str):
        class FakeResponse:
            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *args):
                return False

            @staticmethod
            def read():
                return json.dumps({"choices": [{"message": {"content": reply}}]}).encode()

        with mock.patch.object(self.main, "validate_upstream_url"):
            with mock.patch.object(self.main.urllib.request, "urlopen", return_value=FakeResponse()) as urlopen:
                yield urlopen

    def analyze(self, reply: str, text: str = "向量检索与大模型的结合方法。", **config):
        with self.model(reply) as urlopen:
            result = self.main.analyze_document("检索增强生成", text, CATEGORIES, self.config(**config))
        self.urlopen = urlopen
        return result

    # ---- what is accepted -----------------------------------------------------------------------------------------
    def test_a_summary_and_a_listed_category_are_returned(self) -> None:
        result = self.analyze('{"summary": "介绍检索增强生成。", "categoryId": 3}')
        self.assertEqual({"available": True, "summary": "介绍检索增强生成。", "categoryId": 3, "reason": ""}, result)

    def test_a_category_that_is_not_on_the_list_is_dropped(self) -> None:
        for invented in ('99', '"3; DROP TABLE"', 'true', '"人工智能"'):
            with self.subTest(invented):
                result = self.analyze('{"summary": "摘要", "categoryId": %s}' % invented)
                self.assertIsNone(result["categoryId"])
                self.assertEqual("摘要", result["summary"])
        self.assertEqual(7, self.analyze('{"summary": "摘要", "categoryId": "7"}')["categoryId"])

    def test_true_is_not_category_one(self) -> None:
        # JSON true is an int to Python; with a category numbered 1 on offer it must still not count as one.
        with self.model('{"summary": "摘要", "categoryId": true}'):
            result = self.main.analyze_document("标题", "正文", [{"id": 1, "name": "通用"}], self.config())
        self.assertIsNone(result["categoryId"])

    def test_an_overlong_summary_is_cut_and_whitespace_folded(self) -> None:
        result = self.analyze(json.dumps({"summary": "长" * 500 + "\n\n  尾", "categoryId": None}))
        self.assertEqual(self.analysis.SUMMARY_MAX_CHARS, len(result["summary"]))

    def test_a_summary_the_sensitive_rules_catch_is_dropped(self) -> None:
        result = self.analyze('{"summary": "部署时运行 java -jar app.jar", "categoryId": 3}')
        self.assertIsNone(result["summary"])
        self.assertEqual(3, result["categoryId"])

    def test_an_answer_that_is_not_json_gives_nothing(self) -> None:
        result = self.analyze("好的，这是一篇关于检索的文章。")
        self.assertFalse(result["available"])

    def test_the_model_reads_only_the_start_of_a_long_document(self) -> None:
        self.analyze('{"summary": "摘要", "categoryId": null}', text="字" * 50_000)
        sent = json.loads(self.urlopen.call_args.args[0].data.decode("utf-8"))
        user = sent["messages"][1]["content"]
        self.assertLess(len(user), self.analysis.PREVIEW_CHARS + 500)
        self.assertIn("3：人工智能", user)
        self.assertEqual(self.analysis.MAX_TOKENS, sent["max_tokens"])

    # ---- when nothing is asked -----------------------------------------------------------------------------------
    def test_nothing_is_asked_while_the_switch_is_off(self) -> None:
        with self.model('{"summary": "摘要", "categoryId": 3}') as urlopen:
            result = self.main.analyze_document("标题", "正文", CATEGORIES, self.config(ai_analysis_enabled=False))
        self.assertFalse(result["available"])
        urlopen.assert_not_called()

    def test_without_a_model_there_is_no_analysis(self) -> None:
        with mock.patch.object(self.main.urllib.request, "urlopen") as urlopen:
            result = self.main.analyze_document("标题", "正文", CATEGORIES, self.config(provider="local"))
        self.assertFalse(result["available"])
        urlopen.assert_not_called()

    def test_an_empty_document_is_not_sent(self) -> None:
        with self.model('{"summary": "摘要", "categoryId": 3}') as urlopen:
            self.assertFalse(self.main.analyze_document("标题", "  ", CATEGORIES, self.config())["available"])
        urlopen.assert_not_called()

    # ---- providers ---------------------------------------------------------------------------------------------------
    def test_qwen3_is_told_not_to_think(self) -> None:
        self.analyze('{"summary": "摘要", "categoryId": 3}', model="qwen3-8b")
        self.assertIs(False, json.loads(self.urlopen.call_args.args[0].data.decode("utf-8"))["enable_thinking"])

    def test_claude_is_asked_through_its_own_api(self) -> None:
        os.environ["ANTHROPIC_API_KEY"] = "claude-key"
        self.addCleanup(os.environ.pop, "ANTHROPIC_API_KEY", None)
        with mock.patch.object(self.main.provider_keys, "claude_text",
                               return_value=('{"summary": "摘要", "categoryId": 7}', None)) as asked, \
                mock.patch.object(self.main.provider_keys, "make_claude_client"):
            result = self.main.analyze_document("标题", "正文", CATEGORIES, self.config(provider="anthropic"))
        self.assertEqual((True, 7), (result["available"], result["categoryId"]))
        self.assertEqual(self.analysis.SYSTEM_PROMPT, asked.call_args.args[2])

    # ---- the endpoint ------------------------------------------------------------------------------------------------
    def test_only_the_services_may_ask(self) -> None:
        request = self.main.AnalyzeRequest(title="标题", text="正文", categories=[{"id": 3, "name": "人工智能"}])
        for token in (None, "wrong"):
            with self.assertRaises(self.main.HTTPException) as refused:
                self.main.internal_analyze(request, x_internal_token=token)
            self.assertEqual(403, refused.exception.status_code)

    def test_the_switch_is_public_so_the_knowledge_service_can_read_it(self) -> None:
        self.assertIn("ai_analysis_enabled", self.main.public_config().data)


if __name__ == "__main__":
    unittest.main()
