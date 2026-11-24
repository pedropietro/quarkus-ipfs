#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: unixfs-stream.sh jvm|native path-to-app}
artifact=${2:?Pass the packaged integration-test application}
source "$(dirname "${BASH_SOURCE[0]}")/kubo-test-lib.sh"
check_artifact "$mode" "$artifact"

network=quarkus-unixfs-stream-$$-$RANDOM
app="$network-app"
trap 'cleanup_network "$?" "$network" "$app"' EXIT
docker network create --internal "$network" >/dev/null
APP_MAX_HEAP=64m start_app "$app" "$mode" "$artifact" --network "$network" \
    -e QUARKUS_IPFS_STORAGE_PATH=/tmp/unixfs-blocks -e QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=128M
await_ready docker exec "$app" curl --fail --silent --max-time 1 http://127.0.0.1:8080/ipfs/node/peer-id

docker exec "$app" bash -euo pipefail -c '
    url=http://127.0.0.1:8080/unixfs/file
    # Random data exercises distinct FILE blocks and keeps generation out of the application heap.
    dd if=/dev/urandom of=/tmp/source.bin bs=1M count=96 status=none
    cid=$(curl -fsS --max-time 120 -X POST -H "Content-Type: application/octet-stream" -T /tmp/source.bin "$url/stream")
    curl -fsS --max-time 120 -D /tmp/headers -o /tmp/read.bin "$url/$cid/stream"
    heap=$(awk "tolower(\$1) == \"x-test-max-heap:\" { print \$2 + 0 }" /tmp/headers)
    size=$(wc -c </tmp/source.bin)
    blocks=$(find /tmp/unixfs-blocks/blocks -type f -name "*.data" | wc -l)
    test "$heap" -gt 0
    test "$heap" -le "$((64 * 1024 * 1024))"
    test "$size" -gt "$heap"
    test "$blocks" -ge 384
    cmp /tmp/source.bin /tmp/read.bin
    printf "UnixFS streaming matched %s bytes with a %s-byte heap and %s blocks on disk.\n" "$size" "$heap" "$blocks"
'
