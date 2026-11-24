#!/usr/bin/env bash
# Runs JavaByte inside fresh network and mount namespaces, so that the process has no network at
# all and cannot reach the desktop's services through their sockets.
#
# JavaByte already simulates every network call a sample makes. This wrapper is a second,
# independent layer: if a defect ever let a real connection attempt through, the kernel would
# still have nowhere to send it. Use it whenever you analyse live malware.
#
# It does not protect files: the run can still read and write whatever your user can. For that,
# use the container below.
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
if ! unshare --user --map-root-user --net --mount true 2>/dev/null; then
    echo "error: this system does not allow unprivileged network and mount namespaces; use a container instead" >&2
    exit 2
fi

# A network namespace does not hide Unix sockets that live in the file system, and some of the
# services behind them make connections for whoever asks: the session D-Bus (whose portal opens
# a browser at an address), systemd-resolved, nscd, Avahi, CUPS, Docker, X11 and Wayland, an
# ssh-agent, a tmux server. So the run also gets a mount namespace of its own in which an empty
# read-only folder covers each of them, as the desktop app's isolation does
# (gui/crates/backend/src/isolation.rs; keep the two lists the same).
uid="$(id -u)"
hidden_folders=("/run/user/$uid" /run/dbus /run/systemd /run/nscd /var/run/nscd /run/avahi-daemon /run/cups
    /tmp/.X11-unix "/tmp/tmux-$uid")
hidden_sockets=(/run/docker.sock /var/run/docker.sock /run/snapd.socket /run/snapd-snap.socket)
# An ssh-agent keeps its socket in a folder of its own under the temporary folder.
if [[ "${SSH_AUTH_SOCK:-}" == /tmp/ssh-*/* ]]; then
    hidden_folders+=("$(dirname "$SSH_AUTH_SOCK")")
fi

# Runs inside the new namespaces. Mounts made here stay in this namespace and never reach the
# desktop. A cover that cannot be put in place stops the run: it must not start less isolated.
inside='
set -euo pipefail
jar="$1"; folders="$2"; sockets="$3"; shift 3
while IFS= read -r folder; do
    if [[ -n "$folder" && -d "$folder" ]]; then
        mount -t tmpfs -o ro,nosuid,nodev,noexec tmpfs "$folder"
    fi
done <<< "$folders"
while IFS= read -r socket; do
    if [[ -n "$socket" && -e "$socket" ]]; then
        mount --bind /dev/null "$socket"
    fi
done <<< "$sockets"
unset DBUS_SESSION_BUS_ADDRESS DISPLAY WAYLAND_DISPLAY XDG_RUNTIME_DIR SSH_AUTH_SOCK GPG_AGENT_INFO TMUX
exec java -jar "$jar" "$@"
'
# --map-root-user makes the namespaces usable without privileges; it grants nothing outside them.
exec unshare --user --map-root-user --net --mount bash -c "$inside" run-isolated "$jar" \
    "$(printf '%s\n' "${hidden_folders[@]}")" "$(printf '%s\n' "${hidden_sockets[@]}")" "$@"
