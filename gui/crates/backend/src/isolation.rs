//! Runs with no network and a read-only file system, on Linux: each run starts in a new user,
//! network and mount namespace.
//!
//! JavaByte already simulates every network call and every file a sample touches. This is a
//! second, independent layer, as `scripts/run-isolated.sh` is on the command line. It does not
//! depend on the emulator being right: if a flaw ever let a sample act for real, the kernel
//! still refuses what follows.
//!
//! - **No network.** The new network namespace has no interface to send on (only a loopback,
//!   which is down), and abstract Unix sockets belong to the namespace too.
//! - **A read-only file system.** In the new mount namespace `/` and every mount below it are
//!   made read-only. The run can write in two places only: its output folder, and a private
//!   temporary folder ([`PRIVATE_TEMP`]) that exists only for this run and is gone when it
//!   ends. So a sample that got out of the emulator cannot write `~/.bashrc` or an autostart
//!   entry.
//! - **No host service sockets.** A network namespace does not hide Unix sockets that live in
//!   the file system, and some of the services behind them make connections for whoever asks
//!   (the session D-Bus, systemd-resolved, nscd, Avahi, CUPS, Docker, the X11 and Wayland
//!   displays, SSH and GPG agents). The folders such sockets live in ([`COVERED_FOLDERS`]) are
//!   each covered by an empty folder. Nothing in them is visible unless it is named: the
//!   sample, the settings file, the tool, the Java runtime, the bridge's Python and the output
//!   folder are put back at their own paths when they lie inside one, so a sample under `/tmp`
//!   still works. The environment variables that point to desktop services are removed.
//!
//!   The run also gets a session of its own, so it has no controlling terminal to type into.
//!
//! What this does not do: files outside the covered folders stay readable, and a read-only
//! mount does not stop a connection to a Unix socket, so a socket a program keeps somewhere
//! else (under the home folder, say) can still be reached. There is no process namespace:
//! the run can see the person's other processes and send them signals, though it cannot read
//! their memory, environment or files.
//!
//! If any of this cannot be set up, the run does not start. It never starts less isolated.
//!
//! The namespaces are made by this process between `fork` and `exec`, not by starting
//! `unshare`. On Ubuntu 24.04, unprivileged namespaces are allowed only to programs whose
//! AppArmor profile permits them; the `.deb` installs such a profile for the app, and it
//! applies because the process making the namespace is still the app when it does.
//!
//! The child maps its own user and group to themselves, so Java sees the same user, home
//! folder and file permissions as without isolation. It gives up every capability the new
//! namespace gave it before Java starts, so nothing in the run can undo the mounts.

use std::ffi::{CStr, CString};
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// The run's private temporary folder: a fresh, empty, writable folder in memory, mounted over
/// the shared `/tmp` and gone when the run ends. Java is told to use it (`java.io.tmpdir`, see
/// `Launcher::run_command`), and so is everything else, through `TMPDIR`.
pub const PRIVATE_TEMP: &str = "/tmp";

/// Folders where host services keep Unix sockets and other shared state, each covered by an
/// empty folder in an isolated run: read-only, except [`PRIVATE_TEMP`]. Folders that do not
/// exist are skipped, and so is one that is a link to another (`/var/run` usually is).
const COVERED_FOLDERS: &[&str] = &["/run", "/var/run", PRIVATE_TEMP, "/dev/shm"];

/// Environment variables that tell programs where the person's desktop services are. An
/// isolated run cannot reach those services anyway; without the variables it does not try.
/// The launcher already starts the tool with a short list of variables that has none of
/// these; they are removed here as well for any other command that is isolated (the check
/// that isolation works, the tests).
const DESKTOP_VARIABLES: &[&str] = &["DBUS_SESSION_BUS_ADDRESS", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "SSH_AUTH_SOCK", "GPG_AGENT_INFO"];

/// Room for `/proc/self/mountinfo` when mounts have to be made read-only one by one. A line is
/// about 150 bytes; a system with more mounts than fit is refused, not half done.
const MOUNT_TABLE_ROOM: usize = 1 << 20;

/// What an isolated run may still use of the file system.
#[derive(Clone, Debug, Default)]
pub struct Access {
    /// The one folder the run may write to: its output folder. Made if it does not exist.
    pub writable: Option<PathBuf>,
    /// Files and folders the run must be able to read wherever they are: the sample, the
    /// settings file, the tool's jar, the Java runtime, the bridge's Python. Only those inside
    /// a covered folder need anything done; the rest of the file system is readable anyway.
    /// Ones that do not exist are skipped (the tool reports a missing file itself).
    pub readable: Vec<PathBuf>,
}

/// How mounts are made read-only. The second exists as a choice only so tests can run it on a
/// kernel that has the first.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Remount {
    /// `mount_setattr` on the whole tree (Linux 5.12); one by one where that fails.
    Tree,
    /// One by one, over `/proc/self/mountinfo`.
    OneByOne,
}

/// A file or folder to put back at its own path after the folder around it was covered.
struct Kept {
    /// The real path, with no links in it.
    path: CString,
    /// Every folder between the covered folder and `path`, outermost first: made again, empty,
    /// so that `path` has somewhere to be.
    parents: Vec<CString>,
    is_folder: bool,
    /// Inside a covered folder, so the place it is mounted on has to be made first.
    covered: bool,
    writable: bool,
}

/// Makes `command` start in a new user, network and mount namespace, with the file system
/// read-only except for `access.writable` and a private temporary folder, and with host
/// service sockets hidden (see the module documentation). An error means the run must not
/// start: the folder to write to could not be made, or a path could not be read.
pub fn isolate(command: &mut Command, access: &Access) -> io::Result<()> {
    isolate_with(command, access, Remount::Tree)
}

fn isolate_with(command: &mut Command, access: &Access, remount: Remount) -> io::Result<()> {
    // Everything is prepared before `fork`: only system calls happen in the child.
    // SAFETY: getuid and getgid cannot fail and have no side effects.
    let (uid, gid) = unsafe { (libc::getuid(), libc::getgid()) };
    let uid_map = format!("{uid} {uid} 1\n").into_bytes();
    let gid_map = format!("{gid} {gid} 1\n").into_bytes();

    // The covered folders by their real paths, each once.
    let mut covered: Vec<(PathBuf, bool)> = Vec::new();
    for folder in COVERED_FOLDERS {
        match std::fs::canonicalize(folder) {
            Ok(real) if real.is_dir() => {
                if !covered.iter().any(|(known, _)| *known == real) {
                    covered.push((real, *folder == PRIVATE_TEMP));
                }
            }
            Ok(_) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    if !covered.iter().any(|(_, private_temp)| *private_temp) {
        return Err(io::Error::other(format!("{PRIVATE_TEMP} is not a folder"))); // not-ui-text: technical detail
    }

    let mut kept: Vec<(PathBuf, Kept)> = Vec::new();
    let mut keep = |path: &Path, writable: bool| -> io::Result<()> {
        let real = match std::fs::canonicalize(path) {
            Ok(real) => real,
            Err(e) if e.kind() == io::ErrorKind::NotFound && !writable => return Ok(()),
            Err(e) => return Err(e),
        };
        let inside = covered.iter().map(|(folder, _)| folder).find(|folder| real.starts_with(folder));
        if inside.is_some_and(|folder| *folder == real) {
            // The whole of /tmp or /run cannot be asked for: that would undo the cover.
            return Err(io::Error::other(format!("{} cannot be used with isolation on", real.display()))); // not-ui-text: technical detail
        }
        // Outside the covered folders a readable path needs nothing; the writable one is
        // still mounted onto itself, so it can be made writable on its own.
        let Some(folder) = inside.or(writable.then_some(&real)) else { return Ok(()) };
        let mut parents: Vec<CString> = real.ancestors().skip(1).take_while(|a| a.starts_with(folder) && *a != folder.as_path()).map(c_path).collect::<io::Result<_>>()?;
        parents.reverse();
        kept.push((real.clone(), Kept { path: c_path(&real)?, parents, is_folder: real.is_dir(), covered: inside.is_some(), writable }));
        Ok(())
    };
    for path in &access.readable {
        keep(path, false)?;
    }
    if let Some(output) = &access.writable {
        std::fs::create_dir_all(output)?;
        keep(output, true)?;
    }
    // Outermost first, so a path inside another kept path is mounted on top of it. A path to
    // read inside a folder that is already kept to read needs nothing more.
    kept.sort_by_key(|(path, _)| path.as_os_str().len());
    let mut outer: Vec<PathBuf> = Vec::new();
    kept.retain(|(path, k)| {
        let redundant = !k.writable && outer.iter().any(|o| path.starts_with(o));
        if !k.writable && !redundant {
            outer.push(path.clone());
        }
        !redundant
    });
    let kept: Vec<Kept> = kept.into_iter().map(|(_, k)| k).collect();
    let mut covers: Vec<(CString, bool)> = Vec::new();
    for (folder, private_temp) in &covered {
        covers.push((c_path(folder)?, *private_temp));
    }
    // The folder the run starts in. Inside a covered folder, the run must not stay in the
    // original (from there, ".." leads to everything that was covered): it starts in an empty
    // folder of the same name instead, where what was kept is at the same relative paths.
    let started_in = command.get_current_dir().map(Path::to_path_buf).map_or_else(std::env::current_dir, Ok).and_then(std::fs::canonicalize)?;
    let mut start: Vec<CString> = Vec::new();
    if let Some((folder, _)) = covered.iter().find(|(folder, _)| started_in.starts_with(folder)) {
        start = started_in.ancestors().take_while(|a| a.starts_with(folder) && *a != folder.as_path()).map(c_path).collect::<io::Result<_>>()?;
        start.reverse();
    }
    let started_in = c_path(&started_in)?;
    let mut sources: Vec<libc::c_int> = vec![-1; kept.len()];
    let mut mount_table = vec![0u8; MOUNT_TABLE_ROOM];

    for variable in DESKTOP_VARIABLES {
        command.env_remove(variable);
    }
    command.env("TMPDIR", PRIVATE_TEMP);
    // SAFETY: the closure runs in the child after fork. It makes only system calls (setsid, unshare,
    // open, read, write, close, mkdir, chdir, mount, mount_setattr, statvfs, prctl) on data
    // prepared above, and allocates nothing: every buffer it uses was made before fork.
    unsafe {
        command.pre_exec(move || {
            // A session of its own, so the run has no controlling terminal. With one (the app
            // started from a shell), a process can push text into the terminal's input, and
            // the shell would run it outside every namespace. The app talks to a run through
            // pipes, so nothing is lost.
            if libc::setsid() < 0 {
                return Err(io::Error::last_os_error());
            }
            if libc::unshare(libc::CLONE_NEWUSER | libc::CLONE_NEWNET | libc::CLONE_NEWNS) != 0 {
                return Err(io::Error::last_os_error());
            }
            // Writing gid_map is refused until setgroups is denied.
            write_file(c"/proc/self/setgroups", b"deny")?;
            write_file(c"/proc/self/uid_map", &uid_map)?;
            write_file(c"/proc/self/gid_map", &gid_map)?;
            // Mounts made from here on stay in this namespace and never reach the desktop.
            mount(None, c"/", None, libc::MS_REC | libc::MS_PRIVATE, None)?;
            // Hold on to what must stay visible before anything is covered. Closed by exec.
            for (kept, source) in kept.iter().zip(sources.iter_mut()) {
                *source = libc::open(kept.path.as_ptr(), libc::O_PATH | libc::O_CLOEXEC);
                if *source < 0 {
                    return Err(io::Error::last_os_error());
                }
            }
            // Everything read-only. Whatever is mounted after this starts as a copy of a
            // read-only mount, or is one of ours.
            if remount == Remount::OneByOne || set_read_only(c"/", true, true).is_err() {
                read_only_one_by_one(&mut mount_table)?;
            }
            let quiet = libc::MS_NOSUID | libc::MS_NODEV;
            for (folder, _) in &covers {
                mount(Some(c"tmpfs"), folder, Some(c"tmpfs"), quiet, Some(c"mode=1777"))?;
            }
            for (kept, source) in kept.iter().zip(sources.iter()) {
                for parent in &kept.parents {
                    if libc::mkdir(parent.as_ptr(), 0o755) != 0 && last_errno() != libc::EEXIST {
                        return Err(io::Error::last_os_error());
                    }
                }
                if kept.covered {
                    make_mount_point(&kept.path, kept.is_folder)?;
                }
                let mut name = *b"/proc/self/fd/0000000000\0";
                mount(Some(descriptor_path(&mut name, *source)), &kept.path, None, libc::MS_BIND | libc::MS_REC, None)?;
                if kept.writable {
                    set_read_only(&kept.path, false, false)?;
                }
            }
            for folder in &start {
                if libc::mkdir(folder.as_ptr(), 0o755) != 0 && last_errno() != libc::EEXIST {
                    return Err(io::Error::last_os_error());
                }
            }
            if libc::chdir(started_in.as_ptr()) != 0 {
                return Err(io::Error::last_os_error());
            }
            // The covers themselves are read-only too, now that what they hold is in place.
            for (folder, private_temp) in &covers {
                if !private_temp {
                    mount(None, folder, None, libc::MS_REMOUNT | libc::MS_BIND | libc::MS_RDONLY | quiet, None)?;
                }
            }
            give_up_capabilities()
        });
    }
    Ok(())
}

/// A path as a C string. Paths with a NUL byte do not exist.
fn c_path(path: impl AsRef<Path>) -> io::Result<CString> {
    CString::new(path.as_ref().as_os_str().as_bytes()).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))
}

fn last_errno() -> i32 {
    io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

/// `mount(2)`, as a result. Safe between fork and exec.
fn mount(source: Option<&CStr>, target: &CStr, fstype: Option<&CStr>, flags: libc::c_ulong, data: Option<&CStr>) -> io::Result<()> {
    let pointer = |s: Option<&CStr>| s.map_or(std::ptr::null(), CStr::as_ptr);
    // SAFETY: every pointer is a valid C string or null, which mount(2) accepts for these.
    let result = unsafe { libc::mount(pointer(source), target.as_ptr(), pointer(fstype), flags, pointer(data).cast()) };
    if result == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

/// `struct mount_attr` of `mount_setattr(2)`.
#[repr(C)]
struct MountAttr {
    attr_set: u64,
    attr_clr: u64,
    propagation: u64,
    userns_fd: u64,
}

const MOUNT_ATTR_RDONLY: u64 = 1;
const AT_RECURSIVE: libc::c_int = 0x8000;

/// Makes the mount at `path` read-only or writable, keeping its other settings, with every
/// mount below it if `recursive`. Uses `mount_setattr`; where the kernel has none (before
/// 5.12), or refuses, a single mount is done the older way and a recursive request fails, for
/// the caller to go one by one. Safe between fork and exec.
fn set_read_only(path: &CStr, read_only: bool, recursive: bool) -> io::Result<()> {
    let attr = MountAttr { attr_set: if read_only { MOUNT_ATTR_RDONLY } else { 0 }, attr_clr: if read_only { 0 } else { MOUNT_ATTR_RDONLY }, propagation: 0, userns_fd: 0 };
    // SAFETY: path is a valid C string and attr a valid struct of the size given.
    let result = unsafe { libc::syscall(libc::SYS_mount_setattr, libc::AT_FDCWD, path.as_ptr(), if recursive { AT_RECURSIVE } else { 0 }, &raw const attr, size_of::<MountAttr>()) };
    if result == 0 {
        return Ok(());
    }
    let error = io::Error::last_os_error();
    if recursive {
        return Err(error);
    }
    remount_one(path, read_only)
}

/// Makes one mount read-only or writable with `mount(2)`, which needs the mount's other
/// settings repeated: a namespace made by an ordinary user may not drop `nosuid` and the like
/// from a mount it was given. Safe between fork and exec.
fn remount_one(path: &CStr, read_only: bool) -> io::Result<()> {
    // statvfs is not on POSIX's list of async-signal-safe functions, but in glibc and musl it
    // is the statfs system call and a copy between two structs: no lock, no allocation.
    // SAFETY: an all-zero statvfs is a valid value to be overwritten.
    let mut status: libc::statvfs = unsafe { std::mem::zeroed() };
    // SAFETY: path is a valid C string and status a valid place for the answer.
    if unsafe { libc::statvfs(path.as_ptr(), &mut status) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // statvfs reports mount settings as ST_ flags; mount takes MS_ flags.
    const SETTINGS: &[(libc::c_ulong, libc::c_ulong)] = &[
        (libc::ST_NOSUID, libc::MS_NOSUID),
        (libc::ST_NODEV, libc::MS_NODEV),
        (libc::ST_NOEXEC, libc::MS_NOEXEC),
        (libc::ST_NOATIME, libc::MS_NOATIME),
        (libc::ST_NODIRATIME, libc::MS_NODIRATIME),
        (libc::ST_RELATIME, libc::MS_RELATIME),
        (0x2000, 0x100), // ST_NOSYMFOLLOW, MS_NOSYMFOLLOW
    ];
    let current = status.f_flag as libc::c_ulong;
    let mut flags = libc::MS_REMOUNT | libc::MS_BIND | if read_only { libc::MS_RDONLY } else { 0 };
    for (reported, wanted) in SETTINGS {
        if current & reported != 0 {
            flags |= wanted;
        }
    }
    mount(None, path, None, flags, None)
}

/// Makes every mount read-only, one at a time, from the list in `/proc/self/mountinfo`. For
/// kernels without `mount_setattr`. `table` is room for the list, made before fork. Safe
/// between fork and exec.
///
/// A mount point this user cannot reach (no such file, or no permission to go there) is
/// skipped: nothing in the run can reach it either, as the run has the same user and no
/// capabilities. Any other failure stops the run.
fn read_only_one_by_one(table: &mut [u8]) -> io::Result<()> {
    let mut length = 0;
    // SAFETY: plain system calls on a valid C string and a buffer of the length given.
    unsafe {
        let fd = libc::open(c"/proc/self/mountinfo".as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC);
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        loop {
            let read = libc::read(fd, table[length..].as_mut_ptr().cast(), table.len() - length);
            if read < 0 {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                libc::close(fd);
                return Err(error);
            }
            if read == 0 {
                break;
            }
            length += read as usize;
            if length == table.len() {
                // More mounts than there is room for: refuse rather than leave some writable.
                libc::close(fd);
                return Err(io::Error::from_raw_os_error(libc::ENOMEM));
            }
        }
        libc::close(fd);
    }
    let mut start = 0;
    while start < length {
        let end = table[start..length].iter().position(|b| *b == b'\n').map_or(length, |p| start + p);
        // "36 35 98:0 /root /mount/point options ...": the mount point is the fifth field,
        // with space, tab, newline and backslash written as \040, \011, \012 and \134.
        let mut field_start = start;
        for _ in 0..4 {
            field_start = table[field_start..end].iter().position(|b| *b == b' ').map_or(end, |p| field_start + p + 1);
        }
        let field_end = table[field_start..end].iter().position(|b| *b == b' ').map_or(end, |p| field_start + p);
        if field_start >= field_end || field_end >= table.len() {
            return Err(io::Error::from_raw_os_error(libc::EINVAL));
        }
        // Decoded where it stands (it only gets shorter), and ended with a NUL over the space
        // that followed it.
        let (mut from, mut to) = (field_start, field_start);
        while from < field_end {
            let octal = |b: u8| (b'0'..=b'7').contains(&b);
            if table[from] == b'\\' && from + 3 < field_end && octal(table[from + 1]) && octal(table[from + 2]) && octal(table[from + 3]) {
                table[to] = ((table[from + 1] - b'0') << 6) | ((table[from + 2] - b'0') << 3) | (table[from + 3] - b'0');
                from += 4;
            } else {
                table[to] = table[from];
                from += 1;
            }
            to += 1;
        }
        table[to] = 0;
        let Ok(mount_point) = CStr::from_bytes_until_nul(&table[field_start..=to]) else {
            return Err(io::Error::from_raw_os_error(libc::EINVAL));
        };
        match remount_one(mount_point, true) {
            Err(e) if matches!(e.raw_os_error(), Some(libc::ENOENT | libc::EACCES)) => {}
            other => other?,
        }
        start = end + 1;
    }
    Ok(())
}

/// Makes the empty folder or file that a kept path is mounted on. Safe between fork and exec.
fn make_mount_point(path: &CStr, is_folder: bool) -> io::Result<()> {
    // SAFETY: path is a valid C string.
    unsafe {
        if is_folder {
            if libc::mkdir(path.as_ptr(), 0o755) != 0 && last_errno() != libc::EEXIST {
                return Err(io::Error::last_os_error());
            }
        } else {
            let fd = libc::open(path.as_ptr(), libc::O_WRONLY | libc::O_CREAT | libc::O_CLOEXEC, 0o600);
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }
            libc::close(fd);
        }
    }
    Ok(())
}

/// Writes `/proc/self/fd/<descriptor>` into `name`, which must be that text with ten digits
/// and a NUL, and returns it: the way to name an open file to `mount(2)`. Allocates nothing.
fn descriptor_path(name: &mut [u8; 25], descriptor: libc::c_int) -> &CStr {
    let mut digits = [0u8; 10];
    let mut count = 0;
    let mut rest = descriptor.unsigned_abs();
    loop {
        digits[count] = b'0' + (rest % 10) as u8;
        count += 1;
        rest /= 10;
        if rest == 0 {
            break;
        }
    }
    let start = "/proc/self/fd/".len();
    for i in 0..count {
        name[start + i] = digits[count - 1 - i];
    }
    name[start + count] = 0;
    CStr::from_bytes_until_nul(name).expect("ended with a NUL above")
}

/// Gives up every capability the new user namespace gave this process, for good: none can come
/// back at `exec`, even when the user is root. Without them nothing in the run can unmount a
/// cover or make a mount writable again. (A namespace the run makes for itself does not help
/// it: there the mounts it was given are locked as they are.) Safe between fork and exec.
fn give_up_capabilities() -> io::Result<()> {
    // SAFETY: prctl with these options takes plain numbers and changes only this process.
    unsafe {
        let none: libc::c_ulong = 0;
        if libc::prctl(libc::PR_CAP_AMBIENT, libc::PR_CAP_AMBIENT_CLEAR_ALL as libc::c_ulong, none, none, none) != 0 && last_errno() != libc::EINVAL {
            return Err(io::Error::last_os_error());
        }
        // The bounding set limits what exec can give. Numbers past the kernel's last
        // capability are refused with EINVAL, which ends the list.
        for capability in 0..64 as libc::c_ulong {
            if libc::prctl(libc::PR_CAPBSET_DROP, capability, none, none, none) != 0 {
                if last_errno() == libc::EINVAL {
                    break;
                }
                return Err(io::Error::last_os_error());
            }
        }
    }
    Ok(())
}

/// Writes `data` to a file with plain system calls (safe between fork and exec).
fn write_file(path: &CStr, data: &[u8]) -> io::Result<()> {
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

/// A shell test: every covered folder that exists is empty, so the run cannot reach the host
/// services whose sockets are there; the private temporary folder can be written and nothing
/// else can. (`test -w` asks the kernel, which says no for a read-only mount; nothing is
/// written.)
fn file_system_is_closed() -> String {
    let folders: Vec<String> = COVERED_FOLDERS.iter().map(|f| format!("'{f}'")).collect();
    let covered_are_empty = format!("for d in {}; do [ ! -d \"$d\" ] || [ -z \"$(ls -A \"$d\")\" ] || exit 1; done", folders.join(" ")); // not-ui-text: a shell script
    let only_temp_is_writable = format!("[ -w '{PRIVATE_TEMP}' ] || exit 1; for d in / /var/tmp /run /dev/shm \"${{HOME:-/}}\"; do [ ! -w \"$d\" ] || exit 1; done"); // not-ui-text: a shell script
    format!("{covered_are_empty}; {only_temp_is_writable}")
}

/// Whether this system lets the app start an isolated process, by starting one. The error is
/// the system's reason, for the explanation shown to people.
pub fn check() -> Result<(), io::Error> {
    check_with(Remount::Tree)
}

fn check_with(remount: Remount) -> Result<(), io::Error> {
    let mut command = Command::new("/bin/sh");
    // Inside, the only interface must be the loopback, the covered folders must be empty and
    // nothing but the temporary folder writable: a check that isolation really happened.
    // (/proc/self/net follows the process's namespace; /sys/class/net would not.)
    command.args(["-c", &format!("{INTERFACES_ARE_ONLY_LOOPBACK} && {{ {}; }}", file_system_is_closed())]);
    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    isolate_with(&mut command, &Access::default(), remount)?;
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

    /// A folder for one test, under the given parent, removed when dropped.
    struct Scratch(PathBuf);

    impl Scratch {
        fn new(parent: &Path, name: &str) -> Self {
            let folder = parent.join(format!("javabyte-isolation-test-{}-{name}", std::process::id()));
            let _ = std::fs::remove_dir_all(&folder);
            std::fs::create_dir_all(&folder).unwrap();
            Scratch(folder)
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Whether isolation works here; says why not otherwise, and the test then passes, unless
    /// `JAVABYTE_ISOLATION_TESTS_REQUIRED` is set, as it is in CI: a system that stopped
    /// allowing namespaces must not turn every isolation test into a silent pass.
    fn available() -> bool {
        match check() {
            Ok(()) => true,
            Err(e) => {
                assert!(std::env::var_os("JAVABYTE_ISOLATION_TESTS_REQUIRED").is_none(), "isolation is required here but not available: {e}");
                eprintln!("isolation is not available here: {e}");
                false
            }
        }
    }

    /// Runs a shell script isolated, and returns what it printed.
    fn isolated_shell(script: &str, access: &Access, remount: Remount) -> String {
        let mut command = Command::new("/bin/sh");
        command.args(["-c", script]);
        isolate_with(&mut command, access, remount).unwrap();
        let output = command.output().unwrap();
        assert!(output.status.success(), "{output:?}");
        String::from_utf8_lossy(&output.stdout).into_owned()
    }

    fn home() -> PathBuf {
        PathBuf::from(std::env::var_os("HOME").expect("HOME is set"))
    }

    /// Where the system allows namespaces (most CI machines and Ubuntu 22.04), an isolated
    /// process sees no network but the same user. Elsewhere the check must say why.
    #[test]
    fn isolated_processes_have_no_network_and_the_same_user() {
        if !available() {
            return;
        }
        let text = isolated_shell("id -u; tail -n +3 /proc/self/net/dev | cut -d: -f1 | tr -d ' '", &Access::default(), Remount::Tree);
        let mut lines = text.lines();
        // SAFETY: getuid cannot fail.
        assert_eq!(lines.next(), Some(unsafe { libc::getuid() }.to_string().as_str()));
        assert_eq!(lines.collect::<Vec<_>>(), ["lo"]);
    }

    /// The point of the read-only file system: an isolated process cannot make a file in the
    /// home folder, in the shared /tmp, or beside its output folder, and can in its output
    /// folder and in its private temporary folder. Both ways of making mounts read-only.
    #[test]
    fn isolated_processes_write_only_to_the_output_and_the_private_temporary_folder() {
        if !available() {
            return;
        }
        for remount in [Remount::Tree, Remount::OneByOne] {
            let name = format!("{remount:?}");
            // Beside the test program: not in a covered folder (unless the build is under /tmp).
            let beside_tests = Scratch::new(std::env::current_exe().unwrap().parent().unwrap(), &name);
            let in_tmp = Scratch::new(Path::new("/tmp"), &name);
            let output = beside_tests.0.join("run");
            let home_mark = home().join(format!(".javabyte-isolation-test-{}-{name}", std::process::id()));
            let tmp_mark = PathBuf::from(format!("/tmp/javabyte-isolation-test-{}-{name}-mark", std::process::id()));
            let script = format!(
                "try() {{ if ( : > \"$1\" ) 2>/dev/null; then echo \"$2 written\"; else echo \"$2 refused\"; fi; }}\n\
                 try '{home}' home\n try '{beside}' beside-output\n try /var/tmp/javabyte-isolation-mark var-tmp\n\
                 try '{tmp}' private-temp\n try '{output}/report.json' output\n\
                 echo \"TMPDIR=$TMPDIR\"; [ -z \"$(ls -A /tmp | grep -v mark)\" ] && echo 'temp empty'\n\
                 [ -e '{in_tmp}' ] || echo 'shared tmp hidden'",
                home = home_mark.display(),
                beside = beside_tests.0.join("beside").display(),
                tmp = tmp_mark.display(),
                output = output.display(),
                in_tmp = in_tmp.0.display(),
            );
            let text = isolated_shell(&script, &Access { writable: Some(output.clone()), readable: vec![] }, remount);
            let wrote_home = home_mark.exists();
            let _ = std::fs::remove_file(&home_mark);
            let _ = std::fs::remove_file("/var/tmp/javabyte-isolation-mark");
            let lines: Vec<&str> = text.lines().collect();
            let expected = ["home refused", "beside-output refused", "var-tmp refused", "private-temp written", "output written", "TMPDIR=/tmp", "temp empty", "shared tmp hidden"];
            assert_eq!(lines, expected, "{remount:?}: {text}");
            assert!(!wrote_home, "{remount:?}: a file appeared in the home folder");
            assert!(!tmp_mark.exists(), "{remount:?}: the private temporary folder must not be the shared /tmp");
            assert!(output.join("report.json").is_file(), "{remount:?}: the output folder is the real one");
        }
    }

    /// A sample, a settings file and an output folder under /tmp are still there in an
    /// isolated run, at the same paths: the sample to read only, the output to write.
    #[test]
    fn a_sample_and_output_under_tmp_stay_usable() {
        if !available() {
            return;
        }
        for remount in [Remount::Tree, Remount::OneByOne] {
            let scratch = Scratch::new(Path::new("/tmp"), &format!("kept-{remount:?}"));
            let sample = scratch.0.join("samples/a sample.jar");
            let classes = scratch.0.join("classes");
            let output = scratch.0.join("classes/out");
            let secret = scratch.0.join("samples/other.txt");
            std::fs::create_dir_all(sample.parent().unwrap()).unwrap();
            std::fs::create_dir_all(&classes).unwrap();
            std::fs::write(&sample, "sample").unwrap();
            std::fs::write(classes.join("A.class"), "class").unwrap();
            std::fs::write(&secret, "not named").unwrap();
            let script = format!(
                "cat '{sample}'; echo; cat '{classes}/A.class'; echo\n\
                 ( : >> '{sample}' ) 2>/dev/null && echo 'sample written'\n\
                 ( : > '{classes}/B.class' ) 2>/dev/null && echo 'classes written'\n\
                 [ -e '{secret}' ] && echo 'neighbour visible'\n\
                 echo result > '{output}/report.json' && echo 'output written'",
                sample = sample.display(),
                classes = classes.display(),
                secret = secret.display(),
                output = output.display(),
            );
            let access = Access { writable: Some(output.clone()), readable: vec![sample.clone(), classes.clone(), scratch.0.join("missing.yaml")] };
            let text = isolated_shell(&script, &access, remount);
            assert_eq!(text.lines().collect::<Vec<_>>(), ["sample", "class", "output written"], "{remount:?}");
            assert_eq!(std::fs::read_to_string(output.join("report.json")).unwrap().trim(), "result");
            assert_eq!(std::fs::read_to_string(&sample).unwrap(), "sample");
        }
    }

    /// A run started in a folder under /tmp does not stay in the original: from there, ".."
    /// would lead to everything that was covered. It starts in an empty folder of the same
    /// name, where what was kept is at the same relative paths.
    #[test]
    fn a_run_started_under_tmp_cannot_walk_back_into_the_shared_tmp() {
        if !available() {
            return;
        }
        let scratch = Scratch::new(Path::new("/tmp"), "start");
        let work = scratch.0.join("work");
        std::fs::create_dir_all(work.join("samples")).unwrap();
        std::fs::write(work.join("samples/x.jar"), "sample").unwrap();
        std::fs::write(work.join("neighbour"), "").unwrap();
        std::fs::write(scratch.0.join("above"), "").unwrap();
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "pwd; cat samples/x.jar; echo; echo result > out/report.json; ls -A . .. ../.."]).current_dir(&work);
        let access = Access { writable: Some(work.join("out")), readable: vec![work.join("samples/x.jar")] };
        isolate(&mut command, &access).unwrap();
        let output = command.output().unwrap();
        assert!(output.status.success(), "{output:?}");
        let name = scratch.0.file_name().unwrap().to_str().unwrap();
        let expected = format!("{}\nsample\n.:\nout\nsamples\n\n..:\nwork\n\n../..:\n{name}\n", work.display());
        assert_eq!(String::from_utf8_lossy(&output.stdout), expected);
        assert_eq!(std::fs::read_to_string(work.join("out/report.json")).unwrap().trim(), "result");
    }

    /// A Unix socket in the file system, of the kind a desktop or agent service listens on,
    /// cannot be reached from an isolated process, though the network namespace alone would
    /// allow it. No list of services is involved: any socket under /tmp or /run is gone. The
    /// desktop's variables that name such services are gone too.
    #[test]
    fn isolated_processes_cannot_reach_host_service_sockets() {
        if !available() {
            return;
        }
        let scratch = Scratch::new(Path::new("/tmp"), "socket");
        let path = scratch.0.join("agent.sock");
        let listener = std::os::unix::net::UnixListener::bind(&path).unwrap();
        assert!(std::os::unix::net::UnixStream::connect(&path).is_ok(), "reachable without isolation");

        let mut command = Command::new("/bin/sh");
        command.args(["-c", &format!("test -e '{}' && echo visible; echo \"bus=$DBUS_SESSION_BUS_ADDRESS display=$DISPLAY agent=$SSH_AUTH_SOCK\"; ls -A /run; ls -A /dev/shm", path.display())]);
        command.env("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus").env("DISPLAY", ":0").env("SSH_AUTH_SOCK", &path);
        isolate(&mut command, &Access::default()).unwrap();
        let output = command.output().unwrap();
        let text = String::from_utf8_lossy(&output.stdout);
        drop(listener);
        assert!(output.status.success(), "{output:?}");
        assert_eq!(text.trim(), "bus= display= agent=", "the socket must be hidden and the variables gone");
    }

    /// Nothing in the run can undo the mounts: the process has no capabilities left, so
    /// unmounting the cover over /tmp or making the home folder writable again is refused.
    #[test]
    fn isolated_processes_cannot_undo_the_isolation() {
        if !available() {
            return;
        }
        let text = isolated_shell(
            "grep -E '^Cap(Prm|Eff|Bnd|Amb)' /proc/self/status | tr -d '\\t'\n\
             umount /tmp 2>/dev/null && echo 'unmounted'; mount -o remount,bind,rw / 2>/dev/null && echo 'writable again'; true",
            &Access::default(),
            Remount::Tree,
        );
        let zero = "0000000000000000";
        assert_eq!(text.lines().collect::<Vec<_>>(), [format!("CapPrm:{zero}"), format!("CapEff:{zero}"), format!("CapBnd:{zero}"), format!("CapAmb:{zero}")]);
    }

    /// A run that cannot be isolated does not start: an output folder that cannot be made, or
    /// asking for all of a covered folder, is an error before anything runs.
    #[test]
    fn what_cannot_be_set_up_stops_the_run() {
        let mut command = Command::new("/bin/true");
        let file = Scratch::new(&std::env::temp_dir(), "blocked");
        std::fs::write(file.0.join("file"), "").unwrap();
        let under_a_file = Access { writable: Some(file.0.join("file/out")), readable: vec![] };
        assert!(isolate(&mut command, &under_a_file).is_err());
        let all_of_tmp = Access { writable: None, readable: vec![PathBuf::from("/tmp")] };
        assert!(isolate(&mut Command::new("/bin/true"), &all_of_tmp).is_err());

        if !available() {
            return;
        }
        // A failure in the child (here: the output folder vanished after it was prepared) is a
        // failure to start, not a run with less.
        let output = file.0.join("out");
        let mut command = Command::new("/bin/true");
        isolate(&mut command, &Access { writable: Some(output.clone()), readable: vec![] }).unwrap();
        std::fs::remove_dir(&output).unwrap();
        assert!(command.status().is_err(), "the run must not start");
    }

    #[test]
    fn descriptors_are_named_without_allocating() {
        for (descriptor, expected) in [(0, "/proc/self/fd/0"), (7, "/proc/self/fd/7"), (1234, "/proc/self/fd/1234"), (i32::MAX, "/proc/self/fd/2147483647")] {
            let mut name = *b"/proc/self/fd/0000000000\0";
            assert_eq!(descriptor_path(&mut name, descriptor).to_str().unwrap(), expected);
        }
    }
}
