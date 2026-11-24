#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: kubo-ipns.sh jvm|native path-to-app}
artifact=${2:?Pass the quarkus-app directory or the Linux native executable}
case "$mode" in
    jvm|native) ;;
    *) printf 'Expected jvm or native\n' >&2; exit 1 ;;
esac

# Preserve IPFS multiaddresses when invoking Docker from Git Bash.
export MSYS_NO_PATHCONV=1
app_image=${APP_IMAGE:-quay.io/quarkus/ubi10-quarkus-mandrel-builder-image:jdk-25}
kubo_image=ipfs/kubo:v0.43.1
network=quarkus-ipns-$$-$RANDOM
app="$network-nabu"
kubo_node="$network-kubo"
router="$network-router"
# The WAN DHT filters private and loopback addresses. These addresses only exist
# inside a Docker internal network with no external connectivity.
prefix="11.$((RANDOM % 254 + 1)).$((RANDOM % 254 + 1))"
app_ip="$prefix.2"
kubo_ip="$prefix.3"
router_ip="$prefix.4"

cleanup() {
    local status=$?
    if (( status != 0 )); then
        docker logs "$app" >&2 || true
        docker logs "$kubo_node" >&2 || true
        docker logs "$router" >&2 || true
    fi
    docker rm -fv "$app" "$kubo_node" "$router" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
docker network create --internal --subnet "$prefix.0/24" "$network" >/dev/null
trap cleanup EXIT

start_kubo() {
    docker run -d --name "$1" --network "$network" --ip "$2" \
        -e "TEST_IP=$2" --entrypoint sh "$kubo_image" -ec '
        ipfs init --profile=server
        ipfs bootstrap rm --all
        ipfs config Routing.Type dhtserver
        ipfs config --json Routing.DelegatedRouters "[]"
        ipfs config --json Ipns.DelegatedPublishers "[]"
        ipfs config --json Addresses.Swarm "[\"/ip4/$TEST_IP/tcp/4001\"]"
        exec ipfs daemon
        ' >/dev/null
}
start_kubo "$kubo_node" "$kubo_ip"
start_kubo "$router" "$router_ip"

docker run -d --name "$app" --network "$network" --ip "$app_ip" --user 0:0 \
    -e "QUARKUS_IPFS_SWARM_ADDRESSES=/ip4/$app_ip/tcp/4001" \
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

kubo() {
    docker exec -i "$kubo_node" ipfs --timeout=25s "$@"
}

kubo_router() {
    docker exec -i "$router" ipfs --timeout=25s "$@"
}

request() {
    docker exec -i "$app" curl --fail --silent --show-error --max-time 35 "$@"
}

await_ready() {
    local attempt
    for (( attempt=0; attempt<60; attempt++ )); do
        if "$@" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    printf 'Timed out waiting for %s\n' "$*" >&2
    return 1
}

url=http://127.0.0.1:8080
await_ready kubo swarm peers
await_ready kubo_router swarm peers
await_ready request "$url/ipfs/node/peer-id"
nabu_id=$(request "$url/ipfs/node/peer-id")
kubo_id=$(kubo id -f='<id>')
router_id=$(kubo_router id -f='<id>')
kubo swarm connect "/ip4/$router_ip/tcp/4001/p2p/$router_id"
test "$(request -H 'Content-Type: text/plain' --data-binary "/ip4/$kubo_ip/tcp/4001/p2p/$kubo_id" "$url/ipns/peers")" = 1

kubo_in_routing_table() {
    kubo stats dht wan | grep -F "$router_id"
}
await_ready kubo_in_routing_table

nabu_value=/ipfs/bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku/nabu
kubo_value=/ipfs/bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku/kubo
published=$(request -i -H 'Content-Type: text/plain' --data-binary "$nabu_value" "$url/ipns?sequence=7")
grep -Eqi '^X-IPNS-Replicas: [1-9]' <<<"$published"
test "$(kubo_router name resolve --nocache "/ipns/$nabu_id")" = "$nabu_value"
printf '%s Nabu -> Kubo: signed IPNS record published and resolved.\n' "$mode"

kubo name publish --resolve=false --v1compat=false --sequence=9 "$kubo_value"
inspection=$(kubo name get "$kubo_id" | kubo name inspect --verify="$kubo_id" --dump=false)
printf '%s\n' "$inspection"
grep -Eq '^Signature Type:[[:space:]]+V2[[:space:]]*$' <<<"$inspection"
grep -Eq '^ Valid:[[:space:]]+true[[:space:]]*$' <<<"$inspection"
test "$(request "$url/ipns/$kubo_id?minResults=1")" = "$kubo_value"
printf '%s Kubo -> Nabu: V2-only IPNS record (sequence 9) published and resolved.\n' "$mode"

kubo_updated_value="${kubo_value}-updated"
kubo name publish --resolve=false --v1compat=true --sequence=10 "$kubo_updated_value"
test "$(request "$url/ipns/$kubo_id?minResults=1")" = "$kubo_updated_value"
printf '%s Kubo -> Nabu: V1+V2 update (sequence 10) published and resolved.\n' "$mode"
