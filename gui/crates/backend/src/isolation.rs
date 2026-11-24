//! Runs with no network, on Linux: each run starts in a new user, network and mount namespace.
//!
//! JavaByte already simulates every network call a sample makes. This is a second, independent
//! layer, as `scripts/run-isolated.sh` is on the command line: if a flaw ever let a real
//! connection attempt through, the new network namespace has no interface to send it on (only
//! a loopback, which is down), and abstract Unix sockets belong to the namespace too.
//!
//! A network namespace does not hide Unix sockets that live in the file system, and some of
//! the services behind them make connections for whoever asks: the session D-Bus (whose
//! portal opens a browser at a web address), systemd-resolved (which glibc asks to look up
//! host names), nscd, Avahi, CUPS, Docker, and the X11 and Wayland displays. So the run also
//! gets a mount namespace of its own, in which an empty, read-only folder covers each of
//! these ([`HIDDEN_FOLDERS`], [`HIDDEN_SOCKETS`]), and the environment variables that point to
//! them are removed. The rest of the file system is the same as without isolation, so the
//! sample, the runtime and the output folder are where they always are.
//!
//! The namespaces are made by this process between `fork` and `exec`, not by starting
//! `unshare`. On Ubuntu 24.04, unprivileged namespaces are allowed only to programs whose
//! AppArmor profile permits them; the `.deb` installs such a profile for the app, and it
//! applies because the process making the namespace is still the app when it does.
//!
//! The child maps its own user and group to themselves, so Java sees the same user, home
//! folder and file permissions as without isolation.

use std::ffi::CString;
use std::io;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

/// Folders of host services' Unix sockets, covered by an empty read-only folder in an
/// isolated run. `{uid}` is the person's user id: their own session's sockets (D-Bus, Wayland,
/// PipeWire, keyrings, podman, gpg-agent) are under `/run/user/{uid}`, and a tmux server's,
/// which runs commands for whoever connects, under `/tmp/tmux-{uid}`. Folders that do not
/// exist are skipped. An ssh-agent's folder is added when the environment names one
/// ([`agent_folder`]). `scripts/run-isolated.sh` hides the same; keep the two lists the same.
const HIDDEN_FOLDERS: &[&str] = &[
    "/run/user/{uid}",
    "/run/dbus",
    "/run/systemd",
    "/run/nscd",
    "/var/run/nscd",
    "/run/avahi-daemon",
    "/run/cups",
    "/tmp/.X11-unix",
    "/tmp/tmux-{uid}",
];

/// Host service sockets that sit directly in a shared folder, covered by `/dev/null` in an
/// isolated run so connecting to them fails. Sockets that do not exist are skipped.
const HIDDEN_SOCKETS: &[&str] = &["/run/docker.sock", "/var/run/docker.sock", "/run/snapd.socket", "/run/snapd-snap.socket"];

/// Environment variables that tell programs where the person's desktop services are. An
/// isolated run cannot reach those services anyway; without the variables it does not try.
const DESKTOP_VARIABLES: &[&str] =
    &["DBUS_SESSION_BUS_ADDRESS", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "SSH_AUTH_SOCK", "GPG_AGENT_INFO", "TMUX"];

/// The folder an ssh-agent made for its socket, from the `SSH_AUTH_SOCK` it set: a folder of
/// its own under the temporary folder (`/tmp/ssh-XXXXXX/agent.123`). An agent signs for
/// whoever connects, so its socket is hidden like the desktop's. A socket anywhere else is
/// left alone, because covering its folder could hide the sample or the output.
fn agent_folder(socket: Option<&str>) -> Option<String> {
    let folder = std::path::Path::new(socket?).parent()?.to_str()?;
    let name = folder.strip_prefix("/tmp/")?;
    (name.starts_with("ssh-") && !name.contains('/')).then(|| folder.to_string())
}

/// Makes `command` start in a new user, network and mount namespace, with host service
/// sockets hidden (see the module documentation).
pub fn isolate(command: &mut Command) {
    // Prepared before `fork`: only system calls happen in the child.
    // SAFETY: getuid and getgid cannot fail and have no side effects.
    let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
    let uid_map = format!("{uid} {uid} 1\n").into_bytes();
    let gid_map = format!("{gid} {gid} 1\n").into_bytes();
    let c_path = |path: &str| CString::new(path.replace("{uid}", &uid.to_string())).expect("no NUL in a fixed path");
    let mut folders: Vec<CString> = HIDDEN_FOLDERS.iter().map(|p| c_path(p)).collect();
    if let Some(folder) = agent_folder(std::env::var("SSH_AUTH_SOCK").ok().as_deref()) {
        folders.extend(CString::new(folder));
    }
    let sockets: Vec<CString> = HIDDEN_SOCKETS.iter().map(|p| c_path(p)).collect();
    for variable in DESKTOP_VARIABLES {
        command.env_remove(variable);
    }
    // SAFETY: the closure runs in the child after fork. It calls only async-signal-safe system
    // calls (unshare, open, write, close, mount) on data prepared above, and allocates nothing.
    unsafe {
        command.pre_exec(move || {
            if libc::unshare(libc::CLONE_NEWUSER | libc::CLONE_NEWNET | libc::CLONE_NEWNS) != 0 {
                return Err(io::Error::last_os_error());
            }
            // Writing gid_map is refused until setgroups is denied.
            write_file(c"/proc/self/setgroups", b"deny")?;
            write_file(c"/proc/self/uid_map", &uid_map)?;
            write_file(c"/proc/self/gid_map", &gid_map)?;
            // Mounts made from here on stay in this namespace and never reach the desktop.
            mount(None, c"/", None, libc::MS_REC | libc::MS_PRIVATE)?;
            let read_only = libc::MS_RDONLY | libc::MS_NOSUID | libc::MS_NODEV | libc::MS_NOEXEC;
            for folder in &folders {
                skip_missing(mount(Some(c"tmpfs"), folder, Some(c"tmpfs"), read_only))?;
            }
            for socket in &sockets {
                skip_missing(mount(Some(c"/dev/null"), socket, None, libc::MS_BIND))?;
            }
            Ok(())
        });
    }
}

/// `mount(2)` with no extra data, as a result. Safe between fork and exec.
fn mount(source: Option<&std::ffi::CStr>, target: &std::ffi::CStr, fstype: Option<&std::ffi::CStr>, flags: libc::c_ulong) -> io::Result<()> {
    let pointer = |s: Option<&std::ffi::CStr>| s.map_or(std::ptr::null(), std::ffi::CStr::as_ptr);
    // SAFETY: every pointer is a valid C string or null, which mount(2) accepts for these.
    let result = unsafe { libc::mount(pointer(source), target.as_ptr(), pointer(fstype), flags, std::ptr::null()) };
    if result == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

/// Treats "no such file" as done: a service that is not installed has nothing to hide. Any
/// other failure stops the run from starting, rather than letting it start less isolated.
fn skip_missing(result: io::Result<()>) -> io::Result<()> {
    match result {
        Err(e) if e.raw_os_error() == Some(libc::ENOENT) => Ok(()),
        other => other,
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

/// A shell test: every folder of host service sockets that exists is empty, so the run cannot
/// reach those services.
fn hidden_folders_are_empty() -> String {
    // SAFETY: getuid cannot fail and has no side effects.
    let uid = unsafe { libc::getuid() }.to_string();
    let folders: Vec<String> = HIDDEN_FOLDERS.iter().map(|f| format!("'{}'", f.replace("{uid}", &uid))).collect();
    format!("for d in {}; do [ ! -d \"$d\" ] || [ -z \"$(ls -A \"$d\")\" ] || exit 1; done", folders.join(" ")) // not-ui-text: a shell script
}

/// Whether this system lets the app start an isolated process, by starting one. The error is
/// the system's reason, for the explanation shown to people.
pub fn check() -> Result<(), io::Error> {
    let mut command = Command::new("/bin/sh");
    // Inside, the only interface must be the loopback and the host services' socket folders
    // must be empty: a check that isolation really happened. (/proc/self/net follows the
    // process's namespace; /sys/class/net would not.)
    command.args(["-c", &format!("{INTERFACES_ARE_ONLY_LOOPBACK} && {}", hidden_folders_are_empty())]);
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

    /// Only an agent's own folder under the temporary folder is covered, never a folder that
    /// could hold anything else.
    #[test]
    fn only_an_agents_own_folder_is_hidden() {
        assert_eq!(agent_folder(Some("/tmp/ssh-XXXXabcd/agent.4242")), Some("/tmp/ssh-XXXXabcd".into()));
        assert_eq!(agent_folder(Some("/tmp/agent.sock")), None);
        assert_eq!(agent_folder(Some("/tmp/ssh-x/deeper/agent")), None);
        assert_eq!(agent_folder(Some("/home/me/.ssh/agent.sock")), None);
        assert_eq!(agent_folder(Some("/run/user/1000/keyring/ssh")), None);
        assert_eq!(agent_folder(None), None);
    }

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

    /// A Unix socket in the file system, of the kind a desktop service listens on, cannot be
    /// reached from an isolated process, though the network namespace alone would allow it.
    /// The desktop's variables that name such services are gone too.
    #[test]
    fn isolated_processes_cannot_reach_host_service_sockets() {
        if let Err(e) = check() {
            eprintln!("isolation is not available here: {e}");
            return;
        }
        // /tmp/.X11-unix is one of the hidden folders that anyone may create a socket in.
        let folder = std::path::Path::new("/tmp/.X11-unix");
        let created = !folder.exists();
        if created && std::fs::create_dir(folder).is_err() {
            eprintln!("skipped: {} could not be made", folder.display());
            return;
        }
        let path = folder.join(format!("javabyte-isolation-test-{}", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let listener = std::os::unix::net::UnixListener::bind(&path).unwrap();
        assert!(std::os::unix::net::UnixStream::connect(&path).is_ok(), "reachable without isolation");

        let mut command = Command::new("/bin/sh");
        command.args(["-c", &format!("test -S '{}' && echo visible; echo \"bus=$DBUS_SESSION_BUS_ADDRESS display=$DISPLAY\"", path.display())]);
        command.env("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus").env("DISPLAY", ":0");
        isolate(&mut command);
        let output = command.output().unwrap();
        let text = String::from_utf8_lossy(&output.stdout);
        drop(listener);
        let _ = std::fs::remove_file(&path);
        if created {
            let _ = std::fs::remove_dir(folder);
        }
        assert!(output.status.success(), "{output:?}");
        assert_eq!(text.trim(), "bus= display=", "the socket must be hidden and the variables gone");
    }
}
