import contextlib
import io
import unittest
from unittest.mock import MagicMock, patch

from maven_verify import RETRY_DELAY, verify


PARENT_429 = (
    "\033[1;31m[FATAL]\033[0m Non-resolvable parent POM: Could not transfer artifact "
    "io.quarkiverse:quarkiverse-parent:pom:22 from/to central (https://repo.maven.apache.org/maven2): "
    "HTTP Status: 429 (additional failures: artifact not found in jitpack.io)"
)
DEPENDENCY_503 = (
    "[ERROR] Failed to execute goal: Could not transfer artifact org.example:module:pom:1 "
    "from/to central (https://repo.maven.apache.org/maven2): status code: 503, reason phrase: Service Unavailable"
)


class MavenVerificationTest(unittest.TestCase):
    def setUp(self):
        self.output = self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.process = self.enterContext(patch("maven_verify.subprocess.Popen"))
        self.sleep = self.enterContext(patch("maven_verify.time.sleep"))

    def results(self, *attempts):
        processes = []
        for status, output in attempts:
            maven = MagicMock()
            maven.__enter__.return_value = maven
            maven.wait.return_value = status
            maven.stdout = io.StringIO(output + "\n")
            processes.append(maven)
        self.process.side_effect = processes

    def test_retries_transient_429_once_and_preserves_native_arguments(self):
        self.results((1, PARENT_429), (0, "[INFO] BUILD SUCCESS"))
        flags = ["-pl", "deployment,integration-tests", "-am", "-Dnative"]
        self.assertEqual(0, verify(flags))
        expected = ["bash", "./mvnw", "-B", "clean", "verify", "-Dno-format", *flags]
        self.assertEqual([expected, expected], [call.args[0] for call in self.process.call_args_list])
        self.sleep.assert_called_once_with(RETRY_DELAY)
        self.assertIn(PARENT_429, self.output.getvalue())
        self.assertIn("[INFO] BUILD SUCCESS", self.output.getvalue())

    def test_persistent_429_fails_after_one_retry(self):
        self.results((1, PARENT_429), (2, PARENT_429))
        self.assertEqual(2, verify([]))
        self.assertEqual(2, self.process.call_count)
        self.sleep.assert_called_once_with(RETRY_DELAY)

    def test_retries_transient_dependency_transfer_with_other_maven_format(self):
        self.results((1, DEPENDENCY_503), (0, "[INFO] BUILD SUCCESS"))
        self.assertEqual(0, verify([]))
        self.assertEqual(2, self.process.call_count)

    def test_test_failure_is_not_retried_even_when_transfer_error_is_present(self):
        self.results((1, PARENT_429 + "\n[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0"))
        self.assertEqual(1, verify([]))
        self.assertEqual(1, self.process.call_count)
        self.sleep.assert_not_called()

    def test_warnings_and_nontransient_errors_do_not_trigger_retry(self):
        for output in (
            DEPENDENCY_503.replace("[ERROR]", "[WARNING]") + "\n[ERROR] Build failed",
            PARENT_429.replace("429", "403"),
            "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 (expected HTTP 429)",
        ):
            with self.subTest(output=output):
                self.process.reset_mock()
                self.results((1, output))
                self.assertEqual(1, verify([]))
                self.assertEqual(1, self.process.call_count)
        self.sleep.assert_not_called()

    def test_success_does_not_retry_and_ignores_transfer_warnings(self):
        self.results((0, DEPENDENCY_503.replace("[ERROR]", "[WARNING]")))
        self.assertEqual(0, verify([]))
        self.assertEqual(1, self.process.call_count)
        self.sleep.assert_not_called()

    def test_terminated_build_is_not_retried(self):
        self.results((-15, PARENT_429))
        self.assertEqual(143, verify([]))
        self.assertEqual(1, self.process.call_count)
        self.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
