//! `javabyte-sandbox`: the Windows sandbox add-on (docs/gui/WINDOWS-SANDBOX.md).
//!
//! Starts one program, isolated from the network and from the person's files, either in an
//! AppContainer (every edition of Windows, starts in about a second) or in Windows Sandbox (a
//! throwaway Windows, where available). Standard input, output and the exit code pass through,
//! so the desktop app drives the helper exactly as it drives Java.
//!
//! Exit codes: the program's own; 2 for a mistake in the helper's arguments; 70 when the
//! isolation itself could not start, with the reason on standard error.

mod args;
// Used by the Windows-only modules; elsewhere only its tests run.
#[cfg_attr(not(windows), allow(dead_code))]
mod leftovers;
// Used by the Windows-only modules; elsewhere only its tests run.
#[cfg_attr(not(windows), allow(dead_code))]
mod plan;
#[cfg(windows)]
mod appcontainer;
#[cfg(windows)]
mod windows_sandbox;

/// Exit code when the isolation could not start. The app offers another way, with the reason.
pub const ISOLATION_FAILED: i32 = 70;

fn main() {
    let command = match args::parse(std::env::args_os().skip(1).collect()) {
        Ok(command) => command,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            std::process::exit(2);
        }
    };
    std::process::exit(run(command));
}

#[cfg(windows)]
fn run(command: args::Command) -> i32 {
    match command {
        args::Command::Check => {
            let appcontainer = appcontainer::check();
            let sandbox = windows_sandbox::check();
            let entry = |result: Result<(), String>| match result {
                Ok(()) => serde_json::json!({ "available": true }),
                Err(problem) => serde_json::json!({ "available": false, "problem": problem }),
            };
            println!("{}", serde_json::json!({ "appcontainer": entry(appcontainer), "windowsSandbox": entry(sandbox) }));
            0
        }
        args::Command::AppContainer { read, write, program } => appcontainer::run(&read, &write, &program),
        args::Command::WindowsSandbox { maps, stage, program } => windows_sandbox::run(&maps, &stage, &program),
    }
}

#[cfg(not(windows))]
fn run(_command: args::Command) -> i32 {
    eprintln!("javabyte-sandbox: this add-on is for Windows; on Linux JavaByte isolates analyses itself");
    ISOLATION_FAILED
}
