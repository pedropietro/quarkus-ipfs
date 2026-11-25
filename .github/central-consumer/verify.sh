#!/usr/bin/env bash
set -euo pipefail

fixture=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$fixture/../.." && pwd -P)

reactor=false
if [[ "${1:-}" == --reactor ]]; then
    reactor=true
    mode=${2:?Usage: verify.sh --reactor jvm|native [block|kubo|kubo-https|kubo-metrics|kubo-metrics-disabled|kubo-readiness|kubo-readiness-no-health]}
    scenario=${3:-kubo}
    read -r ipfs_version quarkus_version < <(python3 - "$root/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

pom = ET.parse(sys.argv[1]).getroot()
print(pom.findtext("{*}version"), pom.findtext("{*}properties/{*}quarkus.version"))
PY
    )
else
    ipfs_version=${1:?Usage: verify.sh ipfs-version quarkus-version [jvm|native] [block|kubo|kubo-https|kubo-metrics|kubo-metrics-disabled|kubo-readiness|kubo-readiness-no-health]}
    quarkus_version=${2:?Pass the Quarkus version}
    mode=${3:-jvm}
    scenario=${4:-block}
fi

metrics=none
readiness=false
case "$scenario" in
    block|kubo|kubo-https) ;;
    kubo-metrics) metrics=enabled ;;
    kubo-metrics-disabled) metrics=disabled ;;
    kubo-readiness|kubo-readiness-no-health) readiness=true ;;
    *) printf 'Unknown consumer scenario: %s\n' "$scenario" >&2; exit 1 ;;
esac
package_args=()
case "$mode" in
    jvm) ;;
    native)
        package_args=(-Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false)
        if [[ "$ipfs_version" == 0.5.0 ]]; then
            package_args+=(-Pnetty-compat)
        fi
        ;;
    *) printf 'Expected jvm or native, got %s\n' "$mode" >&2; exit 1 ;;
esac
temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$temp_root/quarkus-ipfs-consumer.XXXXXXXXXX")
network=
cleanup() {
    local status=$?
    if [[ -n "$network" ]]; then
        cleanup_network "$status" "$network" "$app" "$kubo" ${proxy:+"$proxy"}
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT

cp -- "$fixture/pom.xml" "$work/"
if [[ "$readiness" == true ]]; then
    mkdir -p "$work/src/main/java/org/acme" "$work/src/main/resources"
    cp -- "$fixture/kubo/KuboReadinessApp.java" "$work/src/main/java/org/acme/"
    cp -- "$fixture/kubo/application.properties" "$work/src/main/resources/"
    echo quarkus.ipfs.kubo.health-enabled=true >> "$work/src/main/resources/application.properties"
    package_args+=("-P$scenario")
elif [[ "$scenario" != block ]]; then
    mkdir -p "$work/src/main/java/org/acme" "$work/src/main/resources"
    cp -- "$fixture/kubo/KuboSmokeTest.java" "$fixture/kubo/KuboCaRotationCheck.java" "$work/src/main/java/org/acme/"
    cp -- "$fixture/kubo/application.properties" "$work/src/main/resources/"
    if [[ "$metrics" != none ]]; then
        package_args+=(-Pkubo-micrometer)
        cp -- "$fixture/kubo/KuboMetricsCheck.java" "$work/src/main/java/org/acme/"
        if [[ "$metrics" == disabled ]]; then
            echo quarkus.ipfs.metrics-enabled=false >> "$work/src/main/resources/application.properties"
        fi
    fi
    if [[ "$scenario" == kubo-https || "$metrics" != none ]]; then
        cat >> "$work/src/main/resources/application.properties" <<'PROPERTIES'
quarkus.ipfs.kubo.tls-configuration-name=kubo
quarkus.tls.kubo.trust-store.pem.certs=${KUBO_TEST_CA}
PROPERTIES
    fi
else
    cp -R -- "$fixture/src" "$work/"
fi

stage_artifact() {
    local module=$1 artifact=$2
    local dest="$work/repository/io/github/pedropietro/$artifact/$ipfs_version"
    mkdir -p "$dest"
    cp -- "$root/$module/pom.xml" "$dest/$artifact-$ipfs_version.pom"
    if [[ "$module" != . ]]; then
        cp -- "$root/$module/target/$artifact-$ipfs_version.jar" "$dest/"
    fi
}

if [[ "$reactor" == true ]]; then
    stage_artifact . quarkus-ipfs-parent
    stage_artifact nabu-upstream quarkus-ipfs-nabu
    stage_artifact runtime quarkus-ipfs
    stage_artifact deployment quarkus-ipfs-deployment
fi
printf '<settings/>\n' > "$work/settings.xml"
cd -- "$work"
if [[ "$reactor" == true ]]; then
    printf 'Resolving Quarkus IPFS %s from the reactor in an isolated Maven repository (%s, %s).\n' "$ipfs_version" "$mode" "$scenario"
else
    printf 'Resolving Quarkus IPFS %s with an empty local Maven repository (%s, %s).\n' "$ipfs_version" "$mode" "$scenario"
fi
python3 -B "$root/.github/scripts/maven_verify.py" --package "$root/mvnw" \
    -s "$work/settings.xml" -gs "$work/settings.xml" "-Dmaven.repo.local=$work/repository" \
    "-Dipfs.version=$ipfs_version" "-Dquarkus.version=$quarkus_version" "${package_args[@]}"

if [[ "$mode" == native ]]; then
    shopt -s nullglob
    runners=(target/*-runner)
    if (( ${#runners[@]} != 1 )) || [[ ! -x "${runners[0]}" ]]; then
        printf 'Expected one executable native consumer in target/.\n' >&2
        exit 1
    fi
    artifact=${runners[0]}
else
    artifact=target/quarkus-app
fi

if [[ "$scenario" == block ]]; then
    if [[ "$mode" == native ]]; then
        timeout --kill-after=10s 90s "$artifact" -Dquarkus.ipfs.run-bitswap=false
    else
        timeout --kill-after=10s 90s java -Dquarkus.ipfs.run-bitswap=false -jar "$artifact/quarkus-run.jar"
    fi
    exit
fi

APP_IMAGE=${QUARKUS_NATIVE_BUILDER_IMAGE:-${APP_IMAGE:-}} source "$root/integration-tests/src/test/scripts/kubo-test-lib.sh"
check_artifact "$mode" "$artifact"
network="quarkus-central-kubo-$$-$RANDOM"
kubo="$network-kubo"
app="$network-app"
docker network create --internal "$network" >/dev/null
docker run -d --name "$kubo" --network "$network" --entrypoint sh "$kubo_image" -ec '
    if [ ! -f "${IPFS_PATH:-/data/ipfs}/config" ]; then
        ipfs init --profile=server
        ipfs bootstrap rm --all
        ipfs config Addresses.API /ip4/0.0.0.0/tcp/5001
        ipfs config --json API.Authorizations "{\"test\":{\"AuthSecret\":\"bearer:central-consumer-test\",\"AllowedPaths\":[\"/api/v0/add\",\"/api/v0/cat\",\"/api/v0/block/get\",\"/api/v0/version\"]}}"
    fi
    exec ipfs daemon --offline
' >/dev/null
await_ready kubo_ready "$kubo"
if [[ "$readiness" == true ]]; then
    source "$fixture/kubo/readiness.sh"
    verify_kubo_readiness
    exit
fi
if [[ "$scenario" == kubo-https || "$metrics" != none ]]; then
    source "$fixture/kubo/https.sh"
    verify_https
    exit
fi
start_app "$app" "$mode" "$artifact" --network "$network" \
    -e "QUARKUS_IPFS_KUBO_API_URL=http://$kubo:5001" \
    -e QUARKUS_IPFS_KUBO_BEARER_TOKEN=central-consumer-test

result=$(timeout --kill-after=10s 90s docker wait "$app")
docker logs "$app"
test "$result" = 0
