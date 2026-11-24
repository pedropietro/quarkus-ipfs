import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlsplit
from urllib.error import HTTPError, URLError

from central_publication import ARTIFACTS, main
from central_upload_history import PUBLISH_JOB, UPLOAD_STEP, UploadHistoryError, check_upload
from maven_verify import verify


DEPLOYMENT = "77b32e39-e307-4886-a93a-e0c7c6d66471"
PURLS = [f"pkg:maven/io.github.pedropietro/{artifact}@0.4.0" for artifact in ARTIFACTS]


class CentralPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.bundle = Path(self.temp, "bundle.zip")
        self.bundle.write_bytes(b"test bundle")
        self.checkpoint = Path(self.temp, "deployment.json")
        self.output = Path(self.temp, "github-output")
        self.summary = Path(self.temp, "github-summary")
        self.enterContext(patch.dict(os.environ, {
            "MAVEN_CENTRAL_USERNAME": "fake-user",
            "MAVEN_CENTRAL_PASSWORD": "fake-password",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_OUTPUT": str(self.output),
            "GITHUB_STEP_SUMMARY": str(self.summary),
        }))
        self.console = self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.stderr = self.enterContext(contextlib.redirect_stderr(io.StringIO()))
        self.opener = self.enterContext(patch("urllib.request.OpenerDirector.open"))

    def upload(self):
        return main(["--version", "0.4.0", "upload", "--bundle", str(self.bundle),
                     "--checkpoint", str(self.checkpoint)])

    def wait(self):
        return main(["--version", "0.4.0", "wait", "--deployment-id", DEPLOYMENT, "--timeout", "2", "--interval", "1"])

    def response(self, state="PUBLISHED", **values):
        result = {"deploymentId": DEPLOYMENT, "deploymentState": state, "purls": PURLS} | values
        return io.BytesIO(json.dumps(result).encode())

    def endpoints(self):
        return [urlsplit(call.args[0].full_url).path.rsplit("/", 1)[-1] for call in self.opener.call_args_list]

    def test_upload_records_id_before_wait_and_resume_never_uploads_again(self):
        self.opener.side_effect = [io.BytesIO(DEPLOYMENT.encode()), self.response("PUBLISHING"), self.response()]
        self.assertEqual(0, self.upload())
        upload_request = self.opener.call_args.args[0]
        self.assertEqual(["AUTOMATIC"], parse_qs(urlsplit(upload_request.full_url).query)["publishingType"])
        self.assertIn(b"test bundle", upload_request.data)
        self.assertNotIn("fake-password", self.console.getvalue())
        self.assertEqual({"deployment_id": DEPLOYMENT, "release_tag": "v0.4.0"},
                         json.loads(self.checkpoint.read_text(encoding="utf-8")))
        self.assertIn(DEPLOYMENT, self.output.read_text(encoding="utf-8"))
        self.assertIn(DEPLOYMENT, self.summary.read_text(encoding="utf-8"))

        with patch("central_publication.time") as clock:
            clock.monotonic.side_effect = [0, 0, 2, 2]
            self.assertEqual(1, self.wait())
        self.assertIn("Timed out", self.stderr.getvalue())
        self.assertEqual(0, self.wait())
        self.assertEqual(["upload", "status", "status"], self.endpoints())

    def test_recovery_uploads_once_after_skipped_attempt_and_then_waits_by_id(self):
        tag, commit, run_id = "v0.4.0", "a" * 40, 100
        run = {"id": run_id, "run_number": 1, "run_attempt": 2, "head_branch": tag,
               "head_sha": commit, "status": "in_progress", "event": "push"}
        skipped = {"name": PUBLISH_JOB, "status": "completed", "conclusion": "skipped", "steps": []}
        uploaded = {"name": PUBLISH_JOB, "status": "completed", "conclusion": "failure", "steps": [
            {"name": UPLOAD_STEP, "status": "completed", "conclusion": "success"},
        ]}
        self.opener.side_effect = [io.BytesIO(DEPLOYMENT.encode()), self.response()]
        with patch("central_upload_history.github_list", side_effect=[[run], [skipped]]):
            check_upload(tag, commit, run_id, 2)
            self.assertEqual(0, self.upload())

        with patch("central_upload_history.github_list", side_effect=[
            [run | {"run_attempt": 3}], [skipped], [uploaded],
        ]):

            with self.assertRaises(UploadHistoryError):
                check_upload(tag, commit, run_id, 3)
        self.assertEqual(0, self.wait())
        self.assertEqual(["upload", "status"], self.endpoints())

    def verify_with_429(self, next_status):
        def attempt(status, line):
            process = MagicMock()
            process.__enter__.return_value = process
            process.stdout = io.StringIO(line + "\n")
            process.wait.return_value = status
            return process

        rate_limit = "[FATAL] Could not transfer artifact parent:pom:1 from/to central: HTTP Status: 429"
        with (patch("maven_verify.subprocess.Popen", side_effect=[
            attempt(1, rate_limit), attempt(next_status, rate_limit if next_status else "[INFO] BUILD SUCCESS"),
        ]), patch("maven_verify.time.sleep")):
            return verify([])

    def test_recovered_build_leaves_upload_guard_in_effect(self):
        self.assertEqual(0, self.verify_with_429(0))
        self.assertEqual([], self.endpoints())
        self.opener.return_value = io.BytesIO(DEPLOYMENT.encode())
        self.assertEqual(0, self.upload())
        self.assertEqual(1, self.upload())
        self.assertEqual(["upload"], self.endpoints())

    def test_persistent_rate_limit_stops_before_upload(self):
        self.assertEqual(1, self.verify_with_429(1))
        self.assertEqual([], self.endpoints())
        self.assertFalse(self.checkpoint.exists())

    def test_ignores_transient_status_errors_and_waits_for_publication(self):
        self.opener.side_effect = [
            HTTPError("https://central.sonatype.com", 503, "unavailable", {}, None),
            self.response("VALIDATING"),
            self.response("PUBLISHING"),
            self.response(),
        ]
        with patch("central_publication.time.sleep"):
            self.assertEqual(0, self.wait())
        self.assertEqual(["status"] * 4, self.endpoints())

    def test_rejects_failed_deployment(self):
        self.opener.return_value = self.response("FAILED", errors={"bundle": ["validation failed"]})
        self.assertEqual(1, self.wait())
        self.assertIn("validation failed", self.stderr.getvalue())

    def test_rejects_different_release(self):
        self.opener.return_value = self.response(purls=[purl.replace("@0.4.0", "@0.3.0") for purl in PURLS])
        self.assertEqual(1, self.wait())
        self.assertIn("does not contain the four v0.4.0 artifacts", self.stderr.getvalue())

    def test_rejects_a_different_deployment_id(self):
        self.opener.return_value = self.response(deploymentId="e7a31fb2-706f-4e5b-ae93-57ff200dad82")
        self.assertEqual(1, self.wait())
        self.assertIn("different deployment", self.stderr.getvalue())

    def test_local_upload_cannot_repeat_after_success(self):
        self.opener.return_value = io.BytesIO(DEPLOYMENT.encode())
        self.assertEqual(0, self.upload())
        self.assertEqual(1, self.upload())
        self.assertEqual(["upload"], self.endpoints())

    def test_ambiguous_upload_is_not_retried(self):
        self.opener.side_effect = URLError("timed out")
        self.assertEqual(1, self.upload())
        self.assertEqual(["upload"], self.endpoints())
        self.assertFalse(self.checkpoint.exists())
        self.assertEqual(1, self.upload())
        self.assertEqual(["upload"], self.endpoints())
        self.assertIn("Upload result unknown", self.stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
