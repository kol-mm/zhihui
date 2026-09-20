import base64
import hashlib
import hmac
import json
import os
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock


class AdminAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "ai_audit_test.db")
        from app import main

        self.main = main
        self.main.init_db()
        self.admin = self.issue_token("admin", 2, "ADMIN")
        self.member = self.issue_token("demo", 1, "USER")
        self.sent: list[dict] = []
        patcher = mock.patch.object(self.main, "record_admin_action", side_effect=self.capture)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)

    def capture(self, claims, client_ip, action, target_type, target_id, target_label, summary, detail=None):
        self.sent.append(self.main.build_audit_entry(claims, client_ip, action, target_type, target_id,
                                                     target_label, summary, detail))

    @staticmethod
    def issue_token(username: str, user_id: int, role: str) -> str:
        secret = os.getenv("AI_KNOWLEDGE_JWT_SECRET", "local-dev-secret-change-before-production").encode()
        encode = lambda data: base64.urlsafe_b64encode(data).rstrip(b"=").decode()
        now = int(time.time())
        header = encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        payload = encode(json.dumps({"sub": username, "uid": user_id, "role": role, "iat": now, "exp": now + 600}).encode())
        signature = encode(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
        return f"Bearer {header}.{payload}.{signature}"

    def config(self, **changes):
        current = self.main.read_ai_config()
        values = {key: current[key] for key in self.main.AiConfigRequest.model_fields if key in current}
        values.update(changes)
        return self.main.AiConfigRequest(**values)

    def test_settings_changes_are_recorded_with_before_and_after(self) -> None:
        self.main.save_ai_config(self.config(registration_enabled=False, platform_name="新名称"),
                                 authorization=self.admin, x_client_ip="203.0.113.5")
        self.assertEqual(1, len(self.sent))
        entry = self.sent[0]
        self.assertEqual("AI_CONFIG_SAVE", entry["action"])
        self.assertEqual("SYSTEM", entry["category"])
        self.assertEqual(2, entry["actorId"])
        self.assertEqual("admin", entry["actorName"])
        self.assertEqual("203.0.113.5", entry["clientIp"])
        self.assertTrue(entry["occurredAt"].endswith("Z"))
        changes = {change["field"]: change for change in entry["detail"]["changes"]}
        self.assertEqual({"platform_name", "registration_enabled"}, set(changes))
        self.assertEqual((True, False), (changes["registration_enabled"]["before"], changes["registration_enabled"]["after"]))
        self.assertIn("开放注册", entry["summary"])

    def test_the_profile_review_switch_round_trips_and_is_named(self) -> None:
        self.assertIs(False, self.main.read_ai_config()["profile_audit_required"], "off unless an admin turns it on")
        self.main.save_ai_config(self.config(profile_audit_required=True), authorization=self.admin, x_client_ip=None)
        self.assertIs(True, self.main.read_ai_config()["profile_audit_required"])
        self.assertIs(True, self.main.public_config().data["profile_audit_required"], "services read the public config")
        changes = {change["field"]: change for change in self.sent[0]["detail"]["changes"]}
        self.assertEqual("资料修改先审后改", changes["profile_audit_required"]["label"])
        self.assertEqual((False, True), (changes["profile_audit_required"]["before"], changes["profile_audit_required"]["after"]))

    def test_saving_unchanged_settings_records_nothing(self) -> None:
        self.main.save_ai_config(self.config(), authorization=self.admin, x_client_ip=None)
        self.assertEqual([], self.sent)

    def test_credentials_in_urls_and_file_lists_are_not_kept(self) -> None:
        # The endpoint already refuses credentials inside a URL; the log helper drops them anyway.
        self.assertEqual("https://api.example.com/v1", self.main._audit_value("base_url", "https://user:secret@api.example.com/v1"))
        self.main.save_ai_config(self.config(provider="openai-compatible", base_url="https://api.example.com/v1?key=abc",
                                             data_source_scope="admin-selected", selected_file_ids=[3, 1, 2]),
                                 authorization=self.admin, x_client_ip=None)
        text = json.dumps(self.sent, ensure_ascii=False)
        self.assertNotIn("secret", text)
        self.assertNotIn("key=abc", text)
        changes = {change["field"]: change for change in self.sent[0]["detail"]["changes"]}
        self.assertEqual("https://api.example.com/v1", changes["base_url"]["after"])
        self.assertEqual("3 个文件", changes["selected_file_ids"]["after"])

    def test_members_cannot_change_settings(self) -> None:
        with self.assertRaises(self.main.HTTPException):
            self.main.save_ai_config(self.config(registration_enabled=False), authorization=self.member, x_client_ip=None)
        self.assertEqual([], self.sent)

    def test_only_the_first_batch_of_a_rebuild_is_recorded(self) -> None:
        document = self.main.TextRequest(file_id=1, title="标题", text="正文内容足够长，可以被切分成片段。")
        self.main.rebuild_index(self.main.RebuildIndexRequest(documents=[document], reset=True),
                                authorization=self.admin, x_client_ip=None)
        self.main.rebuild_index(self.main.RebuildIndexRequest(documents=[document], reset=False),
                                authorization=self.admin, x_client_ip=None)
        self.assertEqual(["AI_INDEX_REBUILD"], [entry["action"] for entry in self.sent])


class AuditDeliveryTest(unittest.TestCase):
    def setUp(self) -> None:
        from app import main

        self.main = main
        self.received: list[tuple[str, dict]] = []
        self.responses: list[int] = []
        test = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])).decode("utf-8"))
                test.received.append((self.headers.get("X-Internal-Token"), body))
                code = test.responses.pop(0) if test.responses else 0
                payload = json.dumps({"code": code, "message": "ok", "data": {}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.env = mock.patch.dict(os.environ, {
            "PLATFORM_AUDIT_URL": f"http://127.0.0.1:{self.server.server_port}/user/internal/audit",
            "PLATFORM_INTERNAL_USER_TOKEN": "test-internal-token",
        })
        self.env.start()
        self.delays = mock.patch.object(self.main, "AUDIT_RETRY_DELAYS", (0, 0, 0))
        self.delays.start()

    def tearDown(self) -> None:
        self.delays.stop()
        self.env.stop()
        self.server.shutdown()
        self.server.server_close()

    def entry(self):
        return self.main.build_audit_entry({"uid": 2, "sub": "admin"}, "203.0.113.5", "AI_CONFIG_SAVE",
                                           "PLATFORM_CONFIG", None, "平台与 AI 设置", "修改了开放注册")

    def test_delivers_with_the_internal_token(self) -> None:
        self.assertTrue(self.main.deliver_audit_entry(self.entry()))
        token, body = self.received[0]
        self.assertEqual("test-internal-token", token)
        self.assertEqual("修改了开放注册", body["summary"])

    def test_retries_and_logs_what_could_not_be_delivered(self) -> None:
        self.responses[:] = [500, 500, 500]
        with self.assertLogs("ai.admin_audit", level="ERROR") as logs:
            self.assertFalse(self.main.deliver_audit_entry(self.entry()))
        self.assertEqual(3, len(self.received))
        self.assertIn("修改了开放注册", logs.output[0])

    def test_a_later_attempt_can_succeed(self) -> None:
        self.responses[:] = [500]
        self.assertTrue(self.main.deliver_audit_entry(self.entry()))
        self.assertEqual(2, len(self.received))


if __name__ == "__main__":
    unittest.main()
