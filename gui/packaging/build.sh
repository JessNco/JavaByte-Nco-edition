#!/usr/bin/env bash
# Builds the installers for this system into gui/target/installers/ (docs/gui/PHASE5-PLAN.md):
#
#   Linux:    JavaByte-<version>-linux-x86_64[-with-native-bridge].deb and .AppImage
#   Windows:  JavaByte-<version>-windows-x86_64[-with-native-bridge].msi and -portable.zip
#
# Usage: packaging/build.sh [--bridge]      (from gui/; needs what packaging/stage.sh needs,
#                                            plus Trunk, Node.js for the Tauri CLI, and on
#                                            Windows the WiX toolset Tauri downloads itself)
set -euo pipefail

gui="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$gui"
bridge_flag=()
suffix=""
if [[ "${1:-}" == "--bridge" ]]; then
    bridge_flag=(--bridge)
    suffix="-with-native-bridge"
fi
tauri=(${TAURI_CLI:-npx --yes @tauri-apps/cli@2.12.5})
version="$(packaging/version.sh)"

packaging/stage.sh "${bridge_flag[@]}"
# Absolute paths: the Tauri CLI runs in app/.
configs=(--config "$gui/packaging/tauri.bundle.conf.json")
[[ -n "$suffix" ]] && configs+=(--config "$gui/packaging/tauri.bridge.conf.json")

case "$(uname -s)" in
    Linux) os=linux; bundles=deb,appimage ;;
    MINGW* | MSYS* | CYGWIN*) os=windows; bundles=msi ;;
    *) echo "error: installers are built on Linux and Windows only" >&2; exit 2 ;;
esac

# Earlier bundles would be picked up by the copy below.
rm -rf target/release/bundle
(cd app && "${tauri[@]}" build --bundles "$bundles" "${configs[@]}")

out="target/installers"
mkdir -p "$out"
name="JavaByte-$version-$os-x86_64$suffix"
if [[ $os == linux ]]; then
    cp target/release/bundle/deb/*.deb "$out/$name.deb"
    cp target/release/bundle/appimage/*.AppImage "$out/$name.AppImage"
else
    cp target/release/bundle/msi/*.msi "$out/$name.msi"
    # The portable edition: the app with its resources beside it, which is where it looks
    # when it was not installed.
    portable="$(mktemp -d)/$name-portable"
    mkdir -p "$portable"
    cp target/release/javabyte-gui.exe "$portable/"
    cp -R target/bundle-input/. "$portable/"
    (cd "$(dirname "$portable")" && powershell -NoProfile -Command "Compress-Archive -Path '$name-portable' -DestinationPath '$name-portable.zip'")
    mv "$(dirname "$portable")/$name-portable.zip" "$out/"
fi
ls -la "$out"
