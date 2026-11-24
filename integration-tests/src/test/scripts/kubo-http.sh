#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: kubo-http.sh jvm|native path-to-app}
artifact=${2:?Pass the quarkus-app directory or the Linux native executable}
case "$mode" in
    jvm|native) ;;
    *) printf 'Expected jvm or native\n' >&2; exit 1 ;;
esac

export MSYS_NO_PATHCONV=1
app_image=${APP_IMAGE:-quay.io/quarkus/ubi10-quarkus-mandrel-builder-image:jdk-25}
network=quarkus-kubo-http-$$-$RANDOM
app="$network-app"
kubo="$network-kubo"

cleanup() {
    local status=$?
    if (( status != 0 )); then
        docker logs "$app" >&2 || true
        docker logs "$kubo" >&2 || true
    fi
    docker rm -fv "$app" "$kubo" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
docker network create --internal "$network" >/dev/null
trap cleanup EXIT

docker run -d --name "$kubo" --network "$network" \
    --entrypoint sh ipfs/kubo:v0.43.1 -ec '
        ipfs init --profile=server
        ipfs bootstrap rm --all
        ipfs config Addresses.API /ip4/0.0.0.0/tcp/5001
        printf "kubo-http\000\377\001\n" >/tmp/block.bin
        ipfs --offline block put --cid-codec=raw /tmp/block.bin >/tmp/block.cid
        ipfs config --json API.Authorizations \
            "{\"test\":{\"AuthSecret\":\"bearer:local-test-token\",\"AllowedPaths\":[\"/api/v0/block/get\"]}}"
        exec ipfs daemon --offline
    ' >/dev/null

docker run -d --name "$app" --network "$network" --user 0:0 \
    -e "QUARKUS_IPFS_KUBO_API_URL=http://$kubo:5001" \
    -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=local-test-token \
    -e QUARKUS_HTTP_HOST=127.0.0.1 \
    --entrypoint bash "$app_image" -ec '
        until [[ -e /tmp/start-app ]]; do sleep 0.2; done
        if [[ "$1" == jvm ]]; then
            exec java -jar /tmp/quarkus-app/quarkus-run.jar
        fi
        chmod +x /tmp/ipfs-runner
        exec /tmp/ipfs-runner
    ' bash "$mode" >/dev/null
if [[ "$mode" == jvm ]]; then
    docker cp "$artifact" "$app:/tmp/quarkus-app"
else
    docker cp "$artifact" "$app:/tmp/ipfs-runner"
fi
docker exec "$app" touch /tmp/start-app

request() {
    docker exec "$app" curl --silent --show-error --max-time 15 "$@"
}

url=http://127.0.0.1:8080
ready=false
for (( attempt=0; attempt<45; attempt++ )); do
    if docker logs "$kubo" 2>&1 | grep 'Daemon is ready' >/dev/null \
            && request --fail --max-time 1 "$url/ipfs/node/peer-id" >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
test "$ready" = true

cid=$(docker exec "$kubo" cat /tmp/block.cid)
status=$(request -X POST -o /dev/null -w '%{http_code}' "http://$kubo:5001/api/v0/block/get?arg=$cid")
test "$status" = 401 || test "$status" = 403
expected=$(docker exec "$kubo" sha256sum /tmp/block.bin)
actual=$(request --fail "$url/kubo/block/$cid" | sha256sum)
test "${expected%% *}" = "${actual%% *}"
test "$(request -o /dev/null -w '%{http_code}' "$url/kubo/block/invalid-cid")" = 400
missing=bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku
test "$(request -o /dev/null -w '%{http_code}' "$url/kubo/block/$missing")" = 404
printf '%s: authenticated Kubo read matched the binary block; invalid and missing CIDs were distinguished.\n' "$mode"
