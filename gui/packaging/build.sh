#!/usr/bin/env bash
# Builds the installers for this system into gui/target/installers/ (docs/gui/PHASE5-PLAN.md):
#
#   Linux:    JavaByte-<version>-linux-x86_64[-with-native-bridge].deb and .AppImage
#   Windows:  JavaByte-<version>-windows-x86_64[-with-native-bridge].msi and -portable.zip, and
#             with the standard edition the sandbox add-on (-sandbox-addon.msi and .zip; WiX 5)
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
# The bridge overlay is merged on top of the bundle overlay (JSON Merge Patch), so it lists only the extra bridge/ resource.
configs=(--config "$gui/packaging/tauri.bundle.conf.json")
[[ -n "$suffix" ]] && configs+=(--config "$gui/packaging/tauri.bridge.conf.json")

case "$(uname -s)" in
    Linux) os=linux; bundles=deb,appimage ;;
    MINGW* | MSYS* | CYGWIN*) os=windows; bundles=msi ;;
    *) echo "error: installers are built on Linux and Windows only" >&2; exit 2 ;;
esac

# Earlier bundles would be picked up by the copy below.
rm -rf target/release/bundle
if [[ $os == linux ]]; then
    # The AppImage tool (linuxdeploy) resolves the libraries of every program it packs, the Java
    # runtime's included. Those find libjvm.so by path at run time, not through the linker, so
    # it is shown where they are. Stripping is left off: the runtime's libraries are already
    # stripped, and strip fails on some of them.
    runtime="$gui/target/bundle-input/runtime/lib"
    export LD_LIBRARY_PATH="$runtime/server:$runtime${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export NO_STRIP=true
fi
(cd app && "${tauri[@]}" build --verbose --bundles "$bundles" "${configs[@]}")

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
    # The sandbox add-on, a separate download (docs/gui/WINDOWS-SANDBOX.md). Built once, with the
    # standard edition: it is the same for both.
    if [[ -z "$suffix" ]]; then
        cargo build --release -p javabyte-sandbox
        addon="JavaByte-$version-windows-x86_64-sandbox-addon"
        repo="$(cd "$gui/.." && pwd)"
        # Not $WIX: Windows runners set that to the WiX 3 folder, which Tauri uses.
        wix=(${WIX5_CLI:-wix})
        "${wix[@]}" build -arch x64 -d "Version=$version" \
            -d "Helper=$(cygpath -w "$gui/target/release/javabyte-sandbox.exe")" \
            -d "Readme=$(cygpath -w "$gui/packaging/windows/sandbox-addon-README.txt")" \
            -d "Licence=$(cygpath -w "$repo/LICENSE")" \
            -o "$(cygpath -w "$out/$addon.msi")" "$(cygpath -w "$gui/packaging/windows/sandbox-addon.wxs")"
        # For the portable edition: unpacked next to javabyte-gui.exe, it adds the sandbox folder.
        zipdir="$(mktemp -d)"
        mkdir -p "$zipdir/sandbox"
        cp target/release/javabyte-sandbox.exe "$zipdir/sandbox/"
        cp packaging/windows/sandbox-addon-README.txt "$zipdir/sandbox/README.txt"
        cp "$repo/LICENSE" "$zipdir/sandbox/LICENSE.txt"
        (cd "$zipdir" && powershell -NoProfile -Command "Compress-Archive -Path 'sandbox' -DestinationPath '$addon.zip'")
        mv "$zipdir/$addon.zip" "$out/"
    fi
fi
ls -la "$out"
