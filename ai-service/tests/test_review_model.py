"""
AI review and AI 检索 (chat) can call different models. Review defaults to exactly what chat uses; the endpoint and
the key belong to the provider and are shared, so a key is never sent anywhere its provider's settings do not say.
"""
import base64
import contextlib
import hashlib
import hmac
import json
import os
import tempfile
import time
import unittest
from unittest import mock

BASE = "https://models.example.test/v1"
APPROVE = '{"decision": "APPROVE", "confidence": 0.97, "reason": "内容正常"}'


def token(username: str, user_id: int, super_admin: bool) -> str:
    def segment(value: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

    claims = {"sub": username, "uid": user_id, "role": "ADMIN", "iat": int(time.time()), "exp": int(time.time()) + 600}
    if super_admin:
        claims["sa"] = True
    signing_input = f"{segment({'alg': 'HS256', 'typ': 'JWT'})}.{segment(claims)}"
    signature = base64.urlsafe_b64encode(hmac.new(
        b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256).digest()).decode().rstrip("=")
    return f"Bearer {signing_input}.{signature}"


class ReviewModelTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "review_model_test.db")
        os.environ["AI_API_KEY"] = "compatible-key"
        from app import main, model_discovery

        self.main = main
        self.discovery = model_discovery
        self.main.init_db()
        audit = mock.patch.object(self.main, "record_admin_action")
        self.recorded = audit.start()
        self.addCleanup(audit.stop)
        self.super_admin = token("admin", 2, True)
        self.admin = token("helper", 3, False)

    def tearDown(self) -> None:
        self.discovery.remember(self.discovery.Discovery("skipped", reason="reset by test"))
        self.tmpdir.cleanup()
        for name in ("AI_DB_PATH", "AI_API_KEY"):
            os.environ.pop(name, None)

    def config(self, **overrides) -> dict:
        values = self.main.read_ai_config()
        values.update({"provider": "openai-compatible", "model": "qwen-max", "base_url": BASE,
                       "ai_audit_enabled": True, "ai_audit_sample_percent": 0})
        values.update(overrides)
        return values

    @contextlib.contextmanager
    def upstream(self, reply: str):
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

    @staticmethod
    def sent(urlopen) -> dict:
        return json.loads(urlopen.call_args.args[0].data.decode("utf-8"))

    # ---- which settings each purpose uses -------------------------------------------------------------------------
    def test_by_default_review_uses_exactly_what_chat_uses(self) -> None:
        config = self.config()
        review = self.main.review_settings(config)
        self.assertEqual(("openai-compatible", "qwen-max"), (review["provider"], review["model"]))
        self.assertEqual(config, review)

    def test_a_review_model_alone_changes_only_the_model(self) -> None:
        review = self.main.review_settings(self.config(review_model="qwen3-8b"))
        self.assertEqual(("openai-compatible", "qwen3-8b", BASE), (review["provider"], review["model"], review["base_url"]))
        # Naming chat's own provider changes nothing more.
        same = self.main.review_settings(self.config(review_provider="openai-compatible", review_model="qwen3-8b"))
        self.assertEqual(("openai-compatible", "qwen3-8b"), (same["provider"], same["model"]))

    def test_another_provider_never_inherits_chats_model_name(self) -> None:
        review = self.main.review_settings(self.config(review_provider="anthropic"))
        self.assertEqual(("anthropic", ""), (review["provider"], review["model"]))
        self.assertEqual("claude-opus-5", self.main.provider_keys.claude_model(review))

    def test_the_settings_passed_in_are_not_changed(self) -> None:
        config = self.config(review_model="qwen3-8b")
        self.main.review_settings(config)
        self.assertEqual("qwen-max", config["model"])

    # ---- the requests actually made -----------------------------------------------------------------------------
    def test_review_and_chat_call_their_own_models(self) -> None:
        config = self.config(review_model="qwen3-8b")

        with self.upstream(APPROVE) as urlopen:
            verdict = self.main.review_content("标题", "一段正常的知识内容，足够让模型判断。", config)
        self.assertEqual("APPROVE", verdict["decision"])
        self.assertEqual("qwen3-8b", self.sent(urlopen)["model"])
        self.assertIs(False, self.sent(urlopen)["enable_thinking"], "judged by the review model's own name")

        with self.upstream("回答") as urlopen:
            self.main.compatible_answer("问题", [], config)
        self.assertEqual("qwen-max", self.sent(urlopen)["model"])
        self.assertNotIn("enable_thinking", self.sent(urlopen))

    def test_review_can_use_claude_while_chat_uses_a_compatible_service(self) -> None:
        captured = {}

        def fake_claude(title, body, config, secret, client_factory=None):
            captured.update(provider=config["provider"], model=config["model"], secret=secret)
            return {"decision": "APPROVE", "confidence": 0.97, "reason": "内容正常"}

        os.environ["ANTHROPIC_API_KEY"] = "claude-key"
        self.addCleanup(os.environ.pop, "ANTHROPIC_API_KEY", None)
        with mock.patch.object(self.main, "claude_review", side_effect=fake_claude), \
                mock.patch.object(self.main.urllib.request, "urlopen") as urlopen:
            self.main.review_content("标题", "一段正常的知识内容，足够让模型判断。",
                                     self.config(review_provider="anthropic", review_model="claude-haiku-4-5"))

        self.assertEqual({"provider": "anthropic", "model": "claude-haiku-4-5", "secret": "claude-key"}, captured)
        urlopen.assert_not_called()

    def test_review_can_be_kept_to_the_local_rules_while_chat_uses_a_model(self) -> None:
        with mock.patch.object(self.main.urllib.request, "urlopen") as urlopen:
            verdict = self.main.review_content("标题", "一段正常的知识内容，足够让模型判断。", self.config(review_provider="local"))

        urlopen.assert_not_called()
        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn("本地规则", verdict["reason"])

    # ---- saving --------------------------------------------------------------------------------------------------
    def save(self, authorization: str, **fields):
        return self.main.save_ai_config(self.main.AiConfigRequest(**fields), authorization=authorization)

    def test_a_super_administrator_sets_the_review_model(self) -> None:
        self.save(self.super_admin, provider="anthropic", model="claude-opus-5",
                  review_provider="openai-compatible", review_model=" qwen3-8b ", base_url=BASE)

        stored = self.main.read_ai_config()
        self.assertEqual(("openai-compatible", "qwen3-8b"), (stored["review_provider"], stored["review_model"]))
        # Each change is named in the action log by its own label ("审核模型" is also inside "审核模型服务").
        labels = {change["label"] for change in self.recorded.call_args.args[7]["changes"]}
        self.assertLessEqual({"审核模型服务", "审核模型"}, labels)

    def test_an_ordinary_administrator_cannot_move_the_review_model(self) -> None:
        self.save(self.super_admin, provider="openai-compatible", model="qwen-max", base_url=BASE, review_model="qwen3-8b")

        self.save(self.admin, provider="openai-compatible", model="qwen-max", base_url=BASE,
                  review_provider="anthropic", review_model="someone-elses-model")

        stored = self.main.read_ai_config()
        self.assertEqual(("", "qwen3-8b"), (stored["review_provider"], stored["review_model"]))

    def test_a_review_setting_that_could_never_work_is_refused(self) -> None:
        with self.assertRaises(self.main.HTTPException) as no_model:
            self.save(self.super_admin, provider="anthropic", review_provider="openai-compatible", base_url=BASE)
        self.assertIn("审核模型名称", no_model.exception.detail)

        with self.assertRaises(self.main.HTTPException) as no_address:
            self.save(self.super_admin, provider="anthropic", review_provider="openai-compatible", review_model="qwen3-8b")
        self.assertIn("接口地址", no_address.exception.detail)

        with self.assertRaises(Exception):
            self.main.AiConfigRequest(review_provider="somewhere-else")

    # ---- health and start-up -------------------------------------------------------------------------------------
    def test_review_health_describes_the_review_model(self) -> None:
        health = self.main.review_health(self.config(review_model="qwen3-8b"))
        self.assertEqual(("openai-compatible", "qwen3-8b"), (health["provider"], health["model"]))
        self.assertEqual("environment", health["key_source"])

        local = self.main.review_health(self.config(review_provider="local"))
        self.assertFalse(local["model_configured"])

        # Claude with no model named reports the default it will really call.
        claude = self.main.review_health(self.config(review_provider="anthropic"))
        self.assertEqual(("anthropic", "claude-opus-5"), (claude["provider"], claude["model"]))

    def test_startup_checks_the_review_model_when_review_uses_the_compatible_service(self) -> None:
        with mock.patch.object(self.main, "read_ai_config", return_value=self.config(
                provider="anthropic", model="claude-opus-5", review_provider="openai-compatible",
                review_model="qwen3-8b")), \
                mock.patch.object(self.main.model_discovery, "run_in_background") as started:
            self.main.start_model_discovery()

        arguments = started.call_args.kwargs
        self.assertEqual("openai-compatible", arguments["provider"])
        self.assertIsNone(arguments["configured_model"], "chat does not use this provider")
        self.assertEqual("qwen3-8b", arguments["review_model"])
        self.assertEqual("compatible-key", arguments["api_key"])

    def test_startup_logs_and_warns_about_the_review_model(self) -> None:
        with self.assertLogs("ai.model_discovery", "INFO") as logs:
            self.discovery.run_at_startup(provider="openai-compatible", base_url=BASE, api_key="compatible-key",
                                          configured_model="qwen-max", review_model="qwen3-8b",
                                          validate=lambda url: None,
                                          fetch=lambda url, key, timeout: {"data": [{"id": "qwen-max"}]})
        text = "\n".join(logs.output)
        self.assertIn("active model: 'qwen-max'", text)
        self.assertIn("active review model: 'qwen3-8b'", text)
        self.assertIn("model not listed", text)
        self.assertIn("'qwen3-8b'", text)


if __name__ == "__main__":
    unittest.main()
