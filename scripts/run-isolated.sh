#!/usr/bin/env bash
# Runs JavaByte with no network and a read-only file system, in fresh user, network and mount
# namespaces.
#
# JavaByte already simulates every network call and every file a sample touches. This wrapper
# is a second, independent layer that does not depend on the emulator being right: if a defect
# ever let a sample act for real, the kernel would still refuse. Use it whenever you analyse
# live malware. Inside the wrapper:
#
#   - there is no network interface to send on;
#   - the whole file system is read-only, except the output folder (-o/--out, or
#     ./javabyte-out for "run" without it) and a private, empty /tmp that is gone afterwards;
#   - /run, /tmp and /dev/shm, where desktop and agent services keep their Unix sockets, are
#     covered by empty folders. What the run is given by name stays visible at its own path
#     even there: every argument that names an existing file or folder (the sample, the
#     config), the jar, and the Java runtime;
#   - the environment is cut down to a short list (PATH, HOME, language), so tokens in your
#     shell are not there to be read and JAVA_TOOL_OPTIONS and the like do not reach the JVM;
#   - Java has no capabilities, so nothing in the run can undo any of this;
#   - the run has no controlling terminal, so it cannot type into yours.
#
# If any of that cannot be set up, nothing is run. The desktop app does the same for each
# analysis (gui/crates/backend/src/isolation.rs); keep the two alike.
#
# Not covered: files outside /run, /tmp and /dev/shm stay readable, and a Unix socket that a
# program keeps somewhere else (under your home folder, say) can still be connected to. There
# is no process namespace either: the run can see your processes and send them signals (it
# cannot read their memory or environment). A file
# the config names (a library on "classpath", say) that lies under /tmp is hidden unless it is
# also on the command line; keep such files elsewhere. An output folder set only in the config
# (output.directory) is not known here: pass it with -o.
#
# Usage:  scripts/run-isolated.sh run sample.jar [javabyte options...]
#
# Requires Linux with unprivileged user namespaces, and util-linux (unshare, mount, setpriv,
# setsid).
# Where those are not allowed, use a container with no network, a read-only root and only the
# output directory writable, for example:
#   docker run --rm --network none --read-only --tmpfs /tmp -v "$PWD:/work" -w /work \
#       eclipse-temurin:21 java -jar target/javabyte-0.2.0.jar run samples/x.jar -o out/x
set -euo pipefail

private_temp=/tmp
covered_folders=(/run /var/run "$private_temp" /dev/shm)

# Second stage: this same script, started again inside the new namespaces (see the end).
# Arguments: the output folder (or ""), the number of paths to keep visible, those paths, "--",
# then the command to run.
if [[ "${1:-}" == "--inside-namespaces" ]]; then
    shift
    stop() { echo "error: the isolation could not be set up ($*); nothing was run" >&2; exit 2; }
    trap 'stop "line $LINENO"' ERR
    output="$1" count="$2"
    shift 2
    keep=("${@:1:count}")
    shift "$count"
    [[ "$1" == "--" ]] || stop "bad arguments"
    shift
    # Never on the real system: what follows is only right in namespaces made for it, where
    # the one network interface is the loopback.
    [[ "$(tail -n +3 /proc/self/net/dev | cut -d: -f1 | tr -d ' ')" == lo ]] || stop "not in a new namespace"

    # The folder this was started in. If it is inside a covered folder, the run must not stay
    # in the original (from there, ".." leads to everything that was covered): it starts in an
    # empty folder of the same name instead, where what was named is found at the same
    # relative paths.
    started_in="$(pwd -P)"

    # Mounts made from here on stay in this namespace. (-n throughout: mount keeps no notes
    # of its own, which it would otherwise write under the new /run.)
    mount -n --make-rprivate /

    # Hold on to what must stay visible, before anything is covered.
    [[ -z "$output" ]] || keep+=("$output")
    sources=()
    for path in "${keep[@]}"; do
        exec {fd}< "$path"
        sources+=("$fd")
    done

    # Everything read-only, one mount at a time. Mount points are written with \040 for a
    # space and the like. One this user cannot reach is skipped: the run cannot reach it
    # either. Any other failure stops here.
    declare -A unreachable=()
    while read -r _ _ _ _ mount_point _; do
        mount_point="$(printf '%b' "${mount_point//\\/\\0}")"
        if [[ ! -e "$mount_point" ]]; then
            unreachable["$mount_point"]=1
            continue
        fi
        mount -n -o remount,bind,ro "$mount_point"
    done < /proc/self/mountinfo

    # Empty folders over the places host services keep their sockets; /tmp is the run's own.
    covers=()
    for folder in "${covered_folders[@]}"; do
        [[ -d "$folder" ]] || continue
        folder="$(realpath "$folder")"
        [[ " ${covers[*]-} " == *" $folder "* ]] && continue
        mount -n -t tmpfs -o nosuid,nodev,mode=1777 tmpfs "$folder"
        covers+=("$folder")
    done

    # Put back what was named, at its own path: a copy of a read-only mount, so read-only.
    # The output folder is mounted onto itself wherever it is, and made writable.
    for i in "${!keep[@]}"; do
        path="${keep[$i]}"
        inside=""
        for folder in "${covers[@]}"; do
            [[ "$path" == "$folder" ]] && stop "$path cannot be used with isolation on"
            [[ "$path" == "$folder"/* ]] && inside=yes
        done
        [[ -n "$inside" || "$path" == "$output" ]] || continue
        if [[ -n "$inside" && ! -e "$path" ]]; then
            mkdir -p "$(dirname "$path")"
            if [[ -d "/proc/self/fd/${sources[$i]}" ]]; then mkdir "$path"; else : > "$path"; fi
        fi
        # Not canonicalized: the kernel must follow the open file, not its (covered) name.
        mount -n --no-canonicalize --rbind "/proc/self/fd/${sources[$i]}" "$path"
        [[ "$path" != "$output" ]] || mount -n -o remount,bind,rw "$path"
    done
    for folder in "${covers[@]}"; do
        [[ "$started_in/" != "$folder"/* ]] || mkdir -p "$started_in"
    done
    cd "$started_in"
    for folder in "${covers[@]}"; do
        [[ "$folder" == "$private_temp" ]] || mount -n -o remount,bind,ro "$folder"
    done
    for fd in "${sources[@]}"; do
        exec {fd}<&-
    done

    # Proof before anything runs: every mount that can be reached is read-only, except the
    # private /tmp and the output folder. (Of two mounts on one place, the later is on top.)
    declare -A options=()
    while read -r _ _ _ _ mount_point mount_options _; do
        options["$(printf '%b' "${mount_point//\\/\\0}")"]="$mount_options"
    done < /proc/self/mountinfo
    for mount_point in "${!options[@]}"; do
        [[ "${options[$mount_point]}" == ro* || "$mount_point" == "$private_temp" || "$mount_point" == "$output" \
            || -n "${unreachable[$mount_point]:-}" ]] || stop "$mount_point is still writable"
    done
    [[ "${options[$private_temp]:-}" == rw* ]] || stop "$private_temp is not the run's own"

    trap - ERR
    # A short list of the environment, and no capabilities: the user is root only inside this
    # namespace, and with an empty bounding set "exec" gives Java none of root's powers there,
    # so it cannot unmount or remount anything.
    passed=("TMPDIR=$private_temp")
    for name in PATH HOME USER LOGNAME LANG LANGUAGE TZ JAVABYTE_BRIDGE_PYTHON "${!LC_@}"; do
        [[ -z "${!name+set}" ]] || passed+=("$name=${!name}")
    done
    exec env -i "${passed[@]}" setpriv --bounding-set=-all --inh-caps=-all -- "$@"
fi

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
# --map-root-user makes the namespaces usable without privileges; it grants nothing outside.
namespaces=(unshare --user --map-root-user --net --mount)
if ! "${namespaces[@]}" true 2>/dev/null; then
    echo "error: this system does not allow unprivileged namespaces; use a container instead" >&2
    exit 2
fi
for tool in setpriv setsid mount realpath; do
    command -v "$tool" >/dev/null || { echo "error: $tool is needed (util-linux, coreutils)" >&2; exit 2; }
done
java="$(command -v java)" || { echo "error: java is not on PATH" >&2; exit 2; }

# The output folder: -o DIR, --out DIR or --out=DIR; for "run" without one, the tool's default.
output=""
previous=""
for argument in "$@"; do
    case "$previous" in -o | --out) output="$argument" ;; esac
    case "$argument" in --out=*) output="${argument#--out=}" ;; esac
    previous="$argument"
done
if [[ -z "$output" && "${1:-}" == "run" ]]; then
    output="javabyte-out"
fi
if [[ -n "$output" ]]; then
    mkdir -p "$output"
    output="$(realpath "$output")"
fi
# What must stay visible: the jar, the Java runtime (the folder above the real java's "bin"),
# the bridge's Python if one is named, and every argument that names something that exists.
keep=("$jar" "$(dirname "$(dirname "$(realpath "$java")")")")
if [[ -n "${JAVABYTE_BRIDGE_PYTHON:-}" && -e "$JAVABYTE_BRIDGE_PYTHON" ]]; then
    keep+=("$(dirname "$(dirname "$(realpath "$JAVABYTE_BRIDGE_PYTHON")")")")
fi
for argument in "$@"; do
    [[ "$argument" == -* || ! -e "$argument" ]] || keep+=("$(realpath "$argument")")
done
visible=()
for path in "${keep[@]}"; do
    [[ "$path" == "$output" ]] || visible+=("$path")
done

# The run gets a session of its own (setsid), so it has no controlling terminal. With one, a
# process can push text into the terminal's input (the TIOCSTI ioctl, allowed on older kernels
# and wherever dev.tty.legacy_tiocsti is on), and the shell it was started from would run that
# text after the run ended, outside every namespace. Standard input, output and error are still
# this terminal's, so "--control stdin" and Ctrl+C work: Ctrl+C reaches this script, which is
# still in the terminal's foreground, and is passed on.
#
# Started in the background so that setsid need not fork: a background job of a script is not
# a process group leader. Every later step replaces the process (unshare, bash, env, setpriv,
# java), so the job's id is Java's and a signal sent to it arrives. "<&0" keeps standard input,
# which a background job would otherwise have replaced by /dev/null.
setsid "${namespaces[@]}" bash "${BASH_SOURCE[0]}" --inside-namespaces "$output" "${#visible[@]}" "${visible[@]}" -- \
    "$java" "-Djava.io.tmpdir=$private_temp" -jar "$jar" "$@" <&0 &
run=$!
trap 'kill -INT "$run" 2>/dev/null' INT
trap 'kill -TERM "$run" 2>/dev/null' TERM HUP
# "wait" returns early when a trapped signal arrives; wait again until the run itself has ended.
status=0
while true; do
    wait "$run" && status=0 || status=$?
    kill -0 "$run" 2>/dev/null || break
done
exit "$status"
