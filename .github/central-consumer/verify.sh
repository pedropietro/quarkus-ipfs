#!/usr/bin/env bash
set -euo pipefail

ipfs_version=${1:?Usage: verify.sh ipfs-version quarkus-version [jvm|native]}
quarkus_version=${2:?Pass the Quarkus version}
mode=${3:-jvm}
package_args=()
case "$mode" in
    jvm) ;;
    native) package_args=(-Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false) ;;
    *) printf 'Expected jvm or native, got %s\n' "$mode" >&2; exit 1 ;;
esac
fixture=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$fixture/../.." && pwd -P)
temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$temp_root/quarkus-ipfs-consumer.XXXXXXXXXX")
trap 'rm -rf -- "$work"' EXIT

cp -- "$fixture/pom.xml" "$work/"
cp -R -- "$fixture/src" "$work/"
printf '<settings/>\n' > "$work/settings.xml"
cd -- "$work"
printf 'Resolving Quarkus IPFS %s with an empty local Maven repository (%s).\n' "$ipfs_version" "$mode"
bash "$root/mvnw" -B -ntp -s "$work/settings.xml" -gs "$work/settings.xml" \
    "-Dmaven.repo.local=$work/repository" "-Dipfs.version=$ipfs_version" "-Dquarkus.version=$quarkus_version" "${package_args[@]}" package

if [[ "$mode" == native ]]; then
    shopt -s nullglob
    runners=(target/*-runner)
    if (( ${#runners[@]} != 1 )) || [[ ! -x "${runners[0]}" ]]; then
        printf 'Expected one executable native consumer in target/.\n' >&2
        exit 1
    fi
    timeout --kill-after=10s 90s "${runners[0]}" -Dquarkus.ipfs.run-bitswap=false
else
    timeout --kill-after=10s 90s java -Dquarkus.ipfs.run-bitswap=false -jar target/quarkus-app/quarkus-run.jar
fi
