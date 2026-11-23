#!/usr/bin/env bash
set -euo pipefail

: "${QUARKUS_URL:?Set QUARKUS_URL to a fresh integration-test application URL}"
: "${KUBO_CONTAINER:?Set KUBO_CONTAINER to the offline Kubo container name}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Git Bash should pass IPFS paths verbatim to docker.exe.
export MSYS_NO_PATHCONV=1

kubo() {
    docker exec -i "$KUBO_CONTAINER" ipfs --timeout=70s "$@"
}

request() {
    curl --fail --silent --show-error --max-time 30 "$@"
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
    stored=$(request "$QUARKUS_URL/ipfs/block/$cid" | kubo block put --cid-codec="$codec" --mhtype=sha2-256)
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
printf 'Kubo -> Quarkus: multiblock file and directory entry matched.\n'
