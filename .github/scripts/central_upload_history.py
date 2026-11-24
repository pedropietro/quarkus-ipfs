"""Authorize a tag-triggered upload from GitHub Actions run history."""

import json
import os
import re
import subprocess
import sys
from urllib.parse import urlencode


WORKFLOW = "publish-central.yml"
PUBLISH_JOB = "Publish and verify Maven Central artifacts"
UPLOAD_STEP = "Upload the signed bundle"


class UploadHistoryError(Exception):
    pass


def github_list(path, field):
    query = "&" if "?" in path else "?"
    result = subprocess.run(
        ["gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/{path}{query}per_page=100"],
        check=True, capture_output=True, text=True,
    )
    data = json.loads(result.stdout)
    entries = data[field]
    if not isinstance(entries, list) or data["total_count"] != len(entries):
        raise UploadHistoryError("GitHub returned incomplete history.")
    return entries


def upload_was_skipped(jobs):
    matches = [job for job in jobs if job["name"] == PUBLISH_JOB]
    if len(matches) != 1 or matches[0]["status"] != "completed":
        return False
    job = matches[0]
    if job["conclusion"] == "skipped" and not job["steps"]:
        return True
    steps = [step for step in job["steps"] if step["name"] == UPLOAD_STEP]
    return len(steps) == 1 and steps[0]["status"] == "completed" and steps[0]["conclusion"] == "skipped"


def check_upload(tag, commit, run_id, attempt):
    query = urlencode({"branch": tag, "event": "push"})
    runs = github_list(f"actions/workflows/{WORKFLOW}/runs?{query}", "workflow_runs")
    matches = [run for run in runs if run["id"] == run_id]
    if len(matches) != 1 or matches[0]["run_attempt"] != attempt:
        raise UploadHistoryError("GitHub did not return the current workflow attempt.")
    current = matches[0]
    for run in runs:
        if run["event"] != "push" or run["head_branch"] != tag:
            raise UploadHistoryError("GitHub returned an unrelated workflow run.")
        if run["head_sha"] != commit:
            raise UploadHistoryError("Another run used a different commit for this tag.")
        if (run["run_number"] > current["run_number"] and run["run_attempt"] == 1
                and run["status"] in {"queued", "pending", "requested", "waiting"}):
            # A later run has never executed and waits in the per-tag concurrency group.
            continue
        if run["id"] != run_id and run["status"] != "completed":
            raise UploadHistoryError("A previous run for this tag has not completed.")
        attempts = attempt - 1 if run["id"] == run_id else run["run_attempt"]
        if attempts < 0 or (run["id"] != run_id and attempts == 0):
            raise UploadHistoryError("GitHub returned an invalid attempt count.")
        for number in range(1, attempts + 1):
            jobs = github_list(f"actions/runs/{run['id']}/attempts/{number}/jobs", "jobs")
            if not upload_was_skipped(jobs):
                raise UploadHistoryError(f"Upload may have run in workflow {run['id']}, attempt {number}.")


def main():
    try:
        if os.environ["GITHUB_EVENT_NAME"] != "push":
            raise UploadHistoryError("Upload authorization is only used by the tag-triggered workflow.")
        tag = os.environ["GITHUB_REF_NAME"]
        if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
            raise UploadHistoryError("Expected a release tag.")
        check_upload(tag, os.environ["RELEASE_COMMIT"], int(os.environ["GITHUB_RUN_ID"]),
                     int(os.environ["GITHUB_RUN_ATTEMPT"]))
    except UploadHistoryError as error:
        print(f"Upload blocked. {error}", file=sys.stderr)
    except (KeyError, TypeError, ValueError, OSError, subprocess.CalledProcessError):
        print("Upload blocked. Could not verify GitHub Actions history.", file=sys.stderr)
    else:
        print(f"Upload authorized for {tag}. All previous uploads were skipped.")
        return 0
    print("Check Central Portal and resume an existing deployment by ID.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
