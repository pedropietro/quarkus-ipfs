#!/usr/bin/env bash

# Used by the isolated interoperability scenarios and their standalone entry points.
export MSYS_NO_PATHCONV=1
kubo_image=ipfs/kubo:v0.43.1@sha256:b293923d66e490e70ced64df42ea7a6cf7eac2740e3fb29101df18070fa7be48
app_image=${APP_IMAGE:-quay.io/quarkus/ubi10-quarkus-mandrel-builder-image:jdk-25}

check_artifact() {
    case "$1" in
        jvm) test -f "$2/quarkus-run.jar" && return ;;
        native) test -f "$2" && return ;;
    esac
    printf 'Expected jvm quarkus-app-directory or native executable, got %s %s\n' "$1" "$2" >&2
    return 1
}

start_app() {
    local app=$1 mode=$2 artifact=$3
    shift 3
    docker run -d --name "$app" --user 0:0 -e QUARKUS_HTTP_HOST=127.0.0.1 \
        -e "APP_MAX_HEAP=${APP_MAX_HEAP:-}" \
        "$@" --entrypoint bash "$app_image" -ec '
            until [[ -e /tmp/start-app ]]; do sleep 0.2; done
            heap=()
            if [[ -n "$APP_MAX_HEAP" ]]; then
                heap=("-Xmx$APP_MAX_HEAP")
            fi
            if [[ "$1" == jvm ]]; then
                exec java "${heap[@]}" -jar /tmp/quarkus-app/quarkus-run.jar
            fi
            chmod +x /tmp/ipfs-runner
            exec /tmp/ipfs-runner "${heap[@]}"
        ' bash "$mode" >/dev/null
    if [[ "$mode" == jvm ]]; then
        docker cp "$artifact" "$app:/tmp/quarkus-app"
    else
        docker cp "$artifact" "$app:/tmp/ipfs-runner"
    fi
    docker exec "$app" touch /tmp/start-app
}

cleanup_network() {
    local status=$1 network=$2 container
    shift 2
    if (( status != 0 )); then
        for container in "$@"; do
            printf '\n--- %s ---\n' "$container" >&2
            docker logs "$container" >&2 || true
        done
    fi
    docker rm -fv "$@" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}

kubo_ready() {
    docker logs "$1" 2>&1 | grep -F 'Daemon is ready'
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
