import contextlib
import io
import os
import subprocess
import unittest
from unittest.mock import patch

from central_upload_history import PUBLISH_JOB, UPLOAD_STEP, UploadHistoryError, github_list, main


TAG = "v0.5.0"
COMMIT = "a" * 40
CURRENT = 120
OLDER = 110
NEWER = 130


def run_entry(run_id=CURRENT, attempt=1, status="completed", **changes):
    return {
        "id": run_id, "run_number": run_id, "run_attempt": attempt, "head_branch": TAG,
        "head_sha": COMMIT, "status": status, "event": "push",
    } | changes


def publish_job(conclusion, upload=None):
    steps = [] if upload is None else [{"name": UPLOAD_STEP, "status": "completed", "conclusion": upload}]
    return {"name": PUBLISH_JOB, "status": "completed", "conclusion": conclusion, "steps": steps}


class CentralUploadHistoryTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG, "RELEASE_COMMIT": COMMIT,
            "GITHUB_RUN_ID": str(CURRENT), "GITHUB_RUN_ATTEMPT": "1",
        }))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.errors = self.enterContext(contextlib.redirect_stderr(io.StringIO()))
        self.history = self.enterContext(patch("central_upload_history.github_list"))

    def authorize(self, runs, *jobs, attempt=1):
        self.history.side_effect = [runs, *jobs]
        with patch.dict(os.environ, {"GITHUB_RUN_ATTEMPT": str(attempt)}):
            return main()

    def test_first_attempt_can_upload(self):
        self.assertEqual(0, self.authorize([run_entry()]))
        self.assertEqual(1, self.history.call_count)

    def test_all_prior_attempts_explicitly_skipped_upload(self):
        self.assertEqual(0, self.authorize(
            [run_entry(attempt=3)], [publish_job("skipped")], [publish_job("failure", "skipped")], attempt=3))
        self.assertEqual(
            [f"actions/runs/{CURRENT}/attempts/{n}/jobs" for n in (1, 2)],
            [call.args[0] for call in self.history.call_args_list[1:]],
        )

    def test_completed_upload_blocks_rerun_even_after_later_skipped_attempt(self):
        self.assertEqual(1, self.authorize(
            [run_entry(attempt=3)], [publish_job("failure", "success")], [publish_job("skipped")], attempt=3))
        self.assertIn("resume an existing deployment by ID", self.errors.getvalue())

    def test_failed_or_cancelled_upload_is_ambiguous(self):
        for state in ("failure", "cancelled"):
            with self.subTest(state=state):
                self.assertEqual(1, self.authorize(
                    [run_entry(attempt=2)], [publish_job(state, state)], attempt=2))

    def test_missing_upload_evidence_is_ambiguous(self):
        for jobs in ([], [publish_job("failure")]):
            with self.subTest(jobs=jobs):
                self.assertEqual(1, self.authorize([run_entry(attempt=2)], jobs, attempt=2))

    def test_second_tag_run_checks_the_first_runs_attempt(self):
        runs = [run_entry(), run_entry(OLDER)]
        self.assertEqual(1, self.authorize(runs, [publish_job("success", "success")]))
        self.assertEqual(0, self.authorize(runs, [publish_job("skipped")]))

    def test_rerun_of_older_workflow_cannot_duplicate_a_later_upload(self):
        self.assertEqual(1, self.authorize(
            [run_entry(NEWER), run_entry(attempt=2)], [publish_job("success", "success")], attempt=2))

    def test_unstarted_later_workflow_waits_for_current_workflow(self):
        self.assertEqual(0, self.authorize([run_entry(NEWER, status="queued"), run_entry()]))

    def test_moved_tag_is_blocked(self):
        self.assertEqual(1, self.authorize([run_entry(), run_entry(OLDER, head_sha="b" * 40)]))
        self.assertIn("different commit", self.errors.getvalue())

    def test_unavailable_history_blocks_upload(self):
        self.history.side_effect = subprocess.CalledProcessError(1, ["gh", "api"])
        self.assertEqual(1, main())
        self.assertIn("Could not verify", self.errors.getvalue())

    def test_manual_resume_does_not_use_upload_authorization(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch"}):
            self.assertEqual(1, main())
        self.history.assert_not_called()


class GitHubListTest(unittest.TestCase):
    def test_truncated_api_response_is_rejected(self):
        with (patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}),
              patch("central_upload_history.subprocess.run") as gh):
            gh.return_value.stdout = '{"total_count": 101, "jobs": []}'
            with self.assertRaises(UploadHistoryError):
                github_list("actions/runs/100/attempts/1/jobs", "jobs")
            self.assertTrue(gh.call_args.args[0][-1].endswith("?per_page=100"))


if __name__ == "__main__":
    unittest.main()
