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
    def test_local_rules_alone_do_not_clear_the_default_bar(self) -> None:
        # Nothing but a sensitive-word check stands behind this, so it must not publish on its own.
        verdict = self.main.review_content("向量检索入门", self.clean, self.settings(provider="local"))

        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn("本地规则", verdict["reason"])
        self.assertIn("门槛", verdict["reason"])

    def test_local_rules_may_publish_when_the_bar_is_lowered_to_meet_them(self) -> None:
        verdict = self.main.review_content("向量检索入门", self.clean, self.settings(
            provider="local", ai_audit_approve_confidence=self.main.LOCAL_RULE_CONFIDENCE,
            ai_audit_sample_percent=0))

        self.assertEqual("APPROVE", verdict["decision"])
        self.assertEqual(self.main.LOCAL_RULE_CONFIDENCE, verdict["confidence"])

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


class SamplingTest(unittest.TestCase):
    """A share of what the model passes goes to a person anyway, because content can talk to the model."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "sampling.db")

        from app import main

        self.main = main
        self.main.init_db()
        self.clean = "这是一篇关于向量检索与知识库构建的说明文档，介绍了分块策略与召回评估方法。"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)
        os.environ.pop("AI_API_KEY", None)

    def settings(self, **overrides) -> dict:
        values = self.main.read_ai_config()
        values["ai_audit_enabled"] = True
        values["ai_audit_approve_confidence"] = self.main.LOCAL_RULE_CONFIDENCE
        values.update(overrides)
        return values

    def test_everything_can_be_sampled(self) -> None:
        verdict = self.main.review_content("标题", self.clean, self.settings(ai_audit_sample_percent=100))

        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn("抽样复核", verdict["reason"])
        self.assertIn("100%", verdict["reason"])

    def test_sampling_can_be_turned_off(self) -> None:
        verdict = self.main.review_content("标题", self.clean, self.settings(ai_audit_sample_percent=0))

        self.assertEqual("APPROVE", verdict["decision"])

    def test_the_share_is_honoured(self) -> None:
        settings = self.settings(ai_audit_sample_percent=25)

        with mock.patch.object(self.main.random, "random", return_value=0.10):
            self.assertTrue(self.main.sampled_for_review(settings), "10% falls inside a 25% sample")
        with mock.patch.object(self.main.random, "random", return_value=0.80):
            self.assertFalse(self.main.sampled_for_review(settings), "80% falls outside it")

    def test_a_rejection_is_never_sampled_into_publication(self) -> None:
        with mock.patch.object(self.main, "contains_sensitive_content", return_value=True):
            verdict = self.main.review_content("标题", self.clean, self.settings(ai_audit_sample_percent=100))

        self.assertEqual("REJECT", verdict["decision"], "sampling only ever holds something back")

    def test_a_nonsense_share_falls_back_to_the_default(self) -> None:
        self.assertIsInstance(self.main.sampled_for_review({"ai_audit_sample_percent": "many"}), bool)


class ReviewHealthTest(unittest.TestCase):
    """Review that is quietly doing nothing looks exactly like review that is working, without counters."""

    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "health.db")

        from app import main

        self.main = main
        self.main.init_db()
        with self.main._review_lock:
            for key in ("requested", "approved", "rejected", "escalated", "sampled", "model_failures"):
                self.main._review_health[key] = 0
            for key in ("last_decision", "last_decision_at", "last_failure", "last_failure_at"):
                self.main._review_health[key] = None
        self.clean = "这是一篇关于向量检索与知识库构建的说明文档，介绍了分块策略与召回评估方法。"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)
        os.environ.pop("AI_API_KEY", None)

    def settings(self, **overrides) -> dict:
        values = self.main.read_ai_config()
        values["ai_audit_enabled"] = True
        values["ai_audit_approve_confidence"] = self.main.LOCAL_RULE_CONFIDENCE
        values["ai_audit_sample_percent"] = 0
        values.update(overrides)
        return values

    def test_an_enabled_but_unused_reviewer_reads_as_idle(self) -> None:
        health = self.main.review_health(self.settings())

        self.assertTrue(health["enabled"])
        self.assertEqual(0, health["requested"])
        self.assertTrue(health["idle"], "nothing has been asked of it, which is worth noticing")

    def test_decisions_are_counted_by_kind(self) -> None:
        settings = self.settings()
        self.main.review_content("标题", self.clean, settings)
        self.main.review_content("短", "太短", settings)
        with mock.patch.object(self.main, "contains_sensitive_content", return_value=True):
            self.main.review_content("标题", self.clean, settings)

        health = self.main.review_health(settings)
        self.assertEqual(3, health["requested"])
        self.assertEqual(1, health["approved"])
        self.assertEqual(1, health["rejected"])
        self.assertEqual(1, health["escalated"])
        self.assertEqual(2, health["decided_automatically"])
        self.assertFalse(health["idle"])
        self.assertIsNotNone(health["last_decision_at"])

    def test_a_sampled_approval_is_counted_as_such(self) -> None:
        self.main.review_content("标题", self.clean, self.settings(ai_audit_sample_percent=100))

        health = self.main.review_health(self.settings())
        self.assertEqual(1, health["sampled"])
        self.assertEqual(1, health["escalated"])
        self.assertEqual(0, health["approved"])

    def test_a_model_that_cannot_be_reached_is_recorded(self) -> None:
        os.environ["AI_API_KEY"] = "test-key"
        settings = self.settings(provider="openai-compatible", model="m",
                                 request_url="https://upstream.test/v1/chat/completions")
        with mock.patch.object(self.main, "validate_upstream_url"):
            with mock.patch.object(self.main.urllib.request, "urlopen", side_effect=OSError("refused")):
                self.main.review_content("标题", self.clean, settings)

        health = self.main.review_health(settings)
        self.assertEqual(1, health["model_failures"])
        self.assertIn("refused", health["last_failure"])
        self.assertIsNotNone(health["last_failure_at"])

    def test_a_reply_that_is_not_a_verdict_is_recorded_as_a_failure(self) -> None:
        os.environ["AI_API_KEY"] = "test-key"
        settings = self.settings(provider="openai-compatible", model="m",
                                 request_url="https://upstream.test/v1/chat/completions")

        class FakeResponse:
            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *args):
                return False

            @staticmethod
            def read():
                return json.dumps({"choices": [{"message": {"content": "no idea"}}]}).encode()

        with mock.patch.object(self.main, "validate_upstream_url"):
            with mock.patch.object(self.main.urllib.request, "urlopen", return_value=FakeResponse()):
                self.main.review_content("标题", self.clean, settings)

        health = self.main.review_health(settings)
        self.assertEqual(1, health["model_failures"])
        self.assertIn("无法解析", health["last_failure"])


if __name__ == "__main__":
    unittest.main()
