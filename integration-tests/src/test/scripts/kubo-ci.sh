#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: kubo-ci.sh jvm|native path-to-app log-directory}
artifact=${2:?Pass the packaged application}
logs=${3:?Pass the log directory}
scripts=$(dirname "${BASH_SOURCE[0]}")
source "$scripts/kubo-test-lib.sh"
check_artifact "$mode" "$artifact"
mkdir -p "$logs"
printf 'Kubo image: %s\n' "$kubo_image"

failed=0
for scenario in unixfs ipns http; do
    printf '\n=== %s Kubo interoperability (%s) ===\n' "$scenario" "$mode"
    if ! bash "$scripts/kubo-$scenario.sh" "$mode" "$artifact" 2>&1 | tee "$logs/kubo-$scenario.log"; then
        printf 'Kubo interoperability failed: %s (%s)\n' "$scenario" "$mode" >&2
        failed=1
    fi
done
exit "$failed"
