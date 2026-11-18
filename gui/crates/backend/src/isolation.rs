//! Runs with no network, on Linux: each run starts in a new user and network namespace.
//!
//! JavaByte already simulates every network call a sample makes. This is a second, independent
//! layer, as `scripts/run-isolated.sh` is on the command line: if a flaw ever let a real
//! connection attempt through, the new namespace has no interface to send it on (only a
//! loopback, which is down).
//!
//! The namespace is made by this process between `fork` and `exec`, not by starting `unshare`.
//! On Ubuntu 24.04, unprivileged namespaces are allowed only to programs whose AppArmor profile
//! permits them; the `.deb` installs such a profile for the app, and it applies because the
//! process making the namespace is still the app when it does.
//!
//! The child maps its own user and group to themselves, so Java sees the same user, home
//! folder and file permissions as without isolation.

use std::io;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

/// Makes `command` start in a new user and network namespace.
pub fn isolate(command: &mut Command) {
    // Prepared before `fork`: only system calls happen in the child.
    // SAFETY: getuid and getgid cannot fail and have no side effects.
    let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
    let uid_map = format!("{uid} {uid} 1\n").into_bytes();
    let gid_map = format!("{gid} {gid} 1\n").into_bytes();
    // SAFETY: the closure runs in the child after fork. It calls only async-signal-safe system
    // calls (unshare, open, write, close) on data prepared above, and allocates nothing.
    unsafe {
        command.pre_exec(move || {
            if libc::unshare(libc::CLONE_NEWUSER | libc::CLONE_NEWNET) != 0 {
                return Err(io::Error::last_os_error());
            }
            // Writing gid_map is refused until setgroups is denied.
            write_file(c"/proc/self/setgroups", b"deny")?;
            write_file(c"/proc/self/uid_map", &uid_map)?;
            write_file(c"/proc/self/gid_map", &gid_map)?;
            Ok(())
        });
    }
}

/// Writes `data` to a file with plain system calls (safe between fork and exec).
fn write_file(path: &std::ffi::CStr, data: &[u8]) -> io::Result<()> {
    // SAFETY: path is a valid C string; data is a valid buffer of its length.
    unsafe {
        let fd = libc::open(path.as_ptr(), libc::O_WRONLY | libc::O_CLOEXEC);
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let written = libc::write(fd, data.as_ptr().cast(), data.len());
        let error = io::Error::last_os_error();
        libc::close(fd);
        if written != data.len() as isize {
            return Err(error);
        }
    }
    Ok(())
}

/// A shell test: the process's network namespace has the loopback and nothing else.
const INTERFACES_ARE_ONLY_LOOPBACK: &str = "test \"$(tail -n +3 /proc/self/net/dev | cut -d: -f1 | tr -d ' ')\" = lo";

/// Whether this system lets the app start an isolated process, by starting one. The error is
/// the system's reason, for the explanation shown to people.
pub fn check() -> Result<(), io::Error> {
    let mut command = Command::new("/bin/sh");
    // Inside, the only interface must be the loopback: a check that isolation really happened.
    // (/proc/self/net follows the process's namespace; /sys/class/net would not.)
    command.args(["-c", INTERFACES_ARE_ONLY_LOOPBACK]);
    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    isolate(&mut command);
    let status = command.status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!("the isolated check ended with {status}"))) // not-ui-text: technical detail
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Where the system allows namespaces (most CI machines and Ubuntu 22.04), an isolated
    /// process sees no network but the same user. Elsewhere the check must say why.
    #[test]
    fn isolated_processes_have_no_network_and_the_same_user() {
        match check() {
            Ok(()) => {
                let mut command = Command::new("/bin/sh");
                command.args(["-c", "id -u; tail -n +3 /proc/self/net/dev | cut -d: -f1 | tr -d ' '"]);
                isolate(&mut command);
                let output = command.output().unwrap();
                let text = String::from_utf8_lossy(&output.stdout);
                let mut lines = text.lines();
                // SAFETY: getuid cannot fail.
                assert_eq!(lines.next(), Some(unsafe { libc::getuid() }.to_string().as_str()));
                assert_eq!(lines.collect::<Vec<_>>(), ["lo"]);
            }
            Err(e) => eprintln!("isolation is not available here: {e}"),
        }
    }
}
