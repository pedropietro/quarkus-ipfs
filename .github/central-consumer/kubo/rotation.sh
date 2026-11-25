#!/usr/bin/env bash
# Only the fixture host can change the certificate served by the proxy.
prepare_rotation() {
    local certificates=$1 rotation="$1/rotation" name host
    mkdir -p "$rotation"/{old,next,mismatch}
    cp -- "$certificates"/server.{pem,key} "$rotation/old/"
    for name in next mismatch; do
        host=kubo-proxy
        if [[ "$name" == mismatch ]]; then
            host=wrong-host
        fi
        openssl req -new -key "$certificates/server.key" -subj "/CN=$host" -out "$rotation/$name/server.csr"
        printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$host" >"$rotation/$name/server.ext"
        openssl x509 -req -in "$rotation/$name/server.csr" \
            -CA "$certificates/untrusted.pem" -CAkey "$certificates/untrusted.key" -CAcreateserial \
            -days 2 -extfile "$rotation/$name/server.ext" -out "$rotation/$name/server.pem"
        cp -- "$certificates/server.key" "$rotation/$name/"
    done
    # Workers load these fixture-only keys at handshake time inside the private working directory.
    chmod a+r "$rotation"/{old,next,mismatch}/server.key
    ln -s old "$rotation/9443"
    sed "s/@KUBO@/$kubo/g" "$fixture/kubo/rotation.conf" >"$rotation/proxy.conf"
}

verify_rotation() {
    local certificates=$1 coordination="$work/rotation-client" status
    mkdir -p "$coordination"
    cp -- "$certificates/ca.pem" "$coordination/ca.pem"
    start_app "$app" "$mode" "$artifact" --network "$network" \
        --mount "type=bind,src=$coordination,dst=/rotation" \
        -e KUBO_TEST_CA=/rotation/ca.pem -e KUBO_TEST_EXPECT=rotation \
        -e QUARKUS_IPFS_KUBO_TIMEOUT=60s -e QUARKUS_IPFS_KUBO_API_URL=https://kubo-proxy:9443 \
        -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=central-consumer-test

    await_ready test -f "$coordination/active"
    cp -- "$certificates/untrusted.pem" "$coordination/ca.next"
    mv -f -- "$coordination/ca.next" "$coordination/ca.pem"
    touch "$coordination/new-trust"
    await_ready test -f "$coordination/old-rejected"
    switch_proxy_certificate "$certificates/rotation" next
    touch "$coordination/new-proxy"
    await_ready test -f "$coordination/new-accepted"
    switch_proxy_certificate "$certificates/rotation" mismatch
    touch "$coordination/wrong-hostname"

    status=$(timeout --kill-after=10s 90s docker wait "$app")
    docker logs "$app"
    test "$status" = 0
    docker rm -v "$app" >/dev/null
}

switch_proxy_certificate() {
    local rotation=$1 name=$2
    ln -s "$name" "$rotation/9443.next"
    mv -Tf -- "$rotation/9443.next" "$rotation/9443"
}
