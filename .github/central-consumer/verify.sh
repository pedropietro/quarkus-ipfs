#!/usr/bin/env bash
set -euo pipefail

ipfs_version=${1:?Usage: verify.sh ipfs-version quarkus-version}
quarkus_version=${2:?Pass the Quarkus version}
fixture=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$fixture/../.." && pwd -P)
temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$temp_root/quarkus-ipfs-consumer.XXXXXXXXXX")
trap 'rm -rf -- "$work"' EXIT

cp -- "$fixture/pom.xml" "$work/"
cp -R -- "$fixture/src" "$work/"
printf '<settings/>\n' > "$work/settings.xml"
cd -- "$work"
printf 'Resolving Quarkus IPFS %s with an empty local Maven repository.\n' "$ipfs_version"
bash "$root/mvnw" -B -ntp -s "$work/settings.xml" -gs "$work/settings.xml" \
    "-Dmaven.repo.local=$work/repository" "-Dipfs.version=$ipfs_version" "-Dquarkus.version=$quarkus_version" package

timeout --kill-after=10s 90s java -Dquarkus.ipfs.run-bitswap=false -jar target/quarkus-app/quarkus-run.jar
