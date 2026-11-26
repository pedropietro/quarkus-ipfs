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
    start_app "$app" "$mode" "$artifact" --network "$network" -e QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=64M
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

make_directory() {
    request -H 'Content-Type: text/plain; charset=UTF-8' --data-binary @- "$QUARKUS_URL/unixfs/directory"
}

compare_directory() {
    local producer=$1 sample=$2 cid=$3
    kubo ls --size=false --resolve-type=false "/ipfs/$cid" >"$work/cli-list"
    request "$QUARKUS_URL/unixfs/directory/$cid/entries" >"$work/api-list"
    if [[ -s "$work/api-list" ]]; then
        printf '\n' >>"$work/api-list"
    fi
    diff -u "$work/cli-list" "$work/api-list"
    test "$(request "$QUARKUS_URL/unixfs/directory/$cid")" = "$(cut -d' ' -f2- "$work/cli-list")"
    case "$sample" in
        root) grep -Fq ' préface 世界.txt' "$work/cli-list" ;;
        empty) test ! -s "$work/cli-list" ;;
        nested) grep -Fq ' ação.txt' "$work/cli-list" ;;
    esac
    printf '%s %s directory: DagService names and CIDs matched ipfs ls in order.\n' "$producer" "$sample"
}

# Distinct payloads keep the two content-addressed block sets independent.
dd if=/dev/urandom of="$work/quarkus.bin" bs=262144 count=175 status=none
dd if=/dev/urandom of="$work/kubo.bin" bs=524529 count=1 status=none

quarkus_file=$(request --max-time 120 -X POST -H 'Content-Type: application/octet-stream' --upload-file - \
    "$QUARKUS_URL/unixfs/file/stream" <"$work/quarkus.bin")
quarkus_empty=$(make_directory </dev/null)
quarkus_nested=$(printf '%s ação.txt\n' "$quarkus_file" | make_directory)
quarkus_dir=$(printf '%s payload.bin\n%s empty\n%s nested\n%s préface 世界.txt\n' \
    "$quarkus_file" "$quarkus_empty" "$quarkus_nested" "$quarkus_file" | make_directory)
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

first_child=$(kubo refs "/ipfs/$quarkus_file" | sed -n '1p')
[[ "$first_child" == bafybei* ]]
kubo cat "/ipfs/$quarkus_file" >"$work/read-by-kubo.bin"
cmp "$work/quarkus.bin" "$work/read-by-kubo.bin"
kubo cat "/ipfs/$quarkus_dir/payload.bin" >"$work/read-by-kubo-dir.bin"
cmp "$work/quarkus.bin" "$work/read-by-kubo-dir.bin"
printf 'Quarkus -> Kubo: nested file and directory entry matched.\n'

compare_directory Nabu root "$quarkus_dir"
compare_directory Nabu empty "$quarkus_empty"
compare_directory Nabu nested "$quarkus_nested"

docker exec "$KUBO_CONTAINER" mkdir -p /tmp/quarkus-unixfs/root/empty /tmp/quarkus-unixfs/root/nested
docker exec -i "$KUBO_CONTAINER" sh -ec '
    cd /tmp/quarkus-unixfs/root
    cat >payload.bin
    printf unicode >"préface 世界.txt"
    printf nested >"nested/ação.txt"
' <"$work/kubo.bin"
kubo_dir=$(kubo add -Q --recursive --empty-dirs --pin=false --cid-version=1 --raw-leaves=false \
    --max-file-links=2 /tmp/quarkus-unixfs/root)
kubo_file=$(kubo resolve "/ipfs/$kubo_dir/payload.bin")
kubo_empty=$(kubo resolve "/ipfs/$kubo_dir/empty")
kubo_nested=$(kubo resolve "/ipfs/$kubo_dir/nested")
kubo_blocks=$(kubo refs -r -u "/ipfs/$kubo_dir")
while IFS= read -r cid; do
    stored=$(kubo block get "$cid" </dev/null | request -H 'Content-Type: application/octet-stream' \
        --data-binary @- "$QUARKUS_URL/unixfs/block/$cid")
    test "$stored" = "$cid"
done <<<"$kubo_dir"$'\n'"$kubo_blocks"

compare_directory Kubo root "$kubo_dir"
compare_directory Kubo empty "${kubo_empty#/ipfs/}"
compare_directory Kubo nested "${kubo_nested#/ipfs/}"
request "$QUARKUS_URL/unixfs/file/${kubo_file#/ipfs/}" >"$work/read-by-quarkus.bin"
cmp "$work/kubo.bin" "$work/read-by-quarkus.bin"
request "$QUARKUS_URL/unixfs/file/${kubo_file#/ipfs/}/stream" >"$work/streamed-by-quarkus.bin"
cmp "$work/kubo.bin" "$work/streamed-by-quarkus.bin"
printf 'Kubo -> Quarkus: nested file and directory entry matched.\n'
