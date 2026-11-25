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
    local certificates=$1 round rounds=${KUBO_ROTATION_ROUNDS:-1}
    if [[ ! "$rounds" =~ ^[1-9][0-9]?$ ]]; then
        printf 'KUBO_ROTATION_ROUNDS must be between 1 and 99.\n' >&2
        return 1
    fi
    for (( round=1; round<=rounds; round++ )); do
        switch_proxy_certificate "$certificates/rotation" old
        verify_rotation_round "$certificates" "$work/rotation-client-$round"
        printf 'Kubo CA rotation passed (%s %d/%d).\n' "$mode" "$round" "$rounds"
    done
}

verify_rotation_round() {
    local certificates=$1 coordination=$2 status attempt
    mkdir -p "$coordination"
    cp -- "$certificates/ca.pem" "$coordination/ca.pem"
    local reload_args=()
    if [[ "$rotation_mode" == automatic ]]; then
        source "$fixture/kubo/readiness.sh"
        reload_args=(-e KUBO_TEST_RELOAD_PERIOD=2s -e KUBO_TEST_AUTO_ROTATION=true)
    fi
    start_app "$app" "$mode" "$artifact" --network "$network" "${reload_args[@]}" \
        --mount "type=bind,src=$coordination,dst=/rotation" \
        -e KUBO_TEST_CA=/rotation/ca.pem -e KUBO_TEST_EXPECT=rotation \
        -e QUARKUS_IPFS_KUBO_TIMEOUT=60s -e QUARKUS_IPFS_KUBO_API_URL=https://kubo-proxy:9443 \
        -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=central-consumer-test

    await_ready test -f "$coordination/active"
    if [[ "$rotation_mode" == automatic ]]; then
        expect_readiness UP 200
        echo 'HTTPS readiness is UP with the original CA.'
    fi
    replace_trust_store "$certificates/untrusted.pem" "$coordination"
    if [[ "$rotation_mode" == automatic ]]; then
        await_readiness DOWN 503
        echo 'Periodic CA reload made HTTPS readiness DOWN while the proxy still presented the removed CA.'
    fi
    touch "$coordination/new-trust"
    await_ready test -f "$coordination/old-rejected"
    switch_proxy_certificate "$certificates/rotation" next
    if [[ "$rotation_mode" == automatic ]]; then
        await_readiness UP 200
        echo 'HTTPS readiness recovered to UP in the same consumer after the proxy switched to the new CA.'
    fi
    touch "$coordination/new-proxy"
    await_ready test -f "$coordination/new-accepted"

    if [[ "$rotation_mode" == automatic ]]; then
        printf 'invalid PEM\n' >"$coordination/ca.next"
        mv -f -- "$coordination/ca.next" "$coordination/ca.pem"
        # Leave the invalid file in place across multiple two-second reload periods.
        for attempt in 1 2 3; do
            sleep 2
            expect_readiness UP 200
        done
        touch "$coordination/invalid-trust"
        await_ready test -f "$coordination/invalid-trust-accepted"
        replace_trust_store "$certificates/untrusted.pem" "$coordination"
        echo 'HTTPS readiness and Kubo RPCs stayed UP with the last valid CA while the PEM was invalid.'
    fi

    switch_proxy_certificate "$certificates/rotation" mismatch
    if [[ "$rotation_mode" == automatic ]]; then
        await_readiness DOWN 503
        echo 'HTTPS readiness is DOWN with an incorrect proxy hostname.'
    fi
    touch "$coordination/wrong-hostname"
    if [[ "$rotation_mode" == automatic ]]; then
        await_ready test -f "$coordination/hostname-rejected"
        switch_proxy_certificate "$certificates/rotation" next
        await_readiness UP 200
        echo 'HTTPS readiness is UP after restoring the correct proxy hostname.'
        touch "$coordination/restored-proxy"
    fi

    status=$(timeout --kill-after=10s 90s docker wait "$app")
    docker logs "$app"
    test "$status" = 0
    docker rm -v "$app" >/dev/null
}

replace_trust_store() {
    local certificate=$1 coordination=$2
    cp -- "$certificate" "$coordination/ca.next"
    mv -f -- "$coordination/ca.next" "$coordination/ca.pem"
}

switch_proxy_certificate() {
    local rotation=$1 name=$2
    ln -s "$name" "$rotation/9443.next"
    mv -Tf -- "$rotation/9443.next" "$rotation/9443"
}
