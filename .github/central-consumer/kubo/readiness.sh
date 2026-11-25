#!/usr/bin/env bash
# Verify the SmallRye Health endpoint against the authenticated Kubo fixture.
verify_kubo_readiness() {
    start_readiness_app central-consumer-test

    if [[ "$scenario" == kubo-readiness-no-health ]]; then
        local status
        status=$(docker exec "$app" curl --silent --show-error --max-time 5 \
            --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8080/q/health/ready)
        test "$status" = 404
        echo 'Kubo readiness is absent without SmallRye Health (HTTP 404), even when enabled in configuration.'
        return
    fi

    await_ready expect_readiness UP 200
    echo 'Kubo readiness is UP on the authenticated version RPC.'

    docker pause "$kubo" >/dev/null
    local probe_status=0
    expect_readiness DOWN 503 || probe_status=$?
    docker unpause "$kubo" >/dev/null
    test "$probe_status" = 0
    echo 'Kubo readiness reported DOWN after the RPC deadline while Kubo was paused.'
    await_ready expect_readiness UP 200

    docker stop --time 5 "$kubo" >/dev/null
    expect_readiness DOWN 503
    echo 'Kubo readiness reported DOWN when the RPC was unavailable.'
    docker start "$kubo" >/dev/null
    await_ready expect_readiness UP 200
    echo 'Kubo readiness recovered to UP in the same consumer.'

    docker rm -fv "$app" >/dev/null
    start_readiness_app invalid-token
    await_ready expect_readiness DOWN 503
    echo 'Kubo readiness reported DOWN when the version RPC rejected authentication.'
}

start_readiness_app() {
    local token=$1
    start_app "$app" "$mode" "$artifact" --network "$network" \
        -e "QUARKUS_IPFS_KUBO_API_URL=http://$kubo:5001" \
        -e "QUARKUS_IPFS_KUBO_BEARER_TOKEN=$token"
    await_ready readiness_app_started
}

readiness_app_started() {
    docker logs "$app" 2>&1 | grep -F 'Kubo readiness consumer started without an embedded node'
}

expect_readiness() {
    local expected_status=$1 expected_code=$2 response
    response=$(docker exec "$app" curl --silent --show-error --max-time 5 --write-out $'\n%{http_code}' \
        http://127.0.0.1:8080/q/health/ready) || return
    python3 -c '
import json
import sys

body, code = sys.stdin.read().rsplit("\n", 1)
expected_status, expected_code = sys.argv[1:]
payload = json.loads(body)
if (code.strip() != expected_code or payload["status"] != expected_status
        or payload["checks"] != [{"name": "kubo", "status": expected_status}]):
    sys.exit(f"Expected only Kubo readiness {expected_status} ({expected_code}), got {code.strip()} and {payload}")
' "$expected_status" "$expected_code" <<<"$response"
}
