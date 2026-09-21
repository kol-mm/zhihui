"""AI review decides what may be published; anything it cannot judge goes to a person."""
import json
import os
import tempfile
import unittest
from unittest import mock


class ContentReviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "review_test.db")

        from app import main

        self.main = main
        self.main.init_db()
        self.clean = "这是一篇关于向量检索与知识库构建的说明文档，介绍了分块策略与召回评估方法。"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)
        os.environ.pop("AI_API_KEY", None)

    def settings(self, **overrides) -> dict:
        """Review is off by default, so a test that wants a verdict asks for it."""
        values = self.main.read_ai_config()
        values["ai_audit_enabled"] = True
        values.update(overrides)
        return values

    # ---- without a model configured -------------------------------------------------------------------
    def test_ordinary_content_is_approved_by_the_local_rules(self) -> None:
        verdict = self.main.review_content("向量检索入门", self.clean, self.settings(provider="local"))

        self.assertEqual("APPROVE", verdict["decision"])
        self.assertIn("本地规则", verdict["reason"])

    def test_content_hitting_the_sensitive_rules_is_rejected(self) -> None:
        sensitive = next(pattern.pattern for pattern in self.main.SENSITIVE_CONTENT_PATTERNS)
        with mock.patch.object(self.main, "contains_sensitive_content", return_value=True):
            verdict = self.main.review_content("标题", self.clean, self.settings())

        self.assertEqual("REJECT", verdict["decision"])
        self.assertEqual(1.0, verdict["confidence"])
        self.assertIn("敏感词", verdict["reason"])
        self.assertIsInstance(sensitive, str)

    def test_content_too_short_to_judge_goes_to_a_person(self) -> None:
        verdict = self.main.review_content("嗨", "你好", self.settings(provider="local"))

        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn("过短", verdict["reason"])

    def test_empty_content_goes_to_a_person(self) -> None:
        self.assertEqual("ESCALATE", self.main.review_content("", "", self.settings())["decision"])

    def test_review_can_be_turned_off_entirely(self) -> None:
        verdict = self.main.review_content("向量检索入门", self.clean, self.settings(ai_audit_enabled=False))

        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn("未开启", verdict["reason"])

    # ---- with a model configured ----------------------------------------------------------------------
    def model_settings(self, **overrides) -> dict:
        os.environ["AI_API_KEY"] = "test-key"
        values = {"provider": "openai-compatible", "model": "test-model",
                  "request_url": "https://upstream.test/v1/chat/completions"}
        values.update(overrides)
        return self.settings(**values)

    def answering(self, content: str):
        """A model that replies with `content`; the upstream check is stubbed because the host is fictional."""
        class FakeResponse:
            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *args):
                return False

            @staticmethod
            def read():
                return json.dumps({"choices": [{"message": {"content": content}}]}).encode()

        import contextlib

        @contextlib.contextmanager
        def reachable_model():
            with mock.patch.object(self.main, "validate_upstream_url"):
                with mock.patch.object(self.main.urllib.request, "urlopen", return_value=FakeResponse()):
                    yield

        return reachable_model()

    def test_a_confident_model_rejection_is_acted_on(self) -> None:
        with self.answering('{"decision": "REJECT", "confidence": 0.93, "reason": "包含人身攻击"}'):
            verdict = self.main.review_content("标题", self.clean, self.model_settings())

        self.assertEqual("REJECT", verdict["decision"])
        self.assertEqual("包含人身攻击", verdict["reason"])

    def test_a_hesitant_model_rejection_goes_to_a_person(self) -> None:
        with self.answering('{"decision": "REJECT", "confidence": 0.6, "reason": "可能含广告"}'):
            verdict = self.main.review_content("标题", self.clean, self.model_settings())

        self.assertEqual("ESCALATE", verdict["decision"], "below the reject threshold, so a person decides")
        self.assertIn("可能含广告", verdict["reason"])

    def test_a_hesitant_model_approval_goes_to_a_person(self) -> None:
        with self.answering('{"decision": "APPROVE", "confidence": 0.5, "reason": "看起来正常"}'):
            verdict = self.main.review_content("标题", self.clean, self.model_settings())

        self.assertEqual("ESCALATE", verdict["decision"])

    def test_thresholds_are_configurable(self) -> None:
        with self.answering('{"decision": "APPROVE", "confidence": 0.6, "reason": "正常内容"}'):
            strict = self.main.review_content("标题", self.clean, self.model_settings(ai_audit_approve_confidence=0.9))
            lenient = self.main.review_content("标题", self.clean, self.model_settings(ai_audit_approve_confidence=0.55))

        self.assertEqual("ESCALATE", strict["decision"])
        self.assertEqual("APPROVE", lenient["decision"])

    def test_an_unreachable_model_never_publishes_by_itself(self) -> None:
        with mock.patch.object(self.main, "validate_upstream_url"):
            with mock.patch.object(self.main.urllib.request, "urlopen", side_effect=OSError("connection refused")):
                verdict = self.main.review_content("标题", self.clean, self.model_settings())

        self.assertIn(verdict["decision"], ("APPROVE", "ESCALATE"))
        self.assertNotEqual("REJECT", verdict["decision"], "an outage must not reject a member's work")

    def test_nonsense_from_the_model_is_not_treated_as_a_verdict(self) -> None:
        for reply in ("not json at all", '{"decision": "MAYBE", "confidence": 0.9}', '{"confidence": "high"}'):
            with self.answering(reply):
                self.assertIsNone(self.main.model_review("标题", self.clean, self.model_settings()),
                                  f"{reply!r} should not count as a verdict")

    def test_a_private_upstream_is_not_asked(self) -> None:
        settings = self.model_settings(request_url="http://127.0.0.1:11434/v1/chat/completions")
        with mock.patch.object(self.main.urllib.request, "urlopen") as urlopen:
            self.assertIsNone(self.main.model_review("标题", self.clean, settings))
        urlopen.assert_not_called()


class InternalReviewEndpointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "review_endpoint.db")

        from app import main

        self.main = main
        self.main.init_db()

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)

    def test_the_endpoint_needs_the_internal_token(self) -> None:
        request = self.main.ReviewRequest(kind="KNOWLEDGE", title="标题", text="正文")

        with self.assertRaises(self.main.HTTPException) as denied:
            self.main.internal_review(request, x_internal_token=None)
        self.assertEqual(403, denied.exception.status_code)

        with self.assertRaises(self.main.HTTPException) as wrong:
            self.main.internal_review(request, x_internal_token="not-the-token")
        self.assertEqual(403, wrong.exception.status_code)

    def test_the_endpoint_returns_a_verdict(self) -> None:
        # Left at the shipped default, which is off, so the endpoint hands the decision to a person.
        answer = self.main.internal_review(
            self.main.ReviewRequest(kind="KNOWLEDGE", title="向量检索",
                                    text="这是一篇关于向量检索与知识库构建的说明文档，介绍分块与召回。"),
            x_internal_token="ai-knowledge-local-internal")

        self.assertEqual("ESCALATE", answer.data["decision"])
        self.assertIn("未开启", answer.data["reason"])


class UpstreamSchemeTest(unittest.TestCase):
    """A public upstream must be reachable over TLS, so a rebound name fails before the key is sent."""

    def setUp(self) -> None:
        from app import main

        self.main = main
        os.environ.pop("AI_ALLOW_PRIVATE_UPSTREAM", None)

    def tearDown(self) -> None:
        os.environ.pop("AI_ALLOW_PRIVATE_UPSTREAM", None)

    def test_a_public_http_upstream_is_refused(self) -> None:
        with self.assertRaises(ValueError) as refused:
            self.main.validate_upstream_url("http://api.example.com/v1/chat/completions", resolve_dns=False)

        self.assertIn("https", str(refused.exception))

    def test_a_public_https_upstream_is_accepted(self) -> None:
        self.main.validate_upstream_url("https://api.example.com/v1/chat/completions", resolve_dns=False)

    def test_a_private_address_is_still_refused_over_https(self) -> None:
        with self.assertRaises(ValueError):
            self.main.validate_upstream_url("https://127.0.0.1:11434/v1/chat/completions", resolve_dns=False)

    def test_local_development_can_still_opt_out(self) -> None:
        os.environ["AI_ALLOW_PRIVATE_UPSTREAM"] = "true"

        self.main.validate_upstream_url("http://127.0.0.1:11434/v1/chat/completions", resolve_dns=False)

    def test_credentials_in_the_url_are_still_refused(self) -> None:
        with self.assertRaises(ValueError):
            self.main.validate_upstream_url("https://user:secret@api.example.com/v1", resolve_dns=False)


if __name__ == "__main__":
    unittest.main()
