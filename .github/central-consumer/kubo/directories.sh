#!/usr/bin/env bash

prepare_directory_limit() {
    python3 -c 'import sys; sys.stdout.write(" " * (1024 * 1024 + 1))' >"$1/oversized-listing.json"
}

verify_directories() {
    local output="$work/directories" sample cid index
    local -a samples cids
    mkdir -p "$output"
    docker exec "$kubo" sh -ec '
        mkdir -p /tmp/unixfs/root/empty /tmp/unixfs/root/nested
        printf root >/tmp/unixfs/root/"préface 世界.txt"
        printf nested >/tmp/unixfs/root/nested/"ação.txt"
        for dir in root root/empty root/nested; do
            ipfs add --api-auth bearer:central-consumer-test --cid-version=1 --recursive --quieter "/tmp/unixfs/$dir"
        done
        ipfs add --api-auth bearer:central-consumer-test --cid-version=1 --raw-leaves=false --quieter \
            /tmp/unixfs/root/"préface 世界.txt"
    ' >"$output/cids"
    mapfile -t cids <"$output/cids"
    samples=(root empty nested file)
    test "${#cids[@]}" = "${#samples[@]}"
    for index in "${!samples[@]}"; do
        sample=${samples[index]}
        cid=${cids[index]}
        printf '%s\n' "$cid" >"$output/$sample.cid"
        if [[ "$sample" == file ]]; then
            continue
        fi
        docker run --rm --network "$network" --entrypoint ipfs \
            --mount "type=bind,src=$work/https/ca.pem,dst=/fixture-ca.pem,readonly" \
            -e SSL_CERT_FILE=/fixture-ca.pem "$kubo_image" \
            ls --api /dns4/kubo-proxy/tcp/9443/https --api-auth bearer:central-consumer-test \
            --size=false --resolve-type=false "$cid" >"$output/$sample.ls"
    done

    run_https_consumer directory-listing kubo-proxy rpc ca.pem
    run_https_consumer directory-unauthenticated kubo-proxy rpc ca.pem ''
    run_https_consumer directory-limit kubo-proxy directory-limit ca.pem
}
