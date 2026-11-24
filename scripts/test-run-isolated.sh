#!/usr/bin/env bash
# Tests how scripts/run-isolated.sh picks its jar, without running Java or making a namespace.
#
# The launcher must run the jar of the version in pom.xml, not whichever jar sorts first in
# target/: a target/ that still holds an older build would otherwise run the old engine with no
# warning. The test copies the launcher into a throwaway tree with a made-up pom.xml and empty
# jars, and puts stand-ins for "unshare" and "java" first on PATH that only print what they
# would run.
#
# Usage:  scripts/test-run-isolated.sh        (prints PASS, or FAIL and exits 1)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tree="$(mktemp -d)"
trap 'rm -rf "$tree"' EXIT
mkdir -p "$tree/repo/scripts" "$tree/repo/target" "$tree/bin"
cp "$here/run-isolated.sh" "$tree/repo/scripts/"
# The project's version is the first <version> at two spaces; a dependency's comes later.
cat > "$tree/repo/pom.xml" <<'POM'
<project>
  <artifactId>javabyte</artifactId>
  <version>0.9.0</version>
  <dependencies>
    <dependency>
      <version>1.2.3</version>
    </dependency>
  </dependencies>
</project>
POM
# Stand-ins: "unshare" drops its options and runs the rest; "java" prints its arguments.
printf '#!/bin/bash\nwhile [[ "$1" == --* ]]; do shift; done\nexec "$@"\n' > "$tree/bin/unshare"
printf '#!/bin/bash\necho "java $*"\n' > "$tree/bin/java"
# The launcher covers host service sockets with mounts inside its namespace. Here there is no
# namespace, so "mount" must be a stand-in too: it only records what it was asked to do.
printf '#!/bin/bash\necho "$*" >> "%s/mounts"\n' "$tree" > "$tree/bin/mount"
chmod +x "$tree/bin/unshare" "$tree/bin/java" "$tree/bin/mount"
launch() { PATH="$tree/bin:$PATH" bash "$tree/repo/scripts/run-isolated.sh" run x.jar 2>&1; }
fail() { echo "FAIL $*" >&2; exit 1; }

# Only older and differently named jars: refused, naming the jar it wanted.
touch "$tree/repo/target/javabyte-0.10.0.jar" "$tree/repo/target/javabyte-0.1.0.jar" \
    "$tree/repo/target/original-javabyte-0.9.0.jar"
if out="$(launch)"; then fail "ran without javabyte-0.9.0.jar: $out"; fi
[[ "$out" == *"javabyte-0.9.0.jar not found"* ]] || fail "unexpected refusal: $out"

# With the right jar beside the others: exactly that one runs.
touch "$tree/repo/target/javabyte-0.9.0.jar"
out="$(launch)" || fail "refused although javabyte-0.9.0.jar exists: $out"
[[ "$out" == "java -jar $tree/repo/target/javabyte-0.9.0.jar run x.jar" ]] || fail "ran the wrong jar: $out"

# Every cover is an empty read-only folder or /dev/null, never anything else.
if [[ -f "$tree/mounts" ]] && grep -qv -e '^-t tmpfs -o ro,nosuid,nodev,noexec tmpfs /' -e '^--bind /dev/null /' "$tree/mounts"; then
    fail "an unexpected mount was requested: $(cat "$tree/mounts")"
fi

echo "PASS run-isolated.sh runs the jar of the version in pom.xml"
