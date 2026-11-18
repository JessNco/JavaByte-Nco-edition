//! What the helper writes and runs, worked out without touching the system, so it can be tested
//! everywhere: Windows command-line quoting, rewriting host paths to sandbox paths, and the
//! files that run an analysis inside Windows Sandbox (the `.wsb` configuration, `run.cmd` and the
//! stop relay), and how long the helper waits after Stop.

use crate::args::Mapping;
use std::ffi::OsString;
use std::path::Path;
use std::time::{Duration, Instant};

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
/// How long after Stop the Windows Sandbox helper waits for the program's exit code before it
/// gives up. The program itself ends within a second or two of Stop. This is shorter than the
/// 15 seconds after which the app ends the helper from outside, so the helper can still delete
/// its folders itself.
pub const STOP_GRACE: Duration = Duration::from_secs(10);

/// One argument quoted for a Windows command line, by the rules programs built with the
/// Microsoft C runtime (Java's launcher among them) use to split it again. Quotes are added only
/// when the argument needs them.
pub fn quote(arg: &str) -> String {
    if !arg.is_empty() && !arg.contains([' ', '\t', '\n', '\u{b}', '"']) {
        return arg.to_string();
    }
    quote_always(arg)
}

/// One argument always wrapped in quotes, by the same C runtime rules as [`quote`]. `run.cmd`
/// needs the quotes on every argument, because they are what stop cmd reading `&`, `|`, `<` and
/// `>`; the escaping keeps an argument that ends in a backslash (such as `C:\Run\`) from
/// swallowing its closing quote.
fn quote_always(arg: &str) -> String {
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
/// it appears in the sandbox.
///
/// ASCII letters compare without regard to case, as Windows paths do. Other letters must match
/// exactly: folding them (Turkish `İ` becomes two characters, for example) would change the
/// text's length, and the positions found in the folded copy would no longer fit the original.
pub fn to_guest(text: &str, maps: &[Mapping], staged: &[&Path]) -> String {
    let mut pairs: Vec<(String, String)> = maps.iter().map(|m| (m.host.to_string_lossy().trim_end_matches('\\').to_string(), m.guest.clone())).collect();
    for (file, name) in staged.iter().zip(staged_names(staged)) {
        pairs.push((file.to_string_lossy().into_owned(), format!("{STAGE_GUEST}\\{name}")));
    }
    // Longest first, so a mapping inside another mapping wins.
    pairs.sort_by_key(|(host, _)| std::cmp::Reverse(host.len()));
    let mut out = text.to_string();
    for (host, guest) in pairs {
        if host.is_empty() {
            continue;
        }
        // ASCII folding keeps every character's length, so a position in `lower` is the same
        // position in `out`, and `host.len()` is the length of the match in both.
        let lower = out.to_ascii_lowercase();
        let wanted = host.to_ascii_lowercase();
        let mut result = String::new();
        let mut last = 0;
        for (at, _) in lower.match_indices(&wanted) {
            // Only whole path segments: what follows must end the path, start a new segment, or
            // close the quotes around it (the app passes `sample='<path>'`, quoted as YAML).
            let next = out[at + wanted.len()..].chars().next();
            if matches!(next, None | Some('\\') | Some('/') | Some('"') | Some('\'') | Some(' ') | Some(';')) {
                result.push_str(&out[last..at]);
                result.push_str(&guest);
                last = at + wanted.len();
            }
        }
        result.push_str(&out[last..]);
        out = result;
    }
    out
}

/// The file names staged files have in the sandbox, in the same order.
///
/// The name is the file's own, with every character outside a small safe set (ASCII letters
/// and digits, space, `.`, `-`, `_`, `(`, `)`, `[`, `]`, `+`, `,`) changed to `_`. A sample's
/// name is chosen by its author, and the name reaches `run.cmd`: a `%` there would otherwise
/// make every Windows Sandbox run fail, which would let the author push the analyst to the
/// weaker AppContainer, and letters outside ASCII would be misread in cmd's code page. Most
/// names keep their exact spelling, so the analysis sees the name it would see elsewhere. Two
/// files that end up with the same name get `-2`, `-3` and so on before the extension.
pub fn staged_names(files: &[&Path]) -> Vec<String> {
    let mut names: Vec<String> = Vec::new();
    for file in files {
        // Split by hand: these are Windows paths, and the tests also run where `\` is not a separator.
        let text = file.to_string_lossy();
        let own = text.rsplit(['\\', '/']).next().unwrap_or_default();
        let safe: String = own.chars().map(|c| if c.is_ascii_alphanumeric() || " .-_()[]+,".contains(c) { c } else { '_' }).collect();
        // Windows ignores trailing dots and spaces, and a name of dots alone is no name.
        let safe = safe.trim_end_matches(['.', ' ']).trim_start_matches(' ');
        let safe = if safe.trim_matches('.').is_empty() { "sample".to_string() } else { safe.to_string() };
        let mut name = safe.clone();
        let mut n = 2;
        while names.iter().any(|taken| taken.eq_ignore_ascii_case(&name)) {
            name = match safe.rsplit_once('.') {
                Some((stem, ext)) if !stem.is_empty() => format!("{stem}-{n}.{ext}"),
                _ => format!("{safe}-{n}"),
            };
            n += 1;
        }
        names.push(name);
    }
    names
}

/// Characters `run.cmd` cannot pass through safely. A `"` inside an argument would end the
/// quoting that protects `&`, `|`, `<` and `>`, and a line break would end the command.
///
/// `%` is refused rather than written as `%%`: the program runs on the right of a pipe, and cmd
/// runs each side of a pipe in a new cmd that expands `%NAME%` a second time, where nothing can
/// escape it. No text the sample's author chooses reaches this check: staged files get safe
/// names ([`staged_names`]), and mapped folders are replaced by their sandbox paths.
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
/// and its output into files, then its exit code, then shut the sandbox down. Every argument is
/// quoted by [`quote_always`]; the caller has already refused what [`unsafe_for_cmd`] names, in
/// the arguments and in `bridge_python`.
pub fn run_cmd(program_in_guest: &[String], bridge_python: Option<&str>) -> String {
    let quoted = program_in_guest.iter().map(|a| quote_always(a)).collect::<Vec<_>>().join(" ");
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

/// When Stop was first seen, so the wait for the exit code after it can be bounded.
#[derive(Default)]
pub struct StopClock(Option<Instant>);

impl StopClock {
    /// Notes whether the stop file exists now, and says whether [`STOP_GRACE`] has passed since
    /// it first did.
    pub fn overdue(&mut self, stop_file_exists: bool, now: Instant) -> bool {
        if stop_file_exists && self.0.is_none() {
            self.0 = Some(now);
        }
        self.0.is_some_and(|asked| now.duration_since(asked) > STOP_GRACE)
    }
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
        // The app's override quotes the path as YAML does.
        assert_eq!(to_guest("sample='D:\\samples\\x\\x.jar'", &maps, &staged), format!("sample='{STAGE_GUEST}\\x.jar'"));
        assert_eq!(to_guest("C:\\Users\\a\\Documents\\JavaByte\\run 1\\config.yaml", &maps, &staged), "C:\\Run\\config.yaml");
        // A different folder that merely starts the same is left alone.
        assert_eq!(to_guest("C:\\Program Files\\JavaByteOther\\x", &maps, &staged), "C:\\Program Files\\JavaByteOther\\x");
    }

    #[test]
    fn letters_outside_ascii_neither_panic_nor_hide_a_mapping() {
        let drive = vec![Mapping { host: PathBuf::from("D:\\"), guest: "C:\\S".into(), writable: false }];
        // `İ` grows when lowercased; positions after it used to be wrong (a panic here).
        assert_eq!(to_guest("\u{130} D:\\\u{fc}x", &drive, &[]), "\u{130} C:\\S\\\u{fc}x");
        let run = vec![Mapping { host: PathBuf::from("C:\\Run1"), guest: "C:\\Run".into(), writable: true }];
        // Before, this mapping was silently missed.
        assert_eq!(to_guest("C:\\Users\\\u{130}lker\\a C:\\Run1\\x", &run, &[]), "C:\\Users\\\u{130}lker\\a C:\\Run\\x");
        // A host path with such a letter still maps, ASCII letters in any case.
        let home = vec![Mapping { host: PathBuf::from("C:\\Users\\\u{130}lker\\run"), guest: "C:\\Run".into(), writable: true }];
        assert_eq!(to_guest("c:\\users\\\u{130}lker\\RUN\\x", &home, &[]), "C:\\Run\\x");
    }

    #[test]
    fn staged_files_get_names_cmd_can_carry() {
        let names = |paths: &[&str]| staged_names(&paths.iter().map(Path::new).collect::<Vec<_>>());
        assert_eq!(names(&["D:\\in\\Invoice (2).jar"]), ["Invoice (2).jar"], "ordinary names keep their spelling");
        assert_eq!(names(&["C:\\in\\x%41.jar"]), ["x_41.jar"]);
        assert_eq!(names(&["C:\\in\\r\u{e9}sum\u{e9}&!.jar"]), ["r_sum___.jar"]);
        assert_eq!(names(&["C:\\in\\..."]), ["sample"]);
        assert_eq!(names(&["C:\\a\\x%.jar", "C:\\b\\x&.jar", "C:\\c\\X_.JAR"]), ["x_.jar", "x_-2.jar", "X_-3.JAR"]);

        // A `%` in the sample's name no longer reaches run.cmd, so the run is not refused.
        let sample = PathBuf::from("C:\\in\\x%41.jar");
        let staged = [sample.as_path()];
        let arg = to_guest("sample='C:\\in\\x%41.jar'", &[], &staged);
        assert!(!unsafe_for_cmd(&arg), "{arg}");
        let script = run_cmd(&["java.exe".into(), to_guest("C:\\in\\x%41.jar", &[], &staged), arg], None);
        assert_eq!(script.matches('%').count(), 2, "only %ERRORLEVEL% is left: {script}");
    }

    #[test]
    fn a_trailing_backslash_keeps_its_closing_quote() {
        let script = run_cmd(&["java.exe".into(), "C:\\Run\\".into(), "next".into()], None);
        assert!(script.contains("\"java.exe\" \"C:\\Run\\\\\" \"next\""), "{script}");
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

    #[test]
    fn stop_is_given_a_grace_period_and_no_more() {
        let start = Instant::now();
        let mut stop = StopClock::default();
        assert!(!stop.overdue(false, start + Duration::from_secs(600)), "no Stop, no limit");
        assert!(!stop.overdue(true, start + Duration::from_secs(600)), "Stop seen");
        assert!(!stop.overdue(true, start + Duration::from_secs(600) + STOP_GRACE));
        assert!(stop.overdue(true, start + Duration::from_secs(601) + STOP_GRACE));
    }
}
