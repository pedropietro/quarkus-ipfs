#!/usr/bin/env bash
# HTTPS fixture used by verify.sh. All services stay on its internal Docker network.
verify_https() {
    local certificates="$work/https"
    mkdir -p "$certificates"
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=Kubo Test CA' \
        -keyout "$certificates/ca.key" -out "$certificates/ca.pem"
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=Untrusted Test CA' \
        -keyout "$certificates/untrusted.key" -out "$certificates/untrusted.pem"
    openssl req -newkey rsa:2048 -nodes -subj '/CN=kubo-proxy' \
        -keyout "$certificates/server.key" -out "$certificates/server.csr"
    printf 'subjectAltName=DNS:kubo-proxy,DNS:redirect-target\nextendedKeyUsage=serverAuth\n' >"$certificates/server.ext"
    openssl x509 -req -in "$certificates/server.csr" -CA "$certificates/ca.pem" -CAkey "$certificates/ca.key" \
        -CAcreateserial -days 2 -extfile "$certificates/server.ext" -out "$certificates/server.pem"
    sed "s/@KUBO@/$kubo/g" "$fixture/kubo/nginx.conf" >"$certificates/nginx.conf"

    proxy="$network-proxy"
    local image='nginx:1.28.0-alpine@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284'
    docker run -d --name "$proxy" --network "$network" \
        --network-alias kubo-proxy --network-alias wrong-host --network-alias redirect-target \
        --mount "type=bind,src=$certificates,dst=/fixture,readonly" \
        --entrypoint nginx "$image" -c /fixture/nginx.conf -g 'daemon off;' >/dev/null
    await_ready docker exec "$proxy" wget -q -O /dev/null http://127.0.0.1:8080/ready

    run_https_consumer success kubo-proxy rpc ca.pem
    if [[ "$metrics" == disabled ]]; then
        return
    fi
    run_https_consumer 'untrusted CA' kubo-proxy rpc untrusted.pem
    run_https_consumer 'hostname mismatch' wrong-host rpc ca.pem
    run_https_consumer trailers kubo-proxy late ca.pem
    run_https_consumer redirect kubo-proxy redirect ca.pem
    if [[ "$metrics" == enabled ]]; then
        run_https_consumer interrupted kubo-proxy stall ca.pem
    fi
    if docker logs "$proxy" 2>&1 | grep -F '/capture'; then
        printf 'The redirect target received a request.\n' >&2
        return 1
    fi
    echo 'Kubo HTTPS proxy checks passed. The redirect target received no requests.'
}

run_https_consumer() {
    local expected=$1 host=$2 prefix=$3 ca=$4 status
    local timeout_args=()
    if [[ "$expected" == interrupted ]]; then
        timeout_args=(-e QUARKUS_IPFS_KUBO_TIMEOUT=2s)
    fi
    start_app "$app" "$mode" "$artifact" --network "$network" \
        --mount "type=bind,src=$work/https/$ca,dst=/fixture-ca.pem,readonly" \
        -e KUBO_TEST_CA=/fixture-ca.pem -e "KUBO_TEST_EXPECT=$expected" \
        -e "KUBO_EXPECT_METRICS=$metrics" "${timeout_args[@]}" \
        -e "QUARKUS_IPFS_KUBO_API_URL=https://$host:8443/$prefix" \
        -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=central-consumer-test
    status=$(timeout --kill-after=10s 90s docker wait "$app")
    docker logs "$app"
    test "$status" = 0
    docker rm -v "$app" >/dev/null
}
