//! What the helper writes and runs, worked out without touching the system, so it can be tested
//! everywhere: Windows command-line quoting, rewriting host paths to sandbox paths, and the
//! files that run an analysis inside Windows Sandbox (the `.wsb` configuration, `run.cmd` and the
//! stop relay).

use crate::args::Mapping;
use std::ffi::OsString;
use std::path::Path;

/// Inside the sandbox: where the helper's working folder appears (scripts, output, signals).
pub const IO_GUEST: &str = "C:\\JavaByteIO";
/// Inside the sandbox: where staged files (the sample) appear, read-only.
pub const STAGE_GUEST: &str = "C:\\JavaByteStage";
/// The file the helper creates to ask the analysis to stop.
pub const STOP_FILE: &str = "stop";
/// The file the helper touches every second while it is alive. If it goes stale, the helper
/// was ended and nobody will read the results, so the analysis is ended inside the sandbox too.
pub const HEARTBEAT_FILE: &str = "heartbeat";
/// The program's standard output, which the helper copies to its own as it grows.
pub const STREAM_FILE: &str = "stream.txt";
/// The program's standard error.
pub const ERRORS_FILE: &str = "errors.txt";
/// The program's exit code, written last: its appearance means the program has ended.
pub const EXIT_FILE: &str = "exit-code.txt";

/// One argument quoted for a Windows command line, by the rules programs built with the
/// Microsoft C runtime (Java's launcher among them) use to split it again.
pub fn quote(arg: &str) -> String {
    if !arg.is_empty() && !arg.contains([' ', '\t', '\n', '\u{b}', '"']) {
        return arg.to_string();
    }
    let mut out = String::from("\"");
    let mut backslashes = 0;
    for c in arg.chars() {
        match c {
            '\\' => backslashes += 1,
            '"' => {
                // Backslashes before a quote are doubled, and the quote is escaped.
                out.extend(std::iter::repeat_n('\\', backslashes * 2 + 1));
                out.push('"');
                backslashes = 0;
            }
            _ => {
                out.extend(std::iter::repeat_n('\\', backslashes));
                out.push(c);
                backslashes = 0;
            }
        }
    }
    // Backslashes before the closing quote are doubled too.
    out.extend(std::iter::repeat_n('\\', backslashes * 2));
    out.push('"');
    out
}

/// A whole command line from a program and its arguments.
pub fn command_line(program: &[OsString]) -> String {
    program.iter().map(|a| quote(&a.to_string_lossy())).collect::<Vec<_>>().join(" ")
}

/// Rewrites every host path in `text` that falls under a mapping (or is a staged file) to where
/// it appears in the sandbox. Windows paths compare without regard to case.
pub fn to_guest(text: &str, maps: &[Mapping], staged: &[&Path]) -> String {
    let mut pairs: Vec<(String, String)> = maps.iter().map(|m| (m.host.to_string_lossy().trim_end_matches('\\').to_string(), m.guest.clone())).collect();
    for file in staged {
        // Split by hand: these are Windows paths, and the tests also run where `\\` is not a separator.
        let name = staged_name(file);
        pairs.push((file.to_string_lossy().into_owned(), format!("{STAGE_GUEST}\\{name}")));
    }
    // Longest first, so a mapping inside another mapping wins.
    pairs.sort_by_key(|(host, _)| std::cmp::Reverse(host.len()));
    let mut out = text.to_string();
    for (host, guest) in pairs {
        if host.is_empty() {
            continue;
        }
        let lower = out.to_lowercase();
        let wanted = host.to_lowercase();
        let mut result = String::new();
        let mut last = 0;
        for (at, _) in lower.match_indices(&wanted) {
            // Only whole path segments: what follows must end the path or start a new segment.
            let next = out[at + host.len()..].chars().next();
            if matches!(next, None | Some('\\') | Some('/') | Some('"') | Some(' ') | Some(';')) {
                result.push_str(&out[last..at]);
                result.push_str(&guest);
                last = at + host.len();
            }
        }
        result.push_str(&out[last..]);
        out = result;
    }
    out
}

/// The file name a staged file has in the sandbox: its own name.
pub fn staged_name(file: &Path) -> String {
    let text = file.to_string_lossy();
    text.rsplit(['\\', '/']).next().unwrap_or_default().to_string()
}

/// Characters `run.cmd` cannot pass through safely: `%` is expanded by cmd even inside quotes,
/// and a `"` inside an argument would end the quoting that protects `&`, `|`, `<` and `>`.
pub fn unsafe_for_cmd(arg: &str) -> bool {
    arg.contains(['%', '"', '\n', '\r'])
}

/// The Windows Sandbox configuration: no network, no clipboard, no printers, no microphone or
/// camera, the folders mapped, and `run.cmd` started at logon.
pub fn wsb(maps: &[Mapping], io_host: &Path, stage_host: &Path) -> String {
    let folder = |host: &str, guest: &str, read_only: bool| {
        format!(
            "    <MappedFolder><HostFolder>{}</HostFolder><SandboxFolder>{}</SandboxFolder><ReadOnly>{}</ReadOnly></MappedFolder>\n",
            xml(host),
            xml(guest),
            read_only
        )
    };
    let mut folders = String::new();
    for m in maps {
        folders.push_str(&folder(&m.host.to_string_lossy(), &m.guest, !m.writable));
    }
    folders.push_str(&folder(&io_host.to_string_lossy(), IO_GUEST, false));
    folders.push_str(&folder(&stage_host.to_string_lossy(), STAGE_GUEST, true));
    format!(
        "<Configuration>\n  <Networking>Disable</Networking>\n  <VGpu>Disable</VGpu>\n  <AudioInput>Disable</AudioInput>\n  \
<VideoInput>Disable</VideoInput>\n  <ClipboardRedirection>Disable</ClipboardRedirection>\n  \
<PrinterRedirection>Disable</PrinterRedirection>\n  <ProtectedClient>Enable</ProtectedClient>\n  <MappedFolders>\n{folders}  </MappedFolders>\n  \
<LogonCommand><Command>{IO_GUEST}\\run.cmd</Command></LogonCommand>\n</Configuration>\n"
    )
}

/// `run.cmd`, run at logon inside the sandbox: the program with its input from the stop relay
/// and its output into files, then its exit code, then shut the sandbox down.
pub fn run_cmd(program_in_guest: &[String], bridge_python: Option<&str>) -> String {
    let quoted = program_in_guest.iter().map(|a| format!("\"{a}\"")).collect::<Vec<_>>().join(" ");
    let bridge = bridge_python.map(|p| format!("set \"JAVABYTE_BRIDGE_PYTHON={p}\"\r\n")).unwrap_or_default();
    format!(
        "@echo off\r\n\
cd /d {IO_GUEST}\r\n\
{bridge}\
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File {IO_GUEST}\\relay.ps1 | {quoted} > {IO_GUEST}\\{STREAM_FILE} 2> {IO_GUEST}\\{ERRORS_FILE}\r\n\
echo %ERRORLEVEL%> {IO_GUEST}\\exit-code.tmp\r\n\
move /y {IO_GUEST}\\exit-code.tmp {IO_GUEST}\\{EXIT_FILE} > nul\r\n\
shutdown /s /t 0\r\n"
    )
}

/// The stop relay: waits for the program to start, then writes `stop` to its input when the
/// helper asks (the stop file), or ends it if the helper is gone (a stale heartbeat). Exits
/// quietly when the program ends by itself, which closes the program's input only after the
/// program has already finished.
pub fn relay_ps1() -> String {
    format!(
        "$io = '{IO_GUEST}'\r\n\
$deadline = (Get-Date).AddMinutes(3)\r\n\
while (-not (Get-Process -Name java -ErrorAction SilentlyContinue)) {{\r\n\
  if ((Get-Date) -gt $deadline) {{ exit 1 }}\r\n\
  Start-Sleep -Milliseconds 200\r\n\
}}\r\n\
while ($true) {{\r\n\
  if (-not (Get-Process -Name java -ErrorAction SilentlyContinue)) {{ exit 0 }}\r\n\
  if (Test-Path \"$io\\{STOP_FILE}\") {{ 'stop'; exit 0 }}\r\n\
  $beat = Get-Item \"$io\\{HEARTBEAT_FILE}\" -ErrorAction SilentlyContinue\r\n\
  if ($beat -and ((Get-Date) - $beat.LastWriteTime).TotalSeconds -gt 20) {{\r\n\
    Stop-Process -Name java -Force -ErrorAction SilentlyContinue\r\n\
    exit 0\r\n\
  }}\r\n\
  Start-Sleep -Milliseconds 300\r\n\
}}\r\n"
    )
}

/// Text made safe inside an XML element.
fn xml(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn quoting_follows_the_c_runtime_rules() {
        assert_eq!(quote("plain"), "plain");
        assert_eq!(quote(""), "\"\"");
        assert_eq!(quote("C:\\Program Files\\x"), "\"C:\\Program Files\\x\"");
        assert_eq!(quote("a\"b"), "\"a\\\"b\"");
        assert_eq!(quote("ends in space\\"), "\"ends in space\\\\\"");
        assert_eq!(quote("C:\\dir\\"), "C:\\dir\\");
    }

    #[test]
    fn host_paths_become_sandbox_paths() {
        let maps = vec![
            Mapping { host: PathBuf::from("C:\\Program Files\\JavaByte"), guest: "C:\\JavaByte".into(), writable: false },
            Mapping { host: PathBuf::from("C:\\Users\\a\\Documents\\JavaByte\\run 1"), guest: "C:\\Run".into(), writable: true },
        ];
        let sample = PathBuf::from("D:\\samples\\x\\x.jar");
        let staged = [sample.as_path()];
        assert_eq!(to_guest("c:\\program files\\javabyte\\runtime\\bin\\java.exe", &maps, &staged), "C:\\JavaByte\\runtime\\bin\\java.exe");
        assert_eq!(to_guest("sample=D:\\samples\\x\\x.jar", &maps, &staged), format!("sample={STAGE_GUEST}\\x.jar"));
        assert_eq!(to_guest("C:\\Users\\a\\Documents\\JavaByte\\run 1\\config.yaml", &maps, &staged), "C:\\Run\\config.yaml");
        // A different folder that merely starts the same is left alone.
        assert_eq!(to_guest("C:\\Program Files\\JavaByteOther\\x", &maps, &staged), "C:\\Program Files\\JavaByteOther\\x");
    }

    #[test]
    fn the_sandbox_has_no_network_and_maps_what_it_was_given() {
        let maps = vec![Mapping { host: PathBuf::from("C:\\A&B"), guest: "C:\\JavaByte".into(), writable: false }];
        let text = wsb(&maps, Path::new("C:\\Temp\\io"), Path::new("C:\\Temp\\stage"));
        assert!(text.contains("<Networking>Disable</Networking>"));
        assert!(text.contains("<HostFolder>C:\\A&amp;B</HostFolder>"), "{text}");
        assert!(text.contains(&format!("<SandboxFolder>{IO_GUEST}</SandboxFolder><ReadOnly>false</ReadOnly>")));
        assert!(text.contains(&format!("<SandboxFolder>{STAGE_GUEST}</SandboxFolder><ReadOnly>true</ReadOnly>")));
        assert!(text.contains("run.cmd</Command>"));
    }

    #[test]
    fn the_script_runs_the_program_from_the_relay_and_records_its_end() {
        let script = run_cmd(&["C:\\JavaByte\\runtime\\bin\\java.exe".into(), "-jar".into()], Some("C:\\JavaByte\\bridge\\python\\python.exe"));
        assert!(script.contains("relay.ps1 | \"C:\\JavaByte\\runtime\\bin\\java.exe\" \"-jar\" > "));
        assert!(script.contains("set \"JAVABYTE_BRIDGE_PYTHON=C:\\JavaByte\\bridge\\python\\python.exe\""));
        assert!(script.find(EXIT_FILE).unwrap() < script.find("shutdown").unwrap(), "the exit code is written before shutting down");
        assert!(unsafe_for_cmd("100%") && unsafe_for_cmd("a\"b") && !unsafe_for_cmd("C:\\a b\\c & d"));
        assert!(relay_ps1().contains(STOP_FILE) && relay_ps1().contains(HEARTBEAT_FILE));
    }
}
