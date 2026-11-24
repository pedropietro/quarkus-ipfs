"""Upload a signed Central bundle or wait for an existing deployment."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


PORTAL = "https://central.sonatype.com/api/v1/publisher"
GROUP = "io.github.pedropietro"
ARTIFACTS = ("quarkus-ipfs", "quarkus-ipfs-deployment", "quarkus-ipfs-nabu", "quarkus-ipfs-parent")
PENDING = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"}
TRANSIENT = {408, 429, 500, 502, 503, 504}


class PublicationError(Exception):
    pass


class RetryableStatus(PublicationError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class CentralPortal:
    def __init__(self, username, password):
        if not username or not password:
            raise PublicationError("Configure the Maven Central token secrets.")
        self.authorization = "Bearer " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.opener = urllib.request.build_opener(NoRedirect())

    def post(self, endpoint, params, *, data=b"", content_type=None, status_query=False):
        url = f"{PORTAL}/{endpoint}?{urllib.parse.urlencode(params)}"
        headers = {"Authorization": self.authorization}
        if content_type is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, headers=headers, method="POST")
        try:
            with self.opener.open(request, timeout=30 if status_query else 120) as response:
                return response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            if status_query and error.code in TRANSIENT:
                raise RetryableStatus(f"Central HTTP {error.code}") from None
            raise PublicationError(f"Central {endpoint} returned HTTP {error.code}.") from None
        except (urllib.error.URLError, TimeoutError):
            if status_query:
                raise RetryableStatus("Central status request could not be reached.") from None
            raise PublicationError("Upload result unknown. Check Central Portal before submitting another bundle.") from None

    def upload(self, bundle, version):
        boundary = uuid.uuid4().hex
        prefix = (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode()
        body = b"".join((prefix, bundle.read_bytes(), f"\r\n--{boundary}--\r\n".encode()))
        reply = self.post("upload", {"name": f"quarkus-ipfs-v{version}", "publishingType": "AUTOMATIC"},
                          data=body, content_type=f"multipart/form-data; boundary={boundary}")
        return deployment_uuid(reply.strip())

    def status(self, deployment_id):
        reply = self.post("status", {"id": deployment_id}, status_query=True)
        try:
            result = json.loads(reply)
        except ValueError:
            raise PublicationError("Central returned invalid status JSON.") from None
        if not isinstance(result, dict) or result.get("deploymentId") != deployment_id:
            raise PublicationError("Central returned a status for a different deployment.")
        return result


def deployment_uuid(value):
    try:
        return str(uuid.UUID(value))
    except ValueError:
        raise PublicationError("Expected a Central deployment UUID.") from None


def checkpoint(path, version, deployment_id):
    data = {"deployment_id": deployment_id, "release_tag": f"v{version}"}
    print(f"Deployment ID {deployment_id}", flush=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            print(f"deployment_id={deployment_id}", file=stream)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            print(f"Central deployment for v{version} is \x60{deployment_id}\x60.", file=stream)
            print("Use **Run workflow** on main with this tag and ID to resume.", file=stream)


def wait_for_release(portal, deployment_id, version, timeout, interval):
    deadline = time.monotonic() + timeout
    expected = {f"pkg:maven/{GROUP}/{artifact}@{version}" for artifact in ARTIFACTS}
    while time.monotonic() < deadline:
        try:
            status = portal.status(deployment_id)
        except RetryableStatus as error:
            print(f"Retrying status for {deployment_id}. {error}", flush=True)
        else:
            state = status.get("deploymentState")
            if state == "PUBLISHED":
                purls = status.get("purls")
                if not isinstance(purls, list) or not all(isinstance(purl, str) for purl in purls):
                    raise PublicationError("Central did not return published artifact coordinates.")
                if not expected.issubset(purls):
                    raise PublicationError(f"Deployment {deployment_id} does not contain the four v{version} artifacts.")
                print(f"Deployment {deployment_id} published v{version}.", flush=True)
                return
            if state == "FAILED":
                details = json.dumps(status.get("errors", {}), ensure_ascii=True)
                raise PublicationError(f"Deployment {deployment_id} failed. Central errors {details}")
            if not isinstance(state, str) or state not in PENDING:
                raise PublicationError(f"Unexpected Central deployment state {json.dumps(state)}.")
            print(f"Deployment {deployment_id} state {state}", flush=True)
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(interval, remaining))
    raise PublicationError(
        f"Timed out waiting for deployment {deployment_id}. Resume with v{version} and the same deployment ID."
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    upload = commands.add_parser("upload")
    upload.add_argument("--bundle", type=Path, required=True)
    upload.add_argument("--checkpoint", type=Path, required=True)
    wait = commands.add_parser("wait")
    wait.add_argument("--deployment-id", required=True)
    wait.add_argument("--timeout", type=int, default=3600)
    wait.add_argument("--interval", type=int, default=30)
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", args.version):
            raise PublicationError("Expected a MAJOR.MINOR.PATCH version.")
        portal = CentralPortal(os.environ.get("MAVEN_CENTRAL_USERNAME"), os.environ.get("MAVEN_CENTRAL_PASSWORD"))
        if args.command == "upload":
            marker = args.checkpoint.with_suffix(args.checkpoint.suffix + ".attempted")
            try:
                if args.checkpoint.exists():
                    raise FileExistsError
                marker.touch(exist_ok=False)
            except FileExistsError:
                raise PublicationError("Upload already attempted. Check Central Portal and resume by deployment ID.") from None
            checkpoint(args.checkpoint, args.version, portal.upload(args.bundle, args.version))
        else:
            if args.timeout < 1 or args.interval < 1:
                raise PublicationError("The wait timeout and interval must be positive.")
            wait_for_release(portal, deployment_uuid(args.deployment_id), args.version, args.timeout, args.interval)
        return 0
    except (PublicationError, OSError) as error:
        print(f"Publication stopped. {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
