//! Running a program in an AppContainer: Windows' own sandbox for apps, on every edition of
//! Windows 10 and 11, with no administrator rights.
//!
//! Each run gets a container of its own, `JavaByte.Analysis.<unique>`, with no capabilities: no
//! network (client or server), no access to the person's libraries or files. What the analysis
//! needs is granted for the run only, by adding the container's SID to each path's access list,
//! and taken away afterwards: read access to the sample, write access to the run's folder. The
//! write grant can create, change and delete files but not change who may use them, so nothing
//! inside can keep access for itself. The Java runtime under Program Files is readable by every
//! AppContainer already; outside Program Files (a portable copy, or CI) the app passes those
//! folders with `--read` too.
//!
//! When the run ends, its grants are removed, any access list under the run's folder that still
//! names the container is cleaned, and the container is deleted, with its own folder (where
//! Java's temporary files went). A leftover grant would then name a container that no longer
//! exists. Because the app may end this helper from outside, which runs none of that, the run
//! writes each step down first in a journal (see [`crate::leftovers`]), and every later helper
//! finishes what an ended one left.
//!
//! The program runs in a job object that ends the whole process tree when the helper ends (so
//! killing the helper cannot leave an analysis running) and allows only a few processes: Java,
//! and the native bridge's Python. If the job cannot be set up, the program is not started.

use crate::leftovers::{self, CONTAINER_PREFIX, JOURNAL_EXTENSION, LEGACY_CONTAINER};
use std::ffi::{OsStr, OsString, c_void};
use std::io::{Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use windows_sys::Win32::Foundation::{
    CloseHandle, ERROR_ALREADY_EXISTS, ERROR_SUCCESS, GENERIC_EXECUTE, GENERIC_READ, HANDLE, HANDLE_FLAG_INHERIT, LocalFree, SetHandleInformation,
};
use windows_sys::Win32::Security::Authorization::{
    ConvertSidToStringSidW, ConvertStringSidToSidW, EXPLICIT_ACCESS_W, GRANT_ACCESS, GetNamedSecurityInfoW, NO_MULTIPLE_TRUSTEE, REVOKE_ACCESS,
    SE_FILE_OBJECT, SetEntriesInAclW, SetNamedSecurityInfoW, TRUSTEE_IS_SID, TRUSTEE_IS_WELL_KNOWN_GROUP, TRUSTEE_W,
};
use windows_sys::Win32::Security::Isolation::{
    CreateAppContainerProfile, DeleteAppContainerProfile, DeriveAppContainerSidFromAppContainerName, GetAppContainerFolderPath,
};
use windows_sys::Win32::Security::{ACL, DACL_SECURITY_INFORMATION, FreeSid, NO_INHERITANCE, PSID, SECURITY_CAPABILITIES, SUB_CONTAINERS_AND_OBJECTS_INHERIT};
use windows_sys::Win32::Storage::FileSystem::{DELETE, FILE_DELETE_CHILD, FILE_GENERIC_EXECUTE, FILE_GENERIC_READ, FILE_GENERIC_WRITE};
use windows_sys::Win32::System::Console::{GetStdHandle, STD_ERROR_HANDLE, STD_INPUT_HANDLE, STD_OUTPUT_HANDLE};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_ACTIVE_PROCESS, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation, SetInformationJobObject,
};
use windows_sys::Win32::System::Threading::{
    CREATE_NO_WINDOW, CREATE_SUSPENDED, CreateProcessW, EXTENDED_STARTUPINFO_PRESENT, GetExitCodeProcess, INFINITE,
    InitializeProcThreadAttributeList, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, PROCESS_INFORMATION,
    ResumeThread, STARTF_USESTDHANDLES, STARTUPINFOEXW, TerminateProcess, UpdateProcThreadAttribute, WaitForSingleObject,
};

/// Java, the native bridge's Python, and a margin.
const MAX_PROCESSES: u32 = 4;
/// What a write grant allows: read, write, run, delete and delete inside. Not `GENERIC_ALL`,
/// which also gives WRITE_DAC and WRITE_OWNER: with those, a program inside could add access
/// for itself (or for every AppContainer) to files it creates, and keep it after the run.
const WRITE_ACCESS: u32 = FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE | FILE_DELETE_CHILD;
/// ALL APPLICATION PACKAGES and ALL RESTRICTED APPLICATION PACKAGES: groups every container
/// belongs to. Access lists under a run's folder that name them are cleaned with the
/// container's own, so access given to them from inside does not reach later runs.
const EVERY_CONTAINER: [&str; 2] = ["S-1-15-2-1", "S-1-15-2-2"];

/// A wide, NUL-terminated copy of a string, for Windows calls.
fn wide(text: impl AsRef<OsStr>) -> Vec<u16> {
    text.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}

/// Where the runs' journals are kept: the person's local app data, which no container may
/// write to.
fn journal_dir() -> PathBuf {
    match std::env::var_os("LOCALAPPDATA") {
        Some(base) => PathBuf::from(base).join("JavaByte").join("sandbox"),
        None => std::env::temp_dir().join("JavaByte-sandbox-journal"),
    }
}

/// A container's SID.
struct ContainerSid(PSID);

impl ContainerSid {
    /// Creates the container `name` and returns its SID. A container of that name left by an
    /// earlier helper is used as it is.
    fn create(name: &str) -> Result<Self, String> {
        let wide_name = wide(name);
        let mut sid: PSID = null_mut();
        // SAFETY: valid NUL-terminated strings; sid receives a SID freed in Drop.
        let created = unsafe { CreateAppContainerProfile(wide_name.as_ptr(), wide_name.as_ptr(), wide_name.as_ptr(), null(), 0, &mut sid) };
        if created >= 0 {
            return Ok(Self(sid));
        }
        // 0x800700B7: the container exists already.
        if created as u32 == 0x8007_0000 | ERROR_ALREADY_EXISTS {
            return Self::derive(name);
        }
        Err(format!("Windows refused to create the AppContainer (0x{created:08X})"))
    }

    /// The SID a container of this name has, whether or not the container still exists. The
    /// SID is worked out from the name alone.
    fn derive(name: &str) -> Result<Self, String> {
        let wide_name = wide(name);
        let mut sid: PSID = null_mut();
        // SAFETY: as in `create`.
        let derived = unsafe { DeriveAppContainerSidFromAppContainerName(wide_name.as_ptr(), &mut sid) };
        if derived >= 0 { Ok(Self(sid)) } else { Err(format!("the AppContainer's identity could not be worked out (0x{derived:08X})")) }
    }

    /// The container's own folder, where its programs may write (Java's temporary files).
    fn folder(&self) -> Option<PathBuf> {
        let mut text: windows_sys::core::PWSTR = null_mut();
        // SAFETY: the SID is valid; the strings returned are freed with LocalFree.
        unsafe {
            if ConvertSidToStringSidW(self.0, &mut text) == 0 {
                return None;
            }
            let mut path: windows_sys::core::PWSTR = null_mut();
            let result = GetAppContainerFolderPath(text, &mut path);
            LocalFree(text.cast());
            if result < 0 {
                return None;
            }
            let length = (0..).take_while(|&i| *path.add(i) != 0).count();
            let folder = PathBuf::from(OsString::from(String::from_utf16_lossy(std::slice::from_raw_parts(path, length))));
            // Freed with CoTaskMemFree in principle; the helper exits soon after, so it is left.
            Some(folder)
        }
    }
}

impl Drop for ContainerSid {
    fn drop(&mut self) {
        // SAFETY: the SID came from CreateAppContainerProfile or Derive…, which both say FreeSid.
        unsafe { FreeSid(self.0) };
    }
}

/// A SID read from its text form, such as `S-1-15-2-1`.
struct TextSid(PSID);

impl TextSid {
    /// The SID `text` names, or None if Windows cannot read it.
    fn parse(text: &str) -> Option<Self> {
        let text = wide(text);
        let mut sid: PSID = null_mut();
        // SAFETY: text is NUL-terminated; sid is freed with LocalFree in Drop.
        (unsafe { ConvertStringSidToSidW(text.as_ptr(), &mut sid) } != 0).then_some(Self(sid))
    }
}

impl Drop for TextSid {
    fn drop(&mut self) {
        // SAFETY: ConvertStringSidToSidW says LocalFree.
        unsafe { LocalFree(self.0.cast()) };
    }
}

/// An access list entry for `sid`, granting `access` or (with REVOKE_ACCESS) removing every
/// entry for that SID.
fn entry(sid: PSID, access: u32, mode: i32, inheritance: u32) -> EXPLICIT_ACCESS_W {
    EXPLICIT_ACCESS_W {
        grfAccessPermissions: access,
        grfAccessMode: mode,
        grfInheritance: inheritance,
        Trustee: TRUSTEE_W {
            pMultipleTrustee: null_mut(),
            MultipleTrusteeOperation: NO_MULTIPLE_TRUSTEE,
            TrusteeForm: TRUSTEE_IS_SID,
            TrusteeType: TRUSTEE_IS_WELL_KNOWN_GROUP,
            ptstrName: sid.cast(),
        },
    }
}

/// Applies `entries` to the access list of `path`. With `only_if_changed`, the list is written
/// back only when the entries removed something, so a clean-up walk leaves untouched files as
/// they were.
fn change_access(path: &Path, entries: &[EXPLICIT_ACCESS_W], only_if_changed: bool) -> Result<(), String> {
    let name = wide(path);
    let mut old: *mut ACL = null_mut();
    let mut descriptor = null_mut();
    // SAFETY: name is NUL-terminated; out-pointers are valid; descriptor is freed below.
    let read = unsafe { GetNamedSecurityInfoW(name.as_ptr(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, null_mut(), null_mut(), &mut old, null_mut(), &mut descriptor) };
    if read != ERROR_SUCCESS {
        return Err(format!("cannot read the access list of {} (error {read})", path.display()));
    }
    let mut new: *mut ACL = null_mut();
    // SAFETY: entries and old are valid (old may be null: no list); new and descriptor are
    // freed here. Setting the list also passes the change down to everything inside a folder.
    let result = unsafe {
        let made = SetEntriesInAclW(entries.len() as u32, entries.as_ptr(), old, &mut new);
        let unchanged = made == ERROR_SUCCESS && only_if_changed && (old.is_null() || (!new.is_null() && (*new).AceCount == (*old).AceCount));
        let set = if made != ERROR_SUCCESS {
            made
        } else if unchanged {
            ERROR_SUCCESS
        } else {
            SetNamedSecurityInfoW(name.as_ptr(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, null_mut(), null_mut(), new, null())
        };
        LocalFree(new.cast());
        LocalFree(descriptor);
        set
    };
    if result == ERROR_SUCCESS { Ok(()) } else { Err(format!("cannot change the access list of {} (error {result})", path.display())) }
}

/// Adds (`grant`) or removes the container's access to one file or folder.
fn set_access(sid: PSID, path: &Path, writable: bool, grant: bool) -> Result<(), String> {
    let access = if writable { WRITE_ACCESS } else { GENERIC_READ | GENERIC_EXECUTE };
    let inheritance = if path.is_dir() { SUB_CONTAINERS_AND_OBJECTS_INHERIT } else { NO_INHERITANCE };
    change_access(path, &[entry(sid, access, if grant { GRANT_ACCESS } else { REVOKE_ACCESS }, inheritance)], false)
}

/// Takes back what a run was given, newest first: each grant, then, under each writable
/// folder, any entry still naming the container or every container. Entries added inside a
/// folder are not removed by taking back the folder's own grant, and nothing under a link is
/// touched (see [`leftovers::walk`]). Problems are reported and the rest carries on.
fn undo(sid: &ContainerSid, given: &[(PathBuf, bool)]) {
    let every: Vec<TextSid> = EVERY_CONTAINER.iter().filter_map(|text| TextSid::parse(text)).collect();
    let mut scrub: Vec<EXPLICIT_ACCESS_W> = vec![entry(sid.0, 0, REVOKE_ACCESS, NO_INHERITANCE)];
    scrub.extend(every.iter().map(|other| entry(other.0, 0, REVOKE_ACCESS, NO_INHERITANCE)));
    for (path, writable) in given.iter().rev() {
        if let Err(problem) = set_access(sid.0, path, *writable, false) {
            eprintln!("javabyte-sandbox: {problem}");
        }
        if *writable && path.is_dir() {
            for inside in leftovers::walk(path, leftovers::is_link) {
                if let Err(problem) = change_access(&inside, &scrub, true) {
                    eprintln!("javabyte-sandbox: {problem}");
                }
            }
        }
    }
}

/// Deletes the container `name` and its folder. A container that does not exist is fine.
fn delete_container(name: &str) {
    let wide_name = wide(name);
    // SAFETY: a NUL-terminated string.
    unsafe { DeleteAppContainerProfile(wide_name.as_ptr()) };
}

/// One run's container, its grants and its journal. Dropping it undoes the grants, deletes the
/// container, and then deletes the journal, in that order, so a helper ended part-way leaves a
/// journal that still lists what is left.
struct Analysis {
    /// The container's name, `JavaByte.Analysis.<unique>`.
    name: String,
    /// The container's SID, which the grants name.
    sid: ContainerSid,
    /// The grants made, and whether each was writable.
    given: Vec<(PathBuf, bool)>,
    /// The journal, open and locked for as long as this helper lives.
    journal: Option<std::fs::File>,
    /// Where the journal is, to delete it at the end.
    journal_path: PathBuf,
}

impl Analysis {
    /// Starts the journal, then makes the container it names.
    fn begin() -> Result<Self, String> {
        let dir = journal_dir();
        std::fs::create_dir_all(&dir).map_err(|e| format!("cannot prepare {}: {e}", dir.display()))?;
        let name = format!("{CONTAINER_PREFIX}{}", leftovers::unique_suffix());
        let journal_path = dir.join(format!("{name}.{JOURNAL_EXTENSION}"));
        let mut journal = leftovers::lock(&journal_path, true).map_err(|e| format!("cannot start {}: {e}", journal_path.display()))?;
        let written = journal.write_all(leftovers::container_line(&name).as_bytes());
        let sid = written.map_err(|e| format!("cannot write {}: {e}", journal_path.display())).and_then(|()| ContainerSid::create(&name));
        match sid {
            Ok(sid) => Ok(Self { name, sid, given: Vec::new(), journal: Some(journal), journal_path }),
            Err(problem) => {
                // Nothing was granted; a container made part-way is deleted with the journal.
                drop(journal);
                delete_container(&name);
                let _ = std::fs::remove_file(&journal_path);
                Err(problem)
            }
        }
    }

    /// Writes the grant down, then makes it. A path the journal cannot hold exactly is not
    /// granted, since the grant could not be undone after a forced end.
    fn grant(&mut self, path: &Path, writable: bool) -> Result<(), String> {
        let line = leftovers::grant_line(path, writable).ok_or_else(|| format!("cannot record a grant for {}", path.display()))?;
        let journal = self.journal.as_mut().ok_or("the journal is closed")?;
        journal.write_all(line.as_bytes()).map_err(|e| format!("cannot write {}: {e}", self.journal_path.display()))?;
        // Undone even if it fails: part of a grant may have been made.
        self.given.push((path.to_path_buf(), writable));
        set_access(self.sid.0, path, writable, true)
    }
}

impl Drop for Analysis {
    fn drop(&mut self) {
        undo(&self.sid, &self.given);
        delete_container(&self.name);
        // Windows will not delete a file that is open, so the journal is closed first.
        self.journal.take();
        let _ = std::fs::remove_file(&self.journal_path);
    }
}

/// Finishes what helpers ended from outside left: for each journal no live helper holds,
/// undoes its grants, deletes its container, then deletes the journal. Also deletes the one
/// container earlier versions shared. Problems are reported or ignored; what cannot be undone
/// now is tried again next time, as long as its journal is kept.
fn clear_leftovers() {
    if let Ok(entries) = std::fs::read_dir(journal_dir()) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension() != Some(OsStr::new(JOURNAL_EXTENSION)) {
                continue;
            }
            // A journal still locked belongs to a helper that is running.
            let Ok(mut file) = leftovers::lock(&path, false) else { continue };
            let mut text = String::new();
            if file.read_to_string(&mut text).is_err() {
                continue;
            }
            let journal = leftovers::parse_journal(&text);
            if let Some(name) = journal.container.filter(|name| leftovers::is_container_name(name)) {
                match ContainerSid::derive(&name) {
                    Ok(sid) => undo(&sid, &journal.grants),
                    Err(problem) => {
                        eprintln!("javabyte-sandbox: {problem}");
                        continue;
                    }
                }
                delete_container(&name);
            }
            drop(file);
            let _ = std::fs::remove_file(&path);
        }
    }
    delete_container(LEGACY_CONTAINER);
}

/// Starts `program` in the container and waits for it. Returns its exit code, or an error when
/// it could not be started. The program is started only inside a job that ends it, and limits
/// its processes; if the job cannot be set up or the program put in it, the program is ended
/// before it runs a single instruction.
fn start_and_wait(sid: &ContainerSid, program: &[OsString]) -> Result<u32, String> {
    let mut command_line = wide(crate::plan::command_line(program));
    // SAFETY: plain Windows calls on handles this function owns or the process's own standard
    // handles; every handle opened here is closed before returning (the job last, which ends any
    // process still in it).
    unsafe {
        let job = CreateJobObjectW(null(), null());
        if job.is_null() {
            return Err(format!("cannot create a job object: {}", std::io::Error::last_os_error()));
        }
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
        limits.BasicLimitInformation.ActiveProcessLimit = MAX_PROCESSES;
        if SetInformationJobObject(job, JobObjectExtendedLimitInformation, (&raw const limits).cast(), size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32) == 0 {
            let error = std::io::Error::last_os_error();
            CloseHandle(job);
            return Err(format!("cannot set the job object's limits: {error}"));
        }

        // The program gets this helper's standard handles, and no other handle.
        let handles: [HANDLE; 3] = [GetStdHandle(STD_INPUT_HANDLE), GetStdHandle(STD_OUTPUT_HANDLE), GetStdHandle(STD_ERROR_HANDLE)];
        // Windows refuses a list that names a handle twice (stdout and stderr on one console).
        let mut inherit: Vec<HANDLE> = Vec::new();
        for h in handles {
            if !h.is_null() && !inherit.contains(&h) {
                SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
                inherit.push(h);
            }
        }
        let mut capabilities = SECURITY_CAPABILITIES { AppContainerSid: sid.0, Capabilities: null_mut(), CapabilityCount: 0, Reserved: 0 };
        let mut size = 0usize;
        InitializeProcThreadAttributeList(null_mut(), 2, 0, &mut size);
        let mut list = vec![0u8; size];
        let attributes = list.as_mut_ptr().cast::<c_void>();
        if InitializeProcThreadAttributeList(attributes, 2, 0, &mut size) == 0
            || UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES as usize, (&raw mut capabilities).cast(), size_of::<SECURITY_CAPABILITIES>(), null_mut(), null())
                == 0
            || UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST as usize, inherit.as_ptr().cast(), size_of_val(inherit.as_slice()), null_mut(), null()) == 0
        {
            CloseHandle(job);
            return Err("cannot prepare the AppContainer's start-up".into());
        }
        let mut startup: STARTUPINFOEXW = std::mem::zeroed();
        startup.StartupInfo.cb = size_of::<STARTUPINFOEXW>() as u32;
        startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        startup.StartupInfo.hStdInput = handles[0];
        startup.StartupInfo.hStdOutput = handles[1];
        startup.StartupInfo.hStdError = handles[2];
        startup.lpAttributeList = attributes;
        let mut process: PROCESS_INFORMATION = std::mem::zeroed();
        let started = CreateProcessW(
            null(),
            command_line.as_mut_ptr(),
            null(),
            null(),
            1,
            EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW,
            null(),
            null(),
            (&raw const startup).cast(),
            &mut process,
        );
        if started == 0 {
            let error = std::io::Error::last_os_error();
            CloseHandle(job);
            return Err(format!("cannot start {} in the AppContainer: {error}", program[0].to_string_lossy()));
        }
        // In the job before it runs a single instruction; if that fails, it never runs one.
        let problem = if AssignProcessToJobObject(job, process.hProcess) == 0 {
            Some(format!("cannot put the program in the job object: {}", std::io::Error::last_os_error()))
        } else if ResumeThread(process.hThread) == u32::MAX {
            Some(format!("cannot start the program's first thread: {}", std::io::Error::last_os_error()))
        } else {
            None
        };
        if let Some(problem) = problem {
            TerminateProcess(process.hProcess, 1);
            WaitForSingleObject(process.hProcess, INFINITE);
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
            CloseHandle(job);
            return Err(problem);
        }
        WaitForSingleObject(process.hProcess, INFINITE);
        let mut code = 0u32;
        GetExitCodeProcess(process.hProcess, &mut code);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        Ok(code)
    }
}

/// Whether AppContainers work here: starts a harmless program in a container of its own, which
/// is deleted afterwards. Also finishes what helpers ended from outside left.
pub fn check() -> Result<(), String> {
    clear_leftovers();
    let analysis = Analysis::begin()?;
    let system = std::env::var_os("SystemRoot").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("C:\\Windows"));
    let probe = [system.join("System32").join("cmd.exe").into_os_string(), "/c".into(), "exit 0".into()];
    match start_and_wait(&analysis.sid, &probe)? {
        0 => Ok(()),
        code => Err(format!("a test program in the AppContainer ended with code {code}")),
    }
}

/// `appcontainer`: runs the program with the grants given, and returns its exit code. First
/// finishes what helpers ended from outside left.
pub fn run(read: &[PathBuf], write: &[PathBuf], program: &[OsString]) -> i32 {
    clear_leftovers();
    let mut analysis = match Analysis::begin() {
        Ok(analysis) => analysis,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            return crate::ISOLATION_FAILED;
        }
    };
    for (paths, writable) in [(read, false), (write, true)] {
        for path in paths {
            // Under Program Files the list cannot be changed without administrator rights,
            // and every AppContainer may already read there; elsewhere the program says so.
            if let Err(problem) = analysis.grant(path, writable) {
                eprintln!("javabyte-sandbox: {problem}");
            }
        }
    }
    // Java's temporary files go to the container's own folder, the one place it may write
    // besides the run's folder. It is deleted with the container.
    if let Some(folder) = analysis.sid.folder() {
        // SAFETY: single-threaded at this point; the variables are read by the child only.
        unsafe {
            std::env::set_var("TEMP", &folder);
            std::env::set_var("TMP", &folder);
        }
    }
    let code = match start_and_wait(&analysis.sid, program) {
        Ok(code) => code as i32,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            crate::ISOLATION_FAILED
        }
    };
    drop(analysis);
    code
}
