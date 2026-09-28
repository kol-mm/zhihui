"""
Model discovery for the OpenAI-compatible provider, Qwen3's thinking switch, and long content in review.

The list is asked for once at start-up; these tests call that step directly with a fake fetch, so nothing leaves
the machine.
"""
import contextlib
import json
import os
import tempfile
import unittest
import urllib.error
from unittest import mock

BASE = "https://models.example.test/v1"


def listing(*ids: str) -> dict:
    return {"object": "list", "data": [{"id": model, "object": "model"} for model in ids]}


class ModelDiscoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "discovery_test.db")
        os.environ.pop("AI_MODEL_DISCOVERY_ENABLED", None)
        from app import main, model_discovery

        self.main = main
        self.discovery = model_discovery
        self.main.init_db()
        self.fetched: list[str] = []

    def tearDown(self) -> None:
        self.discovery.remember(self.discovery.Discovery("skipped", reason="reset by test"))
        self.tmpdir.cleanup()
        for name in ("AI_DB_PATH", "AI_API_KEY", "AI_MODEL_DISCOVERY_ENABLED"):
            os.environ.pop(name, None)

    def fetch_returning(self, body=None, error: Exception | None = None):
        def fetch(url: str, api_key: str, timeout: float):
            self.fetched.append(url)
            self.assertEqual("secret-key", api_key)
            self.assertEqual(3.0, timeout, "the start-up request gives up after three seconds")
            if error is not None:
                raise error
            return body
        return fetch

    def start(self, configured: str, fetch, provider: str = "openai-compatible", base_url: str = BASE):
        return self.discovery.run_at_startup(provider=provider, base_url=base_url, api_key="secret-key",
                                             configured_model=configured, validate=lambda url: None, fetch=fetch)

    # ---- discovery ---------------------------------------------------------------------------------------------
    def test_the_configured_model_is_used_when_the_endpoint_offers_it(self) -> None:
        with self.assertLogs("ai.model_discovery", "INFO") as logs:
            found = self.start("qwen3-32b", self.fetch_returning(listing("qwen-max", "qwen3-32b")))

        self.assertEqual(("ok", ("qwen-max", "qwen3-32b")), (found.outcome, found.models))
        self.assertEqual([BASE + "/models"], self.fetched, "the list sits beside /chat/completions")
        self.assertEqual("qwen3-32b", self.discovery.active_model("qwen3-32b", BASE))
        text = "\n".join(logs.output)
        self.assertIn("2 models", text)
        self.assertIn("active model: 'qwen3-32b'", text)
        self.assertNotIn("secret-key", text)

    def test_a_model_the_endpoint_does_not_list_is_kept_with_a_warning(self) -> None:
        # The first model a service lists may be an embedding model; switching to it would break every request.
        with self.assertLogs("ai.model_discovery", "WARNING") as logs:
            self.start("gpt-4o", self.fetch_returning(listing("text-embedding-v3", "qwen3-8b")))

        self.assertEqual("gpt-4o", self.discovery.active_model("gpt-4o", BASE))
        self.assertTrue(any("model not listed" in line and "'gpt-4o'" in line for line in logs.output), logs.output)
        self.assertFalse(any("text-embedding-v3" in line and "calling" in line for line in logs.output))

    def test_the_warning_is_given_once_not_on_every_request(self) -> None:
        with self.assertLogs("ai.model_discovery", "WARNING") as logs:
            self.start("gpt-4o", self.fetch_returning(listing("qwen3-8b")))
            for _ in range(3):
                self.discovery.active_model("gpt-4o", BASE)
            # A model changed in the settings afterwards is checked against the same list, and warned about once.
            for _ in range(3):
                self.discovery.active_model("gpt-4.1", BASE)

        warnings = [line for line in logs.output if "model not listed" in line]
        self.assertEqual(2, len(warnings), warnings)

    def test_when_discovery_fails_the_configured_model_is_used_as_entered(self) -> None:
        failures = {
            "timeout": self.fetch_returning(error=TimeoutError("timed out")),
            "unreachable": self.fetch_returning(error=urllib.error.URLError("refused")),
            "no such endpoint": self.fetch_returning(error=urllib.error.HTTPError(BASE, 404, "Not Found", {}, None)),
            "server error": self.fetch_returning(error=urllib.error.HTTPError(BASE, 503, "Unavailable", {}, None)),
            "empty list": self.fetch_returning(listing()),
            "not a list": self.fetch_returning({"models": ["qwen3-8b"]}),
        }
        for name, fetch in failures.items():
            with self.subTest(name), self.assertLogs("ai.model_discovery", "WARNING") as logs:
                found = self.start("gpt-4o-mini", fetch)
                self.assertEqual("failed", found.outcome)
                self.assertEqual("gpt-4o-mini", self.discovery.active_model("gpt-4o-mini", BASE))
                self.assertTrue(any("discovery failed" in line for line in logs.output), logs.output)

    def test_turned_off_nothing_is_fetched(self) -> None:
        os.environ["AI_MODEL_DISCOVERY_ENABLED"] = "false"
        found = self.start("qwen3-8b", self.fetch_returning(listing("other")))

        self.assertEqual("disabled", found.outcome)
        self.assertEqual([], self.fetched)
        self.assertEqual("qwen3-8b", self.discovery.active_model("qwen3-8b", BASE))

    def test_other_providers_and_missing_settings_are_not_asked(self) -> None:
        self.assertEqual("skipped", self.start("claude-opus-5", self.fetch_returning(listing("x")), provider="anthropic").outcome)
        self.assertEqual("skipped", self.start("qwen3-8b", self.fetch_returning(listing("x")), base_url="").outcome)
        found = self.discovery.discover(BASE, "", lambda url: None, self.fetch_returning(listing("x")))
        self.assertEqual("skipped", found.outcome)
        self.assertEqual([], self.fetched)

    def test_a_refused_address_is_never_contacted(self) -> None:
        def refuse(url: str) -> None:
            raise ValueError("private AI upstream addresses are disabled")

        found = self.discovery.run_at_startup(provider="openai-compatible", base_url="https://10.0.0.5/v1",
                                              api_key="secret-key", configured_model="qwen3-8b", validate=refuse,
                                              fetch=self.fetch_returning(listing("x")))
        self.assertEqual("failed", found.outcome)
        self.assertEqual([], self.fetched)

    def test_the_list_only_counts_for_the_address_it_came_from(self) -> None:
        self.start("gpt-4o", self.fetch_returning(listing("qwen3-8b")))

        self.assertEqual(("gpt-4o", None), self.discovery.select_model("gpt-4o", "https://elsewhere.example.test/v1"))
        model, warning = self.discovery.select_model("gpt-4o", BASE + "/")
        self.assertEqual("gpt-4o", model)
        self.assertIsNotNone(warning)

    def test_junk_in_the_list_is_ignored(self) -> None:
        found = self.start("b", self.fetch_returning({"data": [
            {"id": "a"}, {"id": ""}, {"id": 7}, "b", {"id": "x" * 201}, {"id": "evil\nline"}, {"id": "a"}, {"id": "b"}]}))

        self.assertEqual(("a", "b"), found.models)

    def test_the_real_request_against_a_local_server(self) -> None:
        """The fetch itself, not a fake: path, key header, an HTTP error, and a response too large to be a list."""
        import http.server
        import threading

        seen: list[tuple[str, str]] = []
        limit = self.discovery.MAX_RESPONSE_BYTES

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self_inner):
                seen.append((self_inner.path, self_inner.headers.get("Authorization", "")))
                if self_inner.path.startswith("/missing"):
                    self_inner.send_response(404)
                    self_inner.end_headers()
                    return
                body = (b"x" * (limit + 10) if self_inner.path.startswith("/huge")
                        else json.dumps(listing("qwen3-8b", "qwen-max")).encode())
                self_inner.send_response(200)
                self_inner.send_header("Content-Type", "application/json")
                self_inner.send_header("Content-Length", str(len(body)))
                self_inner.end_headers()
                try:
                    self_inner.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # the client stops reading once it has more than a list could be

            def log_message(self_inner, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_address[1]}"
        try:
            found = self.discovery.discover(base + "/v1", "secret-key", lambda url: None)
            missing = self.discovery.discover(base + "/missing", "secret-key", lambda url: None)
            huge = self.discovery.discover(base + "/huge", "secret-key", lambda url: None)
        finally:
            server.shutdown()
            server.server_close()

        self.assertEqual(("ok", ("qwen3-8b", "qwen-max")), (found.outcome, found.models))
        self.assertEqual(("/v1/models", "Bearer secret-key"), seen[0])
        self.assertEqual(("failed", "HTTP 404"), (missing.outcome, missing.reason))
        self.assertEqual("failed", huge.outcome)
        self.assertIn("too large", huge.reason)

    # ---- Qwen3's thinking switch ---------------------------------------------------------------------------------
    def test_which_models_count_as_qwen3(self) -> None:
        for model in ("qwen3-8b", "Qwen3-235B-A22B", "qwen3-max", "Qwen/Qwen3-32B"):
            self.assertTrue(self.discovery.wants_thinking_off(model), model)
        for model in ("gpt-4o", "qwen-max", "qwen2.5-72b-instruct", "my-qwen3", "", None):
            self.assertFalse(self.discovery.wants_thinking_off(model), model)

    @contextlib.contextmanager
    def captured_request(self, reply: str):
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

    def compatible(self, model: str) -> dict:
        os.environ["AI_API_KEY"] = "test-key"
        return {"provider": "openai-compatible", "model": model, "base_url": BASE, "temperature": 0.2,
                "ai_audit_enabled": True, "ai_audit_sample_percent": 0}

    def test_a_qwen3_model_is_told_not_to_think_in_review_and_in_chat(self) -> None:
        verdict = '{"decision": "APPROVE", "confidence": 0.95, "reason": "正常"}'
        with self.captured_request(verdict) as urlopen:
            self.main.model_review("标题", "正文", self.compatible("qwen3-8b"))
        self.assertIs(False, self.sent(urlopen)["enable_thinking"])
        self.assertEqual("qwen3-8b", self.sent(urlopen)["model"])

        with self.captured_request("回答") as urlopen:
            self.main.compatible_answer("问题", [], self.compatible("qwen3-8b"))
        self.assertIs(False, self.sent(urlopen)["enable_thinking"])

    def test_other_models_are_never_sent_the_parameter(self) -> None:
        for model in ("gpt-4o", "qwen-max", "deepseek-chat"):
            with self.subTest(model):
                with self.captured_request('{"decision": "APPROVE", "confidence": 0.95, "reason": "正常"}') as urlopen:
                    self.main.model_review("标题", "正文", self.compatible(model))
                self.assertNotIn("enable_thinking", self.sent(urlopen))
                with self.captured_request("回答") as urlopen:
                    self.main.compatible_answer("问题", [], self.compatible(model))
                self.assertNotIn("enable_thinking", self.sent(urlopen))

    def test_an_unlisted_model_is_still_called_and_judged_by_its_own_name(self) -> None:
        # The endpoint lists only a Qwen3; the configured gpt-4o is called anyway, so no thinking switch is sent.
        with self.assertLogs("ai.model_discovery", "WARNING"):
            self.start("gpt-4o", self.fetch_returning(listing("qwen3-8b")))
        with self.captured_request("回答") as urlopen:
            self.main.compatible_answer("问题", [], self.compatible("gpt-4o"))
        self.assertEqual("gpt-4o", self.sent(urlopen)["model"])
        self.assertNotIn("enable_thinking", self.sent(urlopen))

        # And the other way round: an unlisted Qwen3 still has thinking turned off.
        with self.assertLogs("ai.model_discovery", "WARNING"):
            self.start("qwen3-32b", self.fetch_returning(listing("gpt-4o")))
        with self.captured_request("回答") as urlopen:
            self.main.compatible_answer("问题", [], self.compatible("qwen3-32b"))
        self.assertEqual("qwen3-32b", self.sent(urlopen)["model"])
        self.assertIs(False, self.sent(urlopen)["enable_thinking"])

    # ---- long content ------------------------------------------------------------------------------------------
    def review(self, body: str, reply: str) -> dict:
        with self.captured_request(reply) as urlopen:
            verdict = self.main.review_content("标题", body, self.compatible("gpt-4o"))
        self.last_prompt = self.sent(urlopen)["messages"][0]["content"] if urlopen.called else ""
        return verdict

    def test_an_approval_of_long_content_goes_to_a_person(self) -> None:
        long_body = "正常的知识内容。" * 600
        self.assertGreater(len(long_body), self.main.MODEL_PREVIEW_CHARS)

        verdict = self.review(long_body, '{"decision": "APPROVE", "confidence": 0.97, "reason": "内容正常"}')

        self.assertEqual("ESCALATE", verdict["decision"])
        self.assertIn(self.main.LONG_CONTENT_NOTE, verdict["reason"])
        self.assertIn(f"共 {len(long_body)} 字", verdict["reason"])
        # ...because the model was shown only this much of it.
        self.assertEqual(self.main.MODEL_PREVIEW_CHARS, len(self.last_prompt.split("正文：", 1)[1]))

    def test_a_rejection_of_long_content_is_still_acted_on(self) -> None:
        verdict = self.review("正常的知识内容。" * 600, '{"decision": "REJECT", "confidence": 0.95, "reason": "包含广告"}')

        self.assertEqual("REJECT", verdict["decision"])

    def test_content_within_the_preview_keeps_the_usual_path(self) -> None:
        approve = '{"decision": "APPROVE", "confidence": 0.97, "reason": "内容正常"}'

        self.assertEqual("APPROVE", self.review("知" * self.main.MODEL_PREVIEW_CHARS, approve)["decision"])
        self.assertEqual("ESCALATE", self.review("知" * (self.main.MODEL_PREVIEW_CHARS + 1), approve)["decision"])

    def test_the_sensitive_word_rules_still_read_past_the_preview(self) -> None:
        body = "正常的知识内容。" * 600 + "\n部署时运行 java -jar app.jar 即可。"
        self.assertTrue(self.main.contains_sensitive_content(body[self.main.MODEL_PREVIEW_CHARS:]),
                        "the example must be something the rules catch")

        with self.captured_request('{"decision": "APPROVE", "confidence": 0.99, "reason": "正常"}') as urlopen:
            verdict = self.main.review_content("标题", body, self.compatible("gpt-4o"))

        self.assertEqual("REJECT", verdict["decision"])
        self.assertIn("敏感词", verdict["reason"])
        urlopen.assert_not_called()

    def test_review_accepts_a_document_as_long_as_the_parser_does(self) -> None:
        self.main.ReviewRequest(text="字" * 2_000_000)
        with self.assertRaises(Exception):
            self.main.ReviewRequest(text="字" * 2_000_001)


if __name__ == "__main__":
    unittest.main()
