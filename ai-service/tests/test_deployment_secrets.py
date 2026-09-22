"""The service must not serve traffic with the secrets published in this repository."""
import os
import tempfile
import unittest


class DeploymentSecretsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        os.environ["AI_DB_PATH"] = os.path.join(self.tmpdir.name, "secrets_test.db")

        from app import main

        self.main = main
        self.real_secret = "a-secret-of-our-own-31fe"
        self.real_token = "another-secret-88ab"

    def tearDown(self) -> None:
        self.tmpdir.cleanup()
        os.environ.pop("AI_DB_PATH", None)

    def problem(self, secret, token, allow_defaults=False):
        return self.main.deployment_secret_problem(secret, token, allow_defaults)

    def test_a_configured_deployment_may_serve(self) -> None:
        self.assertIsNone(self.problem(self.real_secret, self.real_token))

    def test_the_published_signing_key_stops_the_service(self) -> None:
        problem = self.problem(self.main.DEVELOPMENT_JWT_SECRET, self.real_token)

        self.assertIsNotNone(problem)
        self.assertIn("AI_KNOWLEDGE_JWT_SECRET", problem)

    def test_a_missing_signing_key_stops_the_service(self) -> None:
        self.assertIsNotNone(self.problem(None, self.real_token))
        self.assertIsNotNone(self.problem("   ", self.real_token))

    def test_the_published_internal_token_stops_the_service(self) -> None:
        problem = self.problem(self.real_secret, self.main.DEVELOPMENT_INTERNAL_TOKEN)

        self.assertIsNotNone(problem)
        self.assertIn("PLATFORM_INTERNAL_USER_TOKEN", problem)

    def test_an_absent_internal_token_is_left_alone(self) -> None:
        self.assertIsNone(self.problem(self.real_secret, None))

    def test_local_work_can_say_the_defaults_are_intended(self) -> None:
        self.assertIsNone(self.problem(None, None, allow_defaults=True))
        self.assertIsNone(self.problem(self.main.DEVELOPMENT_JWT_SECRET,
                                       self.main.DEVELOPMENT_INTERNAL_TOKEN, allow_defaults=True))


if __name__ == "__main__":
    unittest.main()
