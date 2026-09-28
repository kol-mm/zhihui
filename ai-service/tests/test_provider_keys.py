"""Model provider keys: who can manage them, that the secret never comes back out, and how Claude is called."""
import base64
import hashlib
import hmac
import json
import os
import sqlite3
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from fastapi import HTTPException

CLAUDE_KEY = "sk-ant-api03-" + "A" * 80 + "Zq9x"
OTHER_CLAUDE_KEY = "sk-ant-api03-" + "B" * 80 + "Wm2k"
COMPATIBLE_KEY = "sk-proj-" + "c" * 40 + "7Tt1"


def text_block(text):
    return SimpleNamespace(type="text", text=text)


class FakeClaude:
    """Stands in for anthropic.Anthropic and remembers exactly what was asked of it."""

    def __init__(self, content=None, stop_reason="end_turn", error=None, retrieve_error=None):
        self.calls = []
        self.content = content if content is not None else [text_block("好的")]
        self.stop_reason = stop_reason
        self.error = error
        self.retrieve_error = retrieve_error
        self.messages = SimpleNamespace(create=lambda **kw: self._create("messages", kw))
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=lambda **kw: self._create("beta", kw)))
        self.models = SimpleNamespace(retrieve=self._retrieve)

    def _create(self, surface, kwargs):
        self.calls.append((surface, kwargs))
        if self.error:
            raise self.error
        details = SimpleNamespace(category="cyber") if self.stop_reason == "refusal" else None
        return SimpleNamespace(content=self.content, stop_reason=self.stop_reason, stop_details=details)

    def _retrieve(self, model_id):
        self.calls.append(("models.retrieve", {"model_id": model_id}))
        if self.retrieve_error:
            raise self.retrieve_error
        return SimpleNamespace(id=model_id)


def api_error(cls, status):
    import anthropic  # noqa: F401  (the SDK ships httpx2)
    import httpx2

    response = httpx2.Response(status, request=httpx2.Request("GET", "https://api.anthropic.com/v1/models/x"))
    return cls("refused", response=response, body=None)


class ProviderKeysTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        environment = mock.patch.dict(os.environ, {
            "AI_DB_PATH": os.path.join(self.tmpdir.name, "provider_keys_test.db"),
            "AI_KEY_ENCRYPTION_KEY": "test-encryption-key-" + "x" * 32,
        })
        environment.start()
        self.addCleanup(environment.stop)
        for variable in ("ANTHROPIC_API_KEY", "AI_API_KEY", "AI_ALLOW_DEFAULT_SECRETS"):
            os.environ.pop(variable, None)

        from app import main

        self.main = main
        self.keys = main.provider_keys
        self.main.init_db()
        self.audited = []
        audit = mock.patch.object(self.main, "record_admin_action",
                                  side_effect=lambda *args, **kwargs: self.audited.append((args, kwargs)))
        audit.start()
        self.addCleanup(audit.stop)
        self.super_admin = self.token("root", 2, "ADMIN", super_admin=True)
        self.admin = self.token("helper", 3, "ADMIN")
        self.member = self.token("demo", 1, "USER")

    def tearDown(self) -> None:
        self.tmpdir.cleanup()

    @staticmethod
    def token(username, user_id, role, super_admin=False):
        def segment(value):
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

        claims = {"sub": username, "uid": user_id, "role": role, "iat": int(time.time()), "exp": int(time.time()) + 600}
        if super_admin:
            claims["sa"] = True
        signing_input = f"{segment({'alg': 'HS256', 'typ': 'JWT'})}.{segment(claims)}"
        signature = base64.urlsafe_b64encode(hmac.new(
            b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256).digest()).decode().rstrip("=")
        return f"Bearer {signing_input}.{signature}"

    def add(self, provider="anthropic", name="主密钥", secret=CLAUDE_KEY, activate=True):
        response = self.main.add_provider_key(
            self.main.ProviderKeyAddRequest(provider=provider, name=name, secret=secret, activate=activate),
            authorization=self.super_admin)
        self.assertEqual(0, response.code, response.message)
        return response.data["key"]

    def listed(self):
        return self.main.list_provider_keys(authorization=self.super_admin).data

    def use_claude(self, model="claude-opus-5"):
        with sqlite3.connect(os.environ["AI_DB_PATH"]) as conn:
            for key, value in (("provider", "anthropic"), ("model", model)):
                conn.execute("INSERT INTO ai_config(config_key, config_value, updated_at) VALUES (?, ?, 'now') "
                             "ON CONFLICT(config_key) DO UPDATE SET config_value = excluded.config_value", (key, value))

    # ---- who may manage keys -------------------------------------------------------------------------------------

    def test_only_a_super_administrator_can_see_or_change_keys(self) -> None:
        ref = self.main.ProviderKeyRef(keyId=1)
        calls = [
            lambda auth: self.main.list_provider_keys(authorization=auth),
            lambda auth: self.main.add_provider_key(self.main.ProviderKeyAddRequest(
                provider="anthropic", name="x", secret=CLAUDE_KEY), authorization=auth),
            lambda auth: self.main.update_provider_key(self.main.ProviderKeyUpdateRequest(keyId=1, name="y"),
                                                       authorization=auth),
            lambda auth: self.main.activate_provider_key(ref, authorization=auth),
            lambda auth: self.main.deactivate_provider_key(ref, authorization=auth),
            lambda auth: self.main.delete_provider_key(ref, authorization=auth),
            lambda auth: self.main.test_provider_key(ref, authorization=auth),
        ]
        for caller in (None, "Bearer nonsense", self.member, self.admin):
            for call in calls:
                with self.assertRaises(HTTPException) as refused:
                    call(caller)
                self.assertIn(refused.exception.status_code, (401, 403))
        self.assertEqual([], self.listed()["keys"], "nothing an outsider tried was stored")

    # ---- the secret never comes back out --------------------------------------------------------------------------

    def test_a_stored_key_is_encrypted_and_only_its_hint_is_ever_shown(self) -> None:
        key = self.add()
        self.assertEqual("sk-ant-api03-…Zq9x", key["hint"])

        with sqlite3.connect(os.environ["AI_DB_PATH"]) as conn:
            stored = conn.execute("SELECT ciphertext FROM ai_provider_key").fetchone()[0]
        self.assertNotIn(CLAUDE_KEY, stored)
        self.assertNotIn("A" * 20, stored)

        page = json.dumps(self.listed(), ensure_ascii=False)
        self.assertNotIn(CLAUDE_KEY, page)
        self.assertNotIn("ciphertext", page)
        self.assertNotIn(CLAUDE_KEY, json.dumps(self.audited, ensure_ascii=False, default=str))
        self.assertEqual("AI_PROVIDER_KEY_ADD", self.audited[0][0][2])

    def test_replacing_a_secret_is_audited_without_the_secret(self) -> None:
        key = self.add()
        response = self.main.update_provider_key(
            self.main.ProviderKeyUpdateRequest(keyId=key["id"], secret=OTHER_CLAUDE_KEY), authorization=self.super_admin)
        self.assertEqual("sk-ant-api03-…Wm2k", response.data["key"]["hint"])
        detail = self.audited[-1][0][7]
        self.assertEqual([{"field": "secret", "label": "密钥", "hidden": True}], detail["changes"])
        self.assertNotIn(OTHER_CLAUDE_KEY, json.dumps(self.audited, ensure_ascii=False, default=str))
        self.assertEqual(OTHER_CLAUDE_KEY, self.main.resolve_provider_key("anthropic")["secret"])

    def test_a_key_stored_under_another_encryption_key_is_unavailable_not_replaced(self) -> None:
        self.add()
        os.environ["ANTHROPIC_API_KEY"] = OTHER_CLAUDE_KEY
        os.environ["AI_KEY_ENCRYPTION_KEY"] = "a-different-key-" + "y" * 32
        resolution = self.main.resolve_provider_key("anthropic")
        # Falling back to the environment key would move spending to a key nobody chose.
        self.assertEqual("undecryptable", resolution["source"])
        self.assertIsNone(resolution["secret"])

    def test_without_an_encryption_key_outside_development_nothing_is_stored(self) -> None:
        os.environ.pop("AI_KEY_ENCRYPTION_KEY")
        response = self.main.add_provider_key(self.main.ProviderKeyAddRequest(
            provider="anthropic", name="主密钥", secret=CLAUDE_KEY), authorization=self.super_admin)
        self.assertNotEqual(0, response.code)
        self.assertIn("AI_KEY_ENCRYPTION_KEY", response.message)
        self.assertFalse(self.listed()["storage"]["available"])
        self.assertEqual([], self.listed()["keys"])
        # Development keeps working out of the box.
        os.environ["AI_ALLOW_DEFAULT_SECRETS"] = "1"
        self.assertTrue(self.listed()["storage"]["available"])
        self.add()

    # ---- what a key must look like ---------------------------------------------------------------------------------

    def test_keys_are_checked_before_they_are_stored(self) -> None:
        cases = {
            ("anthropic", "sk-ant-admin01-" + "a" * 60): "管理密钥",
            ("anthropic", "sk-" + "a" * 60): "sk-ant-",
            ("anthropic", "sk-ant-api03-short"): "长度",
            ("anthropic", "sk-ant-api03-" + "a" * 40 + " x"): "空格",
            ("openai-compatible", "short"): "过短",
            ("gemini", "anything-at-all"): "未知",
        }
        for (provider, secret), expected in cases.items():
            response = self.main.add_provider_key(self.main.ProviderKeyAddRequest(
                provider=provider, name="k", secret=secret), authorization=self.super_admin)
            self.assertNotEqual(0, response.code, secret)
            self.assertIn(expected, response.message, secret)
        response = self.main.add_provider_key(self.main.ProviderKeyAddRequest(
            provider="anthropic", name=" ", secret=CLAUDE_KEY), authorization=self.super_admin)
        self.assertEqual("请填写密钥名称", response.message)

    # ---- one active key per provider -------------------------------------------------------------------------------

    def test_one_key_per_provider_is_active_and_switching_is_explicit(self) -> None:
        first = self.add(name="主密钥")
        second = self.add(name="备用", secret=OTHER_CLAUDE_KEY, activate=False)
        self.assertEqual(CLAUDE_KEY, self.main.resolve_provider_key("anthropic")["secret"])

        self.main.activate_provider_key(self.main.ProviderKeyRef(keyId=second["id"]), authorization=self.super_admin)
        active = {key["name"]: key["active"] for key in self.listed()["keys"]}
        self.assertEqual({"主密钥": False, "备用": True}, active)
        self.assertEqual(OTHER_CLAUDE_KEY, self.main.resolve_provider_key("anthropic")["secret"])
        self.assertIn("替换「主密钥」", self.audited[-1][0][6])

        # A key for another provider is independent.
        self.add(provider="openai-compatible", name="兼容", secret=COMPATIBLE_KEY)
        self.assertEqual(OTHER_CLAUDE_KEY, self.main.resolve_provider_key("anthropic")["secret"])
        self.assertEqual(COMPATIBLE_KEY, self.main.resolve_provider_key("openai-compatible")["secret"])

        # The database itself refuses a second active key, whatever the code does.
        with self.assertRaises(sqlite3.IntegrityError):
            with sqlite3.connect(os.environ["AI_DB_PATH"]) as conn:
                conn.execute("UPDATE ai_provider_key SET active = 1 WHERE id = ?", (first["id"],))

    def test_the_environment_key_is_the_fallback_and_a_stored_one_wins(self) -> None:
        self.assertEqual("none", self.main.resolve_provider_key("anthropic")["source"])
        os.environ["ANTHROPIC_API_KEY"] = OTHER_CLAUDE_KEY
        os.environ["AI_API_KEY"] = COMPATIBLE_KEY
        self.assertEqual(("environment", OTHER_CLAUDE_KEY), tuple(self.main.resolve_provider_key("anthropic")[k] for k in ("source", "secret")))
        self.assertEqual(COMPATIBLE_KEY, self.main.resolve_provider_key("openai-compatible")["secret"])

        key = self.add()
        self.assertEqual(("stored", CLAUDE_KEY), tuple(self.main.resolve_provider_key("anthropic")[k] for k in ("source", "secret")))
        providers = {p["provider"]: p for p in self.listed()["providers"]}
        self.assertNotIn("secret", providers["anthropic"]["inUse"])
        self.assertEqual({"source": "stored", "keyId": key["id"], "name": "主密钥", "hint": "sk-ant-api03-…Zq9x"},
                         providers["anthropic"]["inUse"])
        self.assertEqual("environment", providers["openai-compatible"]["inUse"]["source"])
        self.assertEqual("AI_API_KEY", providers["openai-compatible"]["environmentVariable"])

        self.main.deactivate_provider_key(self.main.ProviderKeyRef(keyId=key["id"]), authorization=self.super_admin)
        self.assertEqual("environment", self.main.resolve_provider_key("anthropic")["source"])
        self.main.activate_provider_key(self.main.ProviderKeyRef(keyId=key["id"]), authorization=self.super_admin)
        self.main.delete_provider_key(self.main.ProviderKeyRef(keyId=key["id"]), authorization=self.super_admin)
        self.assertEqual("environment", self.main.resolve_provider_key("anthropic")["source"])
        self.assertIn("删除前正在使用", self.audited[-1][0][6])
        self.assertEqual([], self.listed()["keys"])

    # ---- calling Claude -------------------------------------------------------------------------------------------

    def test_chat_asks_claude_through_the_sdk_without_sampling_parameters(self) -> None:
        self.use_claude()
        self.add()
        # A block of some newer type that happens to carry text stands for whatever the API adds next: only text
        # blocks are the answer.
        fake = FakeClaude(content=[SimpleNamespace(type="thinking", thinking=""),
                                   SimpleNamespace(type="progress_update", text="正在查找资料…"),
                                   text_block("根据资料，答案是 42。")])
        with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda secret, timeout, retries: fake) as made:
            answer = self.main.model_answer("问题？", [{"title": "资料", "content": "答案是 42"}], self.main.read_ai_config())

        self.assertEqual("根据资料，答案是 42。", answer, "only text blocks make the answer")
        self.assertEqual(CLAUDE_KEY, made.call_args.args[0], "the stored key is the one used")
        surface, request = fake.calls[0]
        self.assertEqual("beta", surface)
        self.assertEqual("claude-opus-5", request["model"])
        self.assertNotIn("temperature", request, "current Claude models reject sampling parameters")
        self.assertEqual("default", request["fallbacks"])
        self.assertEqual(["server-side-fallback-2026-07-01"], request["betas"])
        self.assertIn("答案是 42", request["system"])
        self.assertEqual([{"role": "user", "content": "问题？"}], request["messages"])

    def test_models_without_the_newer_options_are_not_sent_them(self) -> None:
        self.use_claude("claude-haiku-4-5")
        self.add()
        fake = FakeClaude(content=[text_block('{"decision": "APPROVE", "confidence": 0.95, "reason": "正常"}')])
        with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda *a: fake):
            verdict = self.main.model_review("标题", "一段正常的知识内容", self.main.read_ai_config())
        surface, request = fake.calls[0]
        self.assertEqual("messages", surface)
        self.assertNotIn("fallbacks", request)
        self.assertNotIn("output_config", request)
        self.assertEqual("APPROVE", verdict["decision"])

    def test_review_asks_claude_with_the_content_kept_out_of_the_instructions(self) -> None:
        self.use_claude()
        self.add()
        fake = FakeClaude(content=[text_block('结论：{"decision": "REJECT", "confidence": 0.9, "reason": "广告"}')])
        with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda secret, timeout, retries: fake) as made:
            verdict = self.main.model_review("限时优惠", "加微信领取", self.main.read_ai_config())
        self.assertEqual({"decision": "REJECT", "confidence": 0.9, "reason": "广告"}, verdict)
        self.assertEqual(0, made.call_args.args[2], "no retries on the publishing path")
        request = fake.calls[0][1]
        self.assertEqual(self.main.REVIEW_PROMPT, request["system"])
        self.assertIn("加微信领取", request["messages"][0]["content"])
        self.assertNotIn("加微信领取", request["system"])
        self.assertEqual({"effort": "low"}, request["output_config"])

    def test_a_refusal_or_an_error_is_never_mistaken_for_a_verdict(self) -> None:
        import anthropic

        self.use_claude()
        self.add()
        refusal = FakeClaude(content=[], stop_reason="refusal")
        failure = FakeClaude(error=api_error(anthropic.AuthenticationError, 401))
        unparseable = FakeClaude(content=[text_block("我无法判断")])
        expected_failure = {id(refusal): "拒绝", id(failure): "AuthenticationError", id(unparseable): "无法解析"}
        for fake in (refusal, failure, unparseable):
            with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda *a, f=fake: f):
                self.assertIsNone(self.main.model_review("标题", "内容", self.main.read_ai_config()))
            # Each failure is recorded as what it was, so the settings page can say why review escalated.
            self.assertIn(expected_failure[id(fake)], self.main._review_health["last_failure"])
        # Chat falls back to the local answer on a refusal or an error — never shows either as Claude's answer.
        for fake in (refusal, failure):
            with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda *a, f=fake: f):
                self.assertIsNone(self.main.model_answer("问题", [], self.main.read_ai_config()))
        health = self.main.review_health(self.main.read_ai_config())
        self.assertEqual("stored", health["key_source"])
        self.assertTrue(health["model_configured"])
        self.assertNotIn(CLAUDE_KEY, json.dumps(health, ensure_ascii=False, default=str))

    def test_a_key_is_tested_without_spending_anything(self) -> None:
        import anthropic

        self.use_claude()
        key = self.add()
        outcomes = [
            (None, True, "密钥有效，可以使用 claude-opus-5"),
            (api_error(anthropic.AuthenticationError, 401), False, "密钥无效或已被撤销"),
            (api_error(anthropic.PermissionDeniedError, 403), False, "没有访问"),
            (api_error(anthropic.NotFoundError, 404), True, "找不到模型"),
            (api_error(anthropic.RateLimitError, 429), True, "限流"),
        ]
        for error, ok, message in outcomes:
            fake = FakeClaude(retrieve_error=error)
            with mock.patch.object(self.keys, "make_claude_client", side_effect=lambda *a, f=fake: f):
                result = self.main.test_provider_key(self.main.ProviderKeyRef(keyId=key["id"]),
                                                     authorization=self.super_admin).data
            self.assertEqual(ok, result["ok"], message)
            self.assertIn(message, result["message"])
            self.assertEqual([("models.retrieve", {"model_id": "claude-opus-5"})], fake.calls,
                             "a model lookup, not a message: no tokens are spent")
            self.assertEqual(ok, result["key"]["lastTestOk"])
        self.assertEqual([], [entry for entry in self.audited if "TEST" in entry[0][2]], "testing changes nothing")


if __name__ == "__main__":
    unittest.main()
