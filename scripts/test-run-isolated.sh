#!/usr/bin/env bash
# Tests scripts/run-isolated.sh: which jar it picks, and what the isolation it sets up allows.
#
# The launcher must run the jar of the version in pom.xml, not whichever jar sorts first in
# target/: a target/ that still holds an older build would otherwise run the old engine with no
# warning. The test copies the launcher into a throwaway tree with a made-up pom.xml and empty
# jars, and puts stand-ins for "unshare" and "java" first on PATH that only print what they
# would run. That part runs everywhere.
#
# The isolation part uses the real "unshare" with a stand-in for "java" that tries what an
# escaped sample would: writing to the home folder, to the shared /tmp and next to the output
# folder, reaching a socket under /tmp, reading the environment, and undoing the mounts. It is
# skipped, with a message, where the system does not allow unprivileged namespaces, unless
# JAVABYTE_ISOLATION_TESTS_REQUIRED is set.
#
# Usage:  scripts/test-run-isolated.sh        (prints PASS lines, or FAIL and exits 1)
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
# Stand-ins: "unshare" only prints the command it was given, after the launcher's own second
# stage and its arguments (it must never run that stage: outside a namespace it would act on
# the real system); "java" is never reached.
cat > "$tree/bin/unshare" <<'STAND_IN'
#!/bin/bash
while [[ $# -gt 0 && "$1" != "--" ]]; do [[ "$1" == true ]] && exit 0; shift; done
shift
echo "$(basename "$1") ${*:2}"
STAND_IN
printf '#!/bin/bash\necho "java $*"\n' > "$tree/bin/java"
chmod +x "$tree/bin/unshare" "$tree/bin/java"
launch() { (cd "$tree" && PATH="$tree/bin:$PATH" bash "$tree/repo/scripts/run-isolated.sh" inspect x.jar 2>&1); }
fail() { echo "FAIL $*" >&2; exit 1; }

# Only older and differently named jars: refused, naming the jar it wanted.
touch "$tree/repo/target/javabyte-0.10.0.jar" "$tree/repo/target/javabyte-0.1.0.jar" \
    "$tree/repo/target/original-javabyte-0.9.0.jar"
if out="$(launch)"; then fail "ran without javabyte-0.9.0.jar: $out"; fi
[[ "$out" == *"javabyte-0.9.0.jar not found"* ]] || fail "unexpected refusal: $out"

# With the right jar beside the others: exactly that one runs.
touch "$tree/repo/target/javabyte-0.9.0.jar"
out="$(launch)" || fail "refused although javabyte-0.9.0.jar exists: $out"
[[ "$out" == "java -Djava.io.tmpdir=/tmp -jar $tree/repo/target/javabyte-0.9.0.jar inspect x.jar" ]] || fail "ran the wrong jar: $out"

echo "PASS run-isolated.sh runs the jar of the version in pom.xml"

# --- The isolation itself, with the real unshare. ---
rm "$tree/bin/unshare"
if ! unshare --user --map-root-user --net --mount true 2>/dev/null; then
    [[ -z "${JAVABYTE_ISOLATION_TESTS_REQUIRED:-}" ]] || fail "this system does not allow unprivileged namespaces"
    echo "SKIP the isolation tests: this system does not allow unprivileged namespaces"
    exit 0
fi
# The stand-in for java: reports what an escaped sample could and could not do. The marks it
# tries to leave carry this test's process id, and are removed below if one ever appears.
mark="javabyte-isolation-test-$$"
mkdir -p "$tree/jdk/bin"
rm "$tree/bin/java"
cat > "$tree/jdk/bin/java" <<'STAND_IN'
#!/bin/bash
mark="$JAVABYTE_TEST_MARK_NAME"   # not passed on: must be empty
mark="${mark:-$(head -n 1 "$(dirname "$0")/mark")}"
socket="$(tail -n 1 "$(dirname "$0")/mark")"
while [[ $# -gt 0 ]]; do
    case "$1" in -o) out="$2" ;; -c) config="$2" ;; run) sample="$2" ;; esac
    shift
done
try() { if ( : > "$1" ) 2>/dev/null; then echo "$2: written"; else echo "$2: refused"; fi; }
try "$HOME/.$mark" home
try "/tmp/$mark" private-temp
try "/var/tmp/$mark" var-tmp
try "$out/../$mark" beside-output
echo "above: $(ls -A .. | tr '\n' ' ')"
try "$out/report.json" output
try "$sample" sample
echo "sample: $(cat "$sample" 2>&1)"
echo "config: $(cat "$config" 2>&1)"
echo "neighbour: $([[ -e "$(dirname "$sample")/neighbour" ]] && echo visible || echo hidden)"
echo "socket: $([[ -e "$socket" ]] && echo visible || echo hidden)"
echo "temp: $TMPDIR, holding: $(ls -A /tmp | grep -v "$mark" | tr '\n' ' ')"
echo "run and shm: $(ls -A /run /dev/shm 2>/dev/null | grep -vc ':$\|^$') entries"
echo "secret: ${JAVABYTE_TEST_SECRET:-not passed}"
echo "network: $(tail -n +3 /proc/self/net/dev | cut -d: -f1 | tr -d ' ' | tr '\n' ' ')"
echo "capabilities: $(grep -E '^Cap(Prm|Eff|Bnd)' /proc/self/status | cut -f2 | sort -u | tr '\n' ' ')"
echo "undo: $(umount /tmp 2>/dev/null && echo unmounted; mount -o remount,bind,rw / 2>/dev/null && echo remounted; try "$HOME/.$mark" home-again)"
STAND_IN
chmod +x "$tree/jdk/bin/java"
printf '%s\n%s\n' "$mark" "$tree/agent.sock" > "$tree/jdk/bin/mark"
# A sample, a config and an output folder under /tmp (this tree is), with a neighbour that is
# not named, and a socket of the kind an agent listens on.
mkdir -p "$tree/work/samples"
echo "the sample" > "$tree/work/samples/x.jar"
echo "the config" > "$tree/work/c.yaml"
touch "$tree/work/samples/neighbour"
if command -v python3 >/dev/null; then
    python3 -I -c 'import socket, sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$tree/agent.sock"
else
    touch "$tree/agent.sock"
fi
home="${HOME:?HOME must be set}"
cleanup() { rm -f "$home/.$mark" "/tmp/$mark" "/var/tmp/$mark"; rm -rf "$tree"; }
trap cleanup EXIT
out="$(cd "$tree/work" && JAVABYTE_TEST_SECRET=s3cret JAVABYTE_TEST_MARK_NAME=leaked PATH="$tree/jdk/bin:$PATH" \
    bash "$tree/repo/scripts/run-isolated.sh" run samples/x.jar -c "$tree/work/c.yaml" -o out 2>&1)" \
    || fail "the isolated run failed: $out"
expect() { grep -qxF "$1" <<<"$out" || fail "expected \"$1\" in: $out"; }
expect "home: refused"
expect "private-temp: written"
expect "var-tmp: refused"
# Beside the output folder is the run's own empty copy of the folder it started in (a place
# in its private /tmp), not the real one, and above it there is nothing but the ways to what
# was named: no socket, no "bin".
expect "beside-output: written"
expect "above: jdk repo work "
expect "output: written"
expect "sample: refused"
expect "sample: the sample"
expect "config: the config"
expect "neighbour: hidden"
expect "socket: hidden"
# Nothing of the shared /tmp but the empty folders that lead to what was named.
expect "temp: /tmp, holding: $(basename "$tree") "
expect "run and shm: 0 entries"
expect "secret: not passed"
expect "network: lo "
expect "capabilities: 0000000000000000 "
expect "undo: home-again: refused"
[[ ! -e "$home/.$mark" ]] || fail "a file appeared in the home folder"
[[ ! -e "/tmp/$mark" ]] || fail "the private temporary folder is the shared /tmp"
[[ ! -e "$tree/work/$mark" ]] || fail "a file appeared beside the output folder"
[[ -f "$tree/work/out/report.json" ]] || fail "the output folder was not written"
echo "PASS an isolated run writes only to its output folder and its private temporary folder"

# Nothing runs when the isolation cannot be set up: here, an output folder that is all of /tmp.
if out="$(cd "$tree/work" && PATH="$tree/jdk/bin:$PATH" bash "$tree/repo/scripts/run-isolated.sh" run samples/x.jar -o /tmp 2>&1)"; then
    fail "ran with all of /tmp as its output folder: $out"
fi
[[ "$out" == *"nothing was run"* && "$out" != *"home:"* ]] || fail "unexpected refusal: $out"
echo "PASS run-isolated.sh runs nothing when the isolation cannot be set up"
