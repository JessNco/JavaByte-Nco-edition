//! The helper's command line (docs/gui/WINDOWS-SANDBOX.md):
//!
//! ```text
//! javabyte-sandbox check
//! javabyte-sandbox appcontainer [--read PATH]... [--write PATH]... -- PROGRAM ARGS...
//! javabyte-sandbox windows-sandbox [--map HOST=GUEST[:rw]]... [--stage FILE]... -- PROGRAM ARGS...
//! ```

use std::ffi::OsString;
use std::path::PathBuf;

/// What the helper was asked to do.
#[derive(Debug, PartialEq)]
pub enum Command {
    /// Say which kinds of isolation work here, as JSON.
    Check,
    /// Run a program in an AppContainer with these extra file grants.
    AppContainer { read: Vec<PathBuf>, write: Vec<PathBuf>, program: Vec<OsString> },
    /// Run a program in Windows Sandbox with these folders mapped and files staged.
    WindowsSandbox { maps: Vec<Mapping>, stage: Vec<PathBuf>, program: Vec<OsString> },
}

/// A host folder shown inside Windows Sandbox.
#[derive(Clone, Debug, PartialEq)]
pub struct Mapping {
    /// The folder on this computer.
    pub host: PathBuf,
    /// Where it appears inside the sandbox, such as `C:\JavaByte`.
    pub guest: String,
    /// Whether the sandbox may write to it.
    pub writable: bool,
}

/// Reads the command line (without the program name).
pub fn parse(args: Vec<OsString>) -> Result<Command, String> {
    let mut args = args.into_iter();
    let verb = args.next().and_then(|a| a.into_string().ok()).ok_or("expected check, appcontainer or windows-sandbox")?;
    if verb == "check" {
        return Ok(Command::Check);
    }
    let (mut read, mut write, mut maps, mut stage) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
    let mut program = Vec::new();
    while let Some(arg) = args.next() {
        if arg == "--" {
            program.extend(args.by_ref());
            break;
        }
        let mut value = || args.next().ok_or_else(|| format!("{} needs a value", arg.to_string_lossy()));
        match arg.to_str() {
            Some("--read") if verb == "appcontainer" => read.push(PathBuf::from(value()?)),
            Some("--write") if verb == "appcontainer" => write.push(PathBuf::from(value()?)),
            Some("--map") if verb == "windows-sandbox" => maps.push(parse_mapping(&value()?.to_string_lossy())?),
            Some("--stage") if verb == "windows-sandbox" => stage.push(PathBuf::from(value()?)),
            _ => return Err(format!("unexpected argument {}", arg.to_string_lossy())),
        }
    }
    if program.is_empty() {
        return Err("no program given after --".into());
    }
    match verb.as_str() {
        "appcontainer" => Ok(Command::AppContainer { read, write, program }),
        "windows-sandbox" => Ok(Command::WindowsSandbox { maps, stage, program }),
        other => Err(format!("unknown command {other}")),
    }
}

/// `HOST=GUEST` or `HOST=GUEST:rw`. The last `=` splits, so a host path may contain one.
fn parse_mapping(text: &str) -> Result<Mapping, String> {
    let (host, guest) = text.rsplit_once('=').ok_or_else(|| format!("--map needs HOST=GUEST, found {text}"))?;
    let (guest, writable) = match guest.strip_suffix(":rw") {
        Some(guest) => (guest, true),
        None => (guest, false),
    };
    if !guest.starts_with("C:\\") {
        return Err(format!("the sandbox path must be under C:\\, found {guest}"));
    }
    Ok(Mapping { host: PathBuf::from(host), guest: guest.to_string(), writable })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn os(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    #[test]
    fn each_command_reads() {
        assert_eq!(parse(os(&["check"])).unwrap(), Command::Check);
        let Command::AppContainer { read, write, program } =
            parse(os(&["appcontainer", "--read", "D:\\s.jar", "--write", "D:\\run", "--", "java.exe", "-jar", "x.jar"])).unwrap()
        else {
            panic!()
        };
        assert_eq!((read, write), (vec![PathBuf::from("D:\\s.jar")], vec![PathBuf::from("D:\\run")]));
        assert_eq!(program, os(&["java.exe", "-jar", "x.jar"]));
        let Command::WindowsSandbox { maps, stage, .. } =
            parse(os(&["windows-sandbox", "--map", "C:\\Program Files\\JavaByte=C:\\JavaByte", "--map", "D:\\run=C:\\Run:rw", "--stage", "D:\\s.jar", "--", "java.exe"])).unwrap()
        else {
            panic!()
        };
        assert_eq!(maps[0], Mapping { host: "C:\\Program Files\\JavaByte".into(), guest: "C:\\JavaByte".into(), writable: false });
        assert!(maps[1].writable);
        assert_eq!(stage, vec![PathBuf::from("D:\\s.jar")]);
    }

    #[test]
    fn mistakes_are_refused() {
        assert!(parse(os(&["appcontainer", "--", ])).is_err(), "no program");
        assert!(parse(os(&["appcontainer", "--map", "a=C:\\b", "--", "x"])).is_err(), "--map is for windows-sandbox");
        assert!(parse(os(&["windows-sandbox", "--map", "a=D:\\b", "--", "x"])).is_err(), "guest must be under C:\\");
        assert!(parse(os(&["frobnicate", "--", "x"])).is_err());
    }
}
