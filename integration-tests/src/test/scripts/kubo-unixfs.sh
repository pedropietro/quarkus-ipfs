#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/kubo-test-lib.sh"

work=$(mktemp -d)
app=
cleanup() {
    local status=$?
    if [[ -n "$app" ]]; then
        cleanup_network "$status" "$network" "$app" "$KUBO_CONTAINER"
    fi
    rm -rf "$work"
}
trap cleanup EXIT

if (( $# > 0 )); then
    mode=$1
    artifact=${2:?Pass the packaged application}
    check_artifact "$mode" "$artifact"
    network=quarkus-kubo-unixfs-$$-$RANDOM
    app="$network-app"
    KUBO_CONTAINER="$network-kubo"
    QUARKUS_URL=http://127.0.0.1:8080
    docker network create --internal "$network" >/dev/null
    docker run -d --name "$KUBO_CONTAINER" --network "$network" --entrypoint sh "$kubo_image" -ec '
        ipfs init --profile=test
        exec ipfs daemon --offline
    ' >/dev/null
    start_app "$app" "$mode" "$artifact" --network "$network"
    await_ready kubo_ready "$KUBO_CONTAINER"
    await_ready docker exec "$app" curl --fail --silent --max-time 1 "$QUARKUS_URL/ipfs/node/peer-id"
else
    : "${QUARKUS_URL:?Set QUARKUS_URL to a fresh integration-test application URL}"
    : "${KUBO_CONTAINER:?Set KUBO_CONTAINER to the offline Kubo container name}"
fi

kubo() {
    docker exec -i "$KUBO_CONTAINER" ipfs --timeout=70s "$@"
}

request() {
    if [[ -n "$app" ]]; then
        docker exec -i "$app" curl --fail --silent --show-error --max-time 30 "$@"
    else
        curl --fail --silent --show-error --max-time 30 "$@"
    fi
}

# Distinct payloads keep the two content-addressed block sets independent.
dd if=/dev/urandom of="$work/quarkus.bin" bs=524411 count=1 status=none
dd if=/dev/urandom of="$work/kubo.bin" bs=524529 count=1 status=none

quarkus_file=$(request -H 'Content-Type: application/octet-stream' --data-binary @- \
    "$QUARKUS_URL/unixfs/file" <"$work/quarkus.bin")
quarkus_dir=$(request -X POST "$QUARKUS_URL/unixfs/directory/$quarkus_file")
quarkus_blocks=$(request "$QUARKUS_URL/ipfs/block/list" | tr -d '[] ' | tr ',' '\n')
while IFS= read -r cid; do
    case "$cid" in
        bafkrei*) codec=raw ;;
        bafybei*) codec=dag-pb ;;
        *) printf 'Unexpected block CID %s\n' "$cid" >&2; exit 1 ;;
    esac
    stored=$(request "$QUARKUS_URL/ipfs/block/$cid" </dev/null | kubo block put --cid-codec="$codec" --mhtype=sha2-256)
    test "$stored" = "$cid"
done <<<"$quarkus_blocks"

kubo cat "/ipfs/$quarkus_file" >"$work/read-by-kubo.bin"
cmp "$work/quarkus.bin" "$work/read-by-kubo.bin"
kubo cat "/ipfs/$quarkus_dir/payload.bin" >"$work/read-by-kubo-dir.bin"
cmp "$work/quarkus.bin" "$work/read-by-kubo-dir.bin"
printf 'Quarkus -> Kubo: multiblock file and directory entry matched.\n'

kubo_dir=$(kubo add -Q -w --pin=false --cid-version=1 --raw-leaves=false --stdin-name=payload.bin <"$work/kubo.bin")
kubo_file=$(kubo resolve "/ipfs/$kubo_dir/payload.bin")
kubo_blocks=$(kubo refs -r -u "/ipfs/$kubo_dir")
while IFS= read -r cid; do
    stored=$(kubo block get "$cid" </dev/null | request -H 'Content-Type: application/octet-stream' \
        --data-binary @- "$QUARKUS_URL/unixfs/block/$cid")
    test "$stored" = "$cid"
done <<<"$kubo_dir"$'\n'"$kubo_blocks"

test "$(request "$QUARKUS_URL/unixfs/directory/$kubo_dir")" = 'payload.bin'
request "$QUARKUS_URL/unixfs/file/${kubo_file#/ipfs/}" >"$work/read-by-quarkus.bin"
cmp "$work/kubo.bin" "$work/read-by-quarkus.bin"
request "$QUARKUS_URL/unixfs/file/${kubo_file#/ipfs/}/stream" >"$work/streamed-by-quarkus.bin"
cmp "$work/kubo.bin" "$work/streamed-by-quarkus.bin"
printf 'Kubo -> Quarkus: multiblock file and directory entry matched.\n'
