#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: kubo-http.sh jvm|native path-to-app}
artifact=${2:?Pass the quarkus-app directory or the Linux native executable}
source "$(dirname "${BASH_SOURCE[0]}")/kubo-test-lib.sh"
check_artifact "$mode" "$artifact"
network=quarkus-kubo-http-$$-$RANDOM
app="$network-app"
kubo="$network-kubo"

trap 'cleanup_network "$?" "$network" "$app" "$kubo"' EXIT
docker network create --internal "$network" >/dev/null
docker run -d --name "$kubo" --network "$network" \
    --entrypoint sh "$kubo_image" -ec '
        ipfs init --profile=server
        ipfs bootstrap rm --all
        ipfs config Addresses.API /ip4/0.0.0.0/tcp/5001
        printf "kubo-http\000\377\001\n" >/tmp/block.bin
        ipfs --offline block put --cid-codec=raw /tmp/block.bin >/tmp/block.cid
        dd if=/dev/urandom of=/tmp/file.bin bs=1M count=96 status=none
        ipfs --offline add -Q --cid-version=1 /tmp/file.bin >/tmp/file.cid
        ipfs config --json API.Authorizations \
            "{\"test\":{\"AuthSecret\":\"bearer:local-test-token\",\"AllowedPaths\":[\"/api/v0/block/get\",\"/api/v0/cat\",\"/api/v0/add\",\"/api/v0/pin/ls\"]}}"
        exec ipfs daemon --offline
    ' >/dev/null

APP_MAX_HEAP=64m start_app "$app" "$mode" "$artifact" --network "$network" \
    -e "QUARKUS_IPFS_KUBO_API_URL=http://$kubo:5001" \
    -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=local-test-token -e QUARKUS_IPFS_KUBO_TIMEOUT=PT120S \
    -e QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=128M

request() {
    docker exec "$app" curl --silent --show-error --max-time 15 "$@"
}

url=http://127.0.0.1:8080
await_ready request --fail --max-time 1 "$url/ipfs/node/peer-id"
await_ready kubo_ready "$kubo"
cid=$(docker exec "$kubo" cat /tmp/block.cid)
rpc="http://$kubo:5001/api/v0/block/get?arg=$cid"
reject_unauthorized() {
    local status
    status=$(request -X POST -o /dev/null -w '%{http_code}' "$@" "$rpc")
    case "$status" in
        401|403) ;;
        *) printf 'Expected a Kubo authorization error, got HTTP %s\n' "$status" >&2; return 1 ;;
    esac
}
reject_unauthorized
reject_unauthorized -H 'Authorization: Bearer wrong-test-token'
expected=$(docker exec "$kubo" sha256sum /tmp/block.bin)
actual=$(request --fail "$url/kubo/block/$cid" | sha256sum)
test "${expected%% *}" = "${actual%% *}"
test "$(request -o /dev/null -w '%{http_code}' "$url/kubo/block/invalid-cid")" = 400
missing=bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku
test "$(request -o /dev/null -w '%{http_code}' "$url/kubo/block/$missing")" = 404
printf '%s: authenticated Kubo read matched the binary block; missing and wrong credentials were rejected; invalid and missing CIDs were distinguished.\n' "$mode"

file_cid=$(docker exec "$kubo" cat /tmp/file.cid)
rpc="http://$kubo:5001/api/v0/cat?arg=$file_cid"
reject_unauthorized
reject_unauthorized -H 'Authorization: Bearer wrong-test-token'
request --fail --max-time 150 -D /tmp/kubo-file.headers -o /tmp/kubo-file.bin "$url/kubo/file/$file_cid"
heap=$(docker exec "$app" awk 'tolower($1) == "x-test-max-heap:" { print $2 + 0 }' /tmp/kubo-file.headers)
size=$(docker exec "$app" wc -c /tmp/kubo-file.bin | awk '{ print $1 }')
expected=$(docker exec "$kubo" sha256sum /tmp/file.bin)
actual=$(docker exec "$app" sha256sum /tmp/kubo-file.bin)
test "$heap" -gt 0
test "$heap" -le "$((64 * 1024 * 1024))"
test "$size" -eq "$((96 * 1024 * 1024))"
test "${expected%% *}" = "${actual%% *}"
printf '%s: authenticated Kubo UnixFS streaming matched SHA-256 for %s bytes with a %s-byte heap.\n' "$mode" "$size" "$heap"

rpc="http://$kubo:5001/api/v0/add?pin=false"
reject_unauthorized -F 'file=@/dev/null'
reject_unauthorized -H 'Authorization: Bearer wrong-test-token' -F 'file=@/dev/null'

kubo_cli() {
    docker exec "$kubo" ipfs --api-auth=bearer:local-test-token "$@"
}

verify_import() {
    local source=$1 pin=$2 expected_bytes=$3 uploaded heap expected actual cli actual_bytes cli_bytes pin_status
    uploaded=$(request --fail --max-time 150 --request POST -H 'Content-Type: application/octet-stream' \
        --upload-file "$source" -D /tmp/kubo-upload.headers "$url/kubo/file?pin=$pin")
    test -n "$uploaded"
    heap=$(docker exec "$app" awk 'tolower($1) == "x-test-max-heap:" { print $2 + 0 }' /tmp/kubo-upload.headers)
    test "$heap" -gt 0
    test "$heap" -le "$((64 * 1024 * 1024))"
    request --fail --max-time 150 -o /tmp/kubo-imported.bin "$url/kubo/file/$uploaded"
    kubo_cli cat "$uploaded" >"$upload_download"
    expected=$(docker exec "$app" sha256sum "$source")
    actual=$(docker exec "$app" sha256sum /tmp/kubo-imported.bin)
    cli=$(sha256sum "$upload_download")
    actual_bytes=$(docker exec "$app" wc -c /tmp/kubo-imported.bin | awk '{ print $1 }')
    cli_bytes=$(wc -c <"$upload_download")
    test "$actual_bytes" -eq "$expected_bytes"
    test "$cli_bytes" -eq "$expected_bytes"
    test "${expected%% *}" = "${actual%% *}"
    test "${expected%% *}" = "${cli%% *}"
    pin_status=$(request -X POST -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer local-test-token' \
        "http://$kubo:5001/api/v0/pin/ls?arg=$uploaded&type=recursive")
    if [[ "$pin" == true ]]; then
        test "$pin_status" = 200
    else
        test "$pin_status" = 500
    fi
    printf '%s: authenticated Kubo UnixFS import matched SHA-256 via Kubo CLI and client for %s bytes with a %s-byte heap (pin=%s, CID=%s).\n' \
        "$mode" "$expected_bytes" "$heap" "$pin" "$uploaded"
}

upload_download=$(mktemp)
trap 'code=$?; rm -f -- "$upload_download"; cleanup_network "$code" "$network" "$app" "$kubo"' EXIT
docker exec "$app" touch /tmp/empty-upload.bin
docker exec "$app" dd if=/dev/urandom of=/tmp/large-upload.bin bs=1M count=96 status=none
verify_import /tmp/empty-upload.bin false 0
verify_import /tmp/large-upload.bin true "$((96 * 1024 * 1024))"
