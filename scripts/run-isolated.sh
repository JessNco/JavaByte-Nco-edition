#!/usr/bin/env bash
# Runs JavaByte inside a fresh network namespace, so that the process has no network at all.
#
# JavaByte already simulates every network call a sample makes. This wrapper is a second,
# independent layer: if a defect ever let a real connection attempt through, the kernel would
# still have nowhere to send it. Use it whenever you analyse live malware.
#
# Usage:  scripts/run-isolated.sh run sample.jar [javabyte options...]
#
# Requires Linux with unprivileged user namespaces (util-linux "unshare"). For filesystem
# isolation as well, run the tool in a container with a read-only root and only the output
# directory writable, for example:
#   docker run --rm --network none --read-only -v "$PWD:/work" -w /work eclipse-temurin:21 \
#       java -jar target/javabyte-0.1.0.jar run samples/x.jar -o out/x
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$(ls "$here"/target/javabyte-*.jar 2>/dev/null | grep -v original | head -n 1 || true)"
if [[ -z "$jar" ]]; then
    echo "error: build first with: mvn -q package" >&2
    exit 2
fi
if ! unshare --user --map-root-user --net true 2>/dev/null; then
    echo "error: this system does not allow unprivileged network namespaces; use a container instead" >&2
    exit 2
fi
# --map-root-user makes the namespace usable without privileges; it grants nothing outside it.
exec unshare --user --map-root-user --net java -jar "$jar" "$@"
