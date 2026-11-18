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
#       java -jar target/javabyte-0.2.0.jar run samples/x.jar -o out/x
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The jar of the version in pom.xml, never another one: target/ keeps jars of earlier versions
# until "mvn clean", and an older engine would silently give a different analysis. The project's
# own <version> is the first one in pom.xml, before any dependency (as gui/packaging/version.sh
# reads it).
version="$(sed -n 's:^  <version>\(.*\)</version>:\1:p' "$here/pom.xml" | head -n 1)"
if [[ -z "$version" ]]; then
    echo "error: cannot read the project version from $here/pom.xml" >&2
    exit 2
fi
jar="$here/target/javabyte-$version.jar"
if [[ ! -f "$jar" ]]; then
    echo "error: $jar not found; build first with: mvn -q package" >&2
    exit 2
fi
if ! unshare --user --map-root-user --net true 2>/dev/null; then
    echo "error: this system does not allow unprivileged network namespaces; use a container instead" >&2
    exit 2
fi
# --map-root-user makes the namespace usable without privileges; it grants nothing outside it.
exec unshare --user --map-root-user --net java -jar "$jar" "$@"
