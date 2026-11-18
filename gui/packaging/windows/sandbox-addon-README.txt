JavaByte sandbox add-on
=======================

This add-on lets JavaByte run each analysis with no network on Windows, as it already does on
Linux. It is a second layer of protection: JavaByte imitates every network call a sample makes,
and with the add-on the analysis also has no network at all, in case of a flaw.

It offers two ways, chosen in JavaByte's Preferences:

- AppContainer (the default): Windows' own app sandbox. No network and no access to your files
  except the sample and the analysis's folder. Works on every edition of Windows 10 and 11, and
  starts at once.
- Windows Sandbox: a throwaway Windows, started fresh for each analysis, with networking off.
  The strongest choice, but it takes 20 to 40 seconds to start, and needs Windows Pro,
  Enterprise or Education with the "Windows Sandbox" feature turned on.

Installing: run the add-on's MSI after installing JavaByte. For the portable edition, unpack the
add-on's zip so that the "sandbox" folder sits next to javabyte-gui.exe.

Checking: run  javabyte-gui.exe --self-check  and look for "Sandbox add-on" and "Network
isolation for analyses: available."

JavaByte and this add-on are free software under the GNU General Public License, version 3 or
later. Source: https://github.com/JessNco/JavaByte-Nco-edition
