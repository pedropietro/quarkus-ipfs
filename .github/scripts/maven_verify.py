"""Run Maven verification, retrying once on a transient repository transfer error."""

import re
import subprocess
import sys
import time


RETRY_DELAY = 15
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
TRANSFER_FAILURE = re.compile(
    r"^\[(?:ERROR|FATAL)\].*\bCould not transfer artifact\b.*"
    r"\b(?:HTTP status|status code):?\s*(?:429|502|503|504)\b",
    re.IGNORECASE,
)
BUILD_FAILURE = re.compile(
    r"\b(?:Failures|Errors):\s*[1-9][0-9]*\b|<<< (?:FAILURE|ERROR)!|"
    r"There are test failures|COMPILATION ERROR|"
    r"Failed to execute goal.*(?:maven-surefire-plugin|maven-failsafe-plugin)",
    re.IGNORECASE,
)


def verify(arguments):
    command = ["bash", "./mvnw", "-B", "clean", "verify", "-Dno-format", *arguments]
    for attempt in range(2):
        transfer_failure = False
        build_failure = False
        with subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ) as maven:
            for line in maven.stdout:
                print(line, end="", flush=True)
                clean_line = ANSI.sub("", line)
                transfer_failure |= bool(TRANSFER_FAILURE.search(clean_line))
                build_failure |= bool(BUILD_FAILURE.search(clean_line))
            status = maven.wait()

        if status <= 0 or attempt or not transfer_failure or build_failure:
            return status if status >= 0 else 128 - status

        print(f"Transient Maven artifact transfer failure. Retrying verification once in {RETRY_DELAY}s.", flush=True)
        time.sleep(RETRY_DELAY)


if __name__ == "__main__":
    sys.exit(verify(sys.argv[1:]))
