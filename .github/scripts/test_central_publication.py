import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from urllib.error import HTTPError, URLError

from central_publication import ARTIFACTS, main


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

    def test_rerun_does_not_attempt_upload(self):
        with patch.dict(os.environ, {"GITHUB_RUN_ATTEMPT": "2"}):
            self.assertEqual(1, self.upload())
        self.assertEqual([], self.endpoints())

    def test_ambiguous_upload_is_not_retried(self):
        self.opener.side_effect = URLError("timed out")
        self.assertEqual(1, self.upload())
        self.assertEqual(["upload"], self.endpoints())
        self.assertFalse(self.checkpoint.exists())
        self.assertIn("Upload result unknown", self.stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
