"""The upstream that receives the API key is a super administrator's to set; everyone else keeps it as it is."""
import base64
import hashlib
import hmac
import json
import os
import tempfile
import time
import unittest
from unittest import mock


class SuperAdminSettingsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "super_admin_test.db")

        from app import main

        self.main = main
        self.main.init_db()
        audit = mock.patch.object(self.main, "record_admin_action")
        audit.start()
        self.addCleanup(audit.stop)
        self.admin = self.issue_token("helper", 3, "ADMIN")
        self.super_admin = self.issue_token("admin", 2, "ADMIN", super_admin=True)
        self.member = self.issue_token("demo", 1, "USER")

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)

    @staticmethod
    def issue_token(username: str, user_id: int, role: str, super_admin: bool = False) -> str:
        def segment(value: dict) -> str:
            return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

        claims = {"sub": username, "uid": user_id, "role": role,
                  "iat": int(time.time()), "exp": int(time.time()) + 600}
        if super_admin:
            claims["sa"] = True
        header = segment({"alg": "HS256", "typ": "JWT"})
        signing_input = f"{header}.{segment(claims)}"
        signature = base64.urlsafe_b64encode(hmac.new(
            b"local-dev-secret-change-before-production", signing_input.encode(), hashlib.sha256
        ).digest()).decode().rstrip("=")
        return f"Bearer {signing_input}.{signature}"

    def stored(self) -> dict:
        return self.main.read_ai_config()

    def test_a_super_administrator_sets_where_the_key_is_sent(self) -> None:
        saved = self.main.save_ai_config(self.main.AiConfigRequest(
            provider="openai-compatible", model="gpt-4o-mini", base_url="https://upstream.test/v1"
        ), authorization=self.super_admin)

        self.assertEqual("gpt-4o-mini", saved.data["configuration"]["model"])
        self.assertEqual("https://upstream.test/v1", self.stored()["base_url"])

    def test_an_ordinary_administrator_cannot_repoint_the_upstream(self) -> None:
        self.main.save_ai_config(self.main.AiConfigRequest(
            provider="openai-compatible", model="gpt-4o-mini", base_url="https://upstream.test/v1"
        ), authorization=self.super_admin)

        self.main.save_ai_config(self.main.AiConfigRequest(
            provider="local", model="someone-elses-model", base_url="https://attacker.test/v1"
        ), authorization=self.admin)

        settings = self.stored()
        self.assertEqual("gpt-4o-mini", settings["model"], "the model must not have moved")
        self.assertEqual("https://upstream.test/v1", settings["base_url"], "the address must not have moved")
        self.assertEqual("openai-compatible", settings["provider"])

    def test_an_ordinary_administrator_keeps_every_other_setting(self) -> None:
        self.main.save_ai_config(self.main.AiConfigRequest(
            platform_notice="维护公告", max_post_images=4, registration_enabled=False
        ), authorization=self.admin)

        settings = self.stored()
        self.assertEqual("维护公告", settings["platform_notice"])
        self.assertEqual(4, int(settings["max_post_images"]))

    def test_a_member_cannot_reach_the_settings_at_all(self) -> None:
        with self.assertRaises(self.main.HTTPException) as denied:
            self.main.save_ai_config(self.main.AiConfigRequest(platform_notice="x"), authorization=self.member)

        self.assertEqual(403, denied.exception.status_code)

    def test_the_claim_counts_only_on_an_administrator(self) -> None:
        pretender = self.issue_token("demo", 1, "USER", super_admin=True)

        self.assertFalse(self.main.is_super_admin(self.main.token_claims(pretender)))
        self.assertTrue(self.main.is_super_admin(self.main.token_claims(self.super_admin)))
        self.assertFalse(self.main.is_super_admin(self.main.token_claims(self.admin)))

    def test_a_private_upstream_is_refused_even_for_a_super_administrator(self) -> None:
        with self.assertRaises(self.main.HTTPException) as refused:
            self.main.save_ai_config(self.main.AiConfigRequest(
                provider="openai-compatible", request_url="http://127.0.0.1:11434/v1/chat/completions"
            ), authorization=self.super_admin)

        self.assertEqual(400, refused.exception.status_code)


if __name__ == "__main__":
    unittest.main()
