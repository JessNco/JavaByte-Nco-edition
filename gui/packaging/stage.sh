#!/usr/bin/env bash
# Prepares what an installer bundles, in gui/target/bundle-input/ (docs/gui/PHASE5-PLAN.md):
#
#   javabyte.jar               the tool, from `mvn package` at the repository root
#   runtime/                   a Java runtime made with jlink from $JAVA_HOME (Java 21)
#   bridge/python/             with --bridge: a standalone Python with the native bridge's packages
#   THIRD-PARTY-NOTICES.txt    the licences of what is bundled
#
# Usage: packaging/stage.sh [--bridge]      (from gui/; Linux, or Git Bash on Windows)
#
# The bridge's Python comes from uv, which downloads a relocatable CPython build
# (python-build-standalone) and checks it against the checksum uv carries for it. uv's version
# and the checksums of its files are in packaging/uv-requirements.txt, so the same Python is
# bundled every time. The packages installed into it are those in
# scripts/native-bridge-requirements.txt, each file checked against the checksum listed there
# (scripts/install-native-bridge-packages.sh).
set -euo pipefail

gui="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$gui/.." && pwd)"
out="$gui/target/bundle-input"
bridge=false
[[ "${1:-}" == "--bridge" ]] && bridge=true

# Every standard module, not only those jdeps finds: a sample may call any standard API, and a
# missing class would change what the analysis sees. jdk.charsets for the many encodings malware
# uses; jdk.localedata for locale-dependent formatting; jdk.unsupported for sun.misc.Unsafe.
modules="java.se,jdk.unsupported,jdk.charsets,jdk.zipfs,jdk.localedata,jdk.crypto.ec"
python_version="3.12"

version="$("$gui/packaging/version.sh")"
jar="$repo/target/javabyte-$version.jar"
if [[ ! -f "$jar" ]]; then
    echo "error: $jar not found; run 'mvn package' at the repository root first" >&2
    exit 2
fi
java_home="${JAVA_HOME:?set JAVA_HOME to a Java 21 JDK}"

rm -rf "$out"
mkdir -p "$out"
cp "$jar" "$out/javabyte.jar"
cp "$gui/packaging/THIRD-PARTY-NOTICES.txt" "$out/"
cp "$repo/LICENSE" "$out/LICENSE.txt"

echo "jlink: $modules"
"$java_home/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
    --compress zip-9 --output "$out/runtime"
"$out/runtime/bin/java" -jar "$out/javabyte.jar" schema --json > /dev/null
echo "runtime ok: $(du -sh "$out/runtime" | cut -f1)"

if $bridge; then
    work="$(mktemp -d)"
    # Any Python will do to fetch uv; on Windows it is called "python".
    host_python="$(command -v python3 || command -v python)"
    "$host_python" -m venv "$work/uv-venv"
    if [[ -x "$work/uv-venv/Scripts/python.exe" ]]; then tools="$work/uv-venv/Scripts"; else tools="$work/uv-venv/bin"; fi
    "$tools/python" -m pip install --quiet --disable-pip-version-check --require-hashes --only-binary :all: \
        -r "$gui/packaging/uv-requirements.txt"
    export UV_PYTHON_INSTALL_DIR="$work/pythons"
    "$tools/uv" python install "$python_version"
    found="$("$tools/uv" python find --managed-python "$python_version")"
    command -v cygpath > /dev/null && found="$(cygpath -u "$found")"
    # Linux: <root>/bin/python3.12; Windows: <root>/python.exe.
    root="$(dirname "$found")"
    [[ "$(basename "$root")" == "bin" ]] && root="$(dirname "$root")"
    mkdir -p "$out/bridge"
    cp -R "$root" "$out/bridge/python"
    if [[ -x "$out/bridge/python/python.exe" ]]; then py="$out/bridge/python/python.exe"; else py="$out/bridge/python/bin/python3"; fi
    # uv marks the Python as its own; this copy belongs to the app, so pip may install into it.
    find "$out/bridge/python" -name EXTERNALLY-MANAGED -delete
    "$repo/scripts/install-native-bridge-packages.sh" "$py"
    # The standard library's own tests are a large part of a Python and never used here.
    find "$out/bridge/python" -type d -path '*/lib*/python3*/test' -prune -exec rm -rf {} +
    find "$out/bridge/python" -type d -name 'Lib' -prune -exec rm -rf {}/test \;
    "$py" -I -W ignore -c "import speakeasy"
    echo "bridge ok: $(du -sh "$out/bridge" | cut -f1)"
    rm -rf "$work"
fi
