//! Running a program in Windows Sandbox: a throwaway Windows with networking off, on Windows
//! Pro, Enterprise and Education with the feature enabled. Each run gets a fresh sandbox.
//!
//! The helper prepares a working folder (mapped read-write inside) and a staging folder for the
//! sample (mapped read-only), writes the `.wsb` configuration and the scripts from
//! [`crate::plan`], and starts Windows Sandbox. Then it relays, in both directions, through
//! files in the working folder:
//!
//! - the program's output, copied to this helper's standard output as it grows;
//! - Stop: when this helper's input says `stop` or ends, it creates the stop file;
//! - a heartbeat, so the analysis is ended inside if this helper is ended outside;
//! - the program's exit code, whose arrival ends the relay.
//!
//! The sandbox shuts itself down when the program ends. Both folders are deleted afterwards.

use crate::args::Mapping;
use crate::plan::{self, ERRORS_FILE, EXIT_FILE, HEARTBEAT_FILE, STOP_FILE, STREAM_FILE};
use std::ffi::OsString;
use std::io::{BufRead, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// How long Windows Sandbox may take to start the program before the helper gives up. A cold
/// start takes 20 to 40 seconds on most computers.
const START_LIMIT: Duration = Duration::from_secs(180);

/// Windows Sandbox's launcher, present only where the feature is enabled.
fn launcher() -> PathBuf {
    let system = std::env::var_os("SystemRoot").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("C:\\Windows"));
    system.join("System32").join("WindowsSandbox.exe")
}

/// Whether Windows Sandbox can be used here.
pub fn check() -> Result<(), String> {
    if launcher().is_file() {
        Ok(())
    } else {
        Err("Windows Sandbox is not turned on. It needs Windows Pro, Enterprise or Education with virtualisation enabled; \
             turn it on in 'Turn Windows features on or off'."
            .into())
    }
}

/// `windows-sandbox`: runs the program in a fresh sandbox, and returns its exit code.
pub fn run(maps: &[Mapping], stage: &[PathBuf], program: &[OsString]) -> i32 {
    match prepare_and_run(maps, stage, program) {
        Ok(code) => code,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            crate::ISOLATION_FAILED
        }
    }
}

fn prepare_and_run(maps: &[Mapping], stage: &[PathBuf], program: &[OsString]) -> Result<i32, String> {
    check()?;
    let root = std::env::temp_dir().join(format!("javabyte-sandbox-{}", std::process::id()));
    let io = root.join("io");
    let staged_dir = root.join("stage");
    let cleanup = Cleanup(root.clone());
    std::fs::create_dir_all(&io).and_then(|_| std::fs::create_dir_all(&staged_dir)).map_err(|e| format!("cannot prepare {}: {e}", root.display()))?;
    for file in stage {
        std::fs::copy(file, staged_dir.join(plan::staged_name(file))).map_err(|e| format!("cannot stage {}: {e}", file.display()))?;
    }

    // The command as it is seen inside, refusing what run.cmd could not pass through intact.
    let staged: Vec<&Path> = stage.iter().map(PathBuf::as_path).collect();
    let mut inside = Vec::new();
    for arg in program {
        let guest = plan::to_guest(&arg.to_string_lossy(), maps, &staged);
        if plan::unsafe_for_cmd(&guest) {
            return Err(format!("an argument contains % or \", which cannot be passed into Windows Sandbox safely: {guest}"));
        }
        inside.push(guest);
    }
    let bridge = std::env::var("JAVABYTE_BRIDGE_PYTHON").ok().map(|p| plan::to_guest(&p, maps, &staged));
    let write = |name: &str, text: String| std::fs::write(io.join(name), text).map_err(|e| format!("cannot write {name}: {e}"));
    write("run.cmd", plan::run_cmd(&inside, bridge.as_deref()))?;
    write("relay.ps1", plan::relay_ps1())?;
    write(HEARTBEAT_FILE, String::new())?;
    let wsb = root.join("analysis.wsb");
    std::fs::write(&wsb, plan::wsb(maps, &io, &staged_dir)).map_err(|e| format!("cannot write the sandbox configuration: {e}"))?;

    let opened = std::process::Command::new(launcher()).arg(&wsb).spawn().map_err(|e| format!("Windows Sandbox did not start: {e}"))?;
    drop(opened); // the launcher hands over to the sandbox service and may exit at once

    // Stop requests: a line `stop`, or the end of input, from the app.
    {
        let io = io.clone();
        std::thread::spawn(move || {
            let stdin = std::io::stdin();
            for line in stdin.lock().lines() {
                match line {
                    Ok(line) if line.trim().eq_ignore_ascii_case("stop") => break,
                    Ok(_) => continue,
                    Err(_) => break,
                }
            }
            let _ = std::fs::write(io.join(STOP_FILE), b"stop");
        });
    }

    // Wait for the program to start (its output file appears), then copy its output until its
    // exit code arrives.
    let started = Instant::now();
    let stream = io.join(STREAM_FILE);
    while !stream.exists() {
        if started.elapsed() > START_LIMIT {
            return Err("Windows Sandbox did not start the analysis within 3 minutes. Another sandbox may be open (only one can run at a time), \
                        or virtualisation may be turned off."
                .into());
        }
        if io.join(EXIT_FILE).exists() {
            break;
        }
        heartbeat(&io);
        std::thread::sleep(Duration::from_millis(500));
    }
    let mut copied = 0u64;
    let mut out = std::io::stdout();
    loop {
        heartbeat(&io);
        copied += copy_new(&stream, copied, &mut out);
        if io.join(EXIT_FILE).exists() {
            // The last lines, written before the exit code.
            copy_new(&stream, copied, &mut out);
            break;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    if let Ok(errors) = std::fs::read(io.join(ERRORS_FILE)) {
        let _ = std::io::stderr().write_all(&errors);
    }
    let code = std::fs::read_to_string(io.join(EXIT_FILE)).ok().and_then(|t| t.trim().parse().ok()).unwrap_or(1);
    drop(cleanup);
    Ok(code)
}

/// Touches the heartbeat file, so the relay inside knows this helper is still listening.
fn heartbeat(io: &Path) {
    let _ = std::fs::write(io.join(HEARTBEAT_FILE), b"");
}

/// Copies what was added to `file` since `from` to `out`; returns how many bytes.
fn copy_new(file: &Path, from: u64, out: &mut impl Write) -> u64 {
    let Ok(mut f) = std::fs::File::open(file) else { return 0 };
    if f.seek(SeekFrom::Start(from)).is_err() {
        return 0;
    }
    let mut added = Vec::new();
    let _ = f.read_to_end(&mut added);
    let _ = out.write_all(&added);
    let _ = out.flush();
    added.len() as u64
}

/// Deletes the run's working and staging folders when dropped, including the staged sample.
struct Cleanup(PathBuf);

impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
