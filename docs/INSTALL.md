# Installing the JavaByte desktop app

The desktop app works on Windows 10 and 11, and on Ubuntu 22.04 and later (and other Linux
systems with the AppImage). Everything it needs, including Java, comes with it.

Downloads are on the project's **Releases** page on GitHub. Each release has two editions:

| Edition | Size | For |
| --- | --- | --- |
| Standard | about 60–80 MB | Most samples |
| With native bridge (`-with-native-bridge` in the name) | about 250–330 MB | Samples that carry Windows native libraries (DLLs) and you want those analysed too ([what the bridge does](NATIVE.md)) |

> **Analysing live malware?** Use a virtual machine without network access, whatever your
> system. JavaByte never runs the sample, but the sample file itself is still dangerous.

## Check the download first

Every release lists the SHA-256 checksum of each file in `SHA256SUMS`. Compare it with the file
you downloaded.

On Windows, in PowerShell:

```powershell
Get-FileHash .\JavaByte-0.2.0-windows-x86_64.msi -Algorithm SHA256
```

On Linux:

```bash
sha256sum -c SHA256SUMS --ignore-missing
```

When a release is signed, it also has `SHA256SUMS.asc` and the public key
`javabyte-signing-key.asc`. Check the signature (once you have imported the key, only the
second command is needed):

```bash
gpg --import javabyte-signing-key.asc
```

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
```

"Good signature" means the checksums are the ones the project published.

## Windows

1. Run `JavaByte-<version>-windows-x86_64.msi`.
2. **Windows SmartScreen will probably say "Windows protected your PC".** This appears because
   the installer is not yet signed with a code-signing certificate, not because anything was
   found in it. After checking the checksum as above, choose **More info**, check that the
   file name is the one you downloaded, then choose **Run anyway**.
3. The installer may also install Microsoft Edge WebView2, which the app uses to draw its
   window. Windows 10 and 11 normally have it already.

To use it without installing, unpack `JavaByte-<version>-windows-x86_64-portable.zip` and run
`javabyte-gui.exe` in it. SmartScreen may ask in the same way.

JavaByte appears under **Open with** for `.jar` files. It never becomes the default program for
them, so double-clicking a jar does what it did before.

**Network isolation:** Windows cannot turn off the network for one program without
administrator rights or an extra part. Install the **sandbox add-on**
(`JavaByte-<version>-windows-x86_64-sandbox-addon.msi`, from the same release) after JavaByte,
and each analysis runs with no network:

- in an **AppContainer** (the default), Windows' own app sandbox, on every edition of Windows 10
  and 11, starting at once;
- or, chosen in Preferences, in **Windows Sandbox**: a fresh, throwaway Windows for each
  analysis. The strongest choice, but it takes 20 to 40 seconds to start and needs Windows Pro,
  Enterprise or Education with the "Windows Sandbox" feature turned on.

For the portable edition, unpack the add-on's zip so that its `sandbox` folder sits next to
`javabyte-gui.exe`. Without the add-on, JavaByte relies on its own simulation of the network:
use a virtual machine without network for live malware.

To uninstall: Settings, Apps, JavaByte, Uninstall (and "JavaByte sandbox add-on", if
installed). Your analyses stay in Documents\JavaByte.

## Ubuntu and Debian

Install the `.deb`. It brings in the libraries it needs:

```bash
sudo apt install ./JavaByte-0.2.0-linux-x86_64.deb
```

Then start JavaByte from the applications menu, or run `javabyte-gui`.

**Network isolation:** each analysis runs with the network turned off, with the file system
read-only except for its output folder and a private temporary folder, and with the sockets of
desktop services (D-Bus, X11 and others) hidden from it, as a second layer of protection. Ubuntu
24.04 allows this only to programs that have permission, and the `.deb` adds that permission (a
small AppArmor profile). Preferences shows whether isolation is available.

To uninstall:

```bash
sudo apt remove java-byte
```

## Other Linux systems: the AppImage

```bash
chmod +x JavaByte-0.2.0-linux-x86_64.AppImage
```

```bash
./JavaByte-0.2.0-linux-x86_64.AppImage
```

The AppImage cannot add the permission Ubuntu 24.04 asks for, so there it cannot turn off the
network for analyses. It says so, and asks before each analysis, until you tell it not to.

## If something does not work

Run the app with `--self-check`. It prints what it found (Java, the engine, the native bridge,
network isolation, where analyses are saved) and whether the engine works:

```bash
javabyte-gui --self-check
```

On Windows:

```powershell
& "C:\Program Files\JavaByte\javabyte-gui.exe" --self-check | Out-Host
```

Include its output when you report a problem.
