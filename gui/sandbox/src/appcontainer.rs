//! Running a program in an AppContainer: Windows' own sandbox for apps, on every edition of
//! Windows 10 and 11, with no administrator rights.
//!
//! The container `JavaByte.Analysis` is given no capabilities: no network (client or server), no
//! access to the person's libraries or files. What the analysis needs is granted for the run
//! only, by adding the container's SID to each path's access list, and taken away afterwards:
//! read access to the sample, write access to the run's folder. The Java runtime under Program
//! Files is readable by every AppContainer already; outside Program Files (a portable copy, or
//! CI) the app passes those folders with `--read` too.
//!
//! The program runs in a job object that ends the whole process tree when the helper ends (so
//! killing the helper cannot leave an analysis running) and allows only a few processes: Java,
//! and the native bridge's Python.

use std::ffi::{OsStr, OsString, c_void};
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use windows_sys::Win32::Foundation::{
    CloseHandle, ERROR_ALREADY_EXISTS, ERROR_SUCCESS, GENERIC_ALL, GENERIC_EXECUTE, GENERIC_READ, HANDLE, HANDLE_FLAG_INHERIT, LocalFree,
    SetHandleInformation,
};
use windows_sys::Win32::Security::Authorization::{
    EXPLICIT_ACCESS_W, GRANT_ACCESS, GetNamedSecurityInfoW, NO_MULTIPLE_TRUSTEE, REVOKE_ACCESS, SE_FILE_OBJECT, SetEntriesInAclW,
    SetNamedSecurityInfoW, TRUSTEE_IS_SID, TRUSTEE_IS_WELL_KNOWN_GROUP, TRUSTEE_W, ConvertSidToStringSidW,
};
use windows_sys::Win32::Security::Isolation::{CreateAppContainerProfile, DeriveAppContainerSidFromAppContainerName, GetAppContainerFolderPath};
use windows_sys::Win32::Security::{ACL, DACL_SECURITY_INFORMATION, FreeSid, NO_INHERITANCE, PSID, SECURITY_CAPABILITIES, SUB_CONTAINERS_AND_OBJECTS_INHERIT};
use windows_sys::Win32::System::Console::{GetStdHandle, STD_ERROR_HANDLE, STD_INPUT_HANDLE, STD_OUTPUT_HANDLE};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_ACTIVE_PROCESS, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation, SetInformationJobObject,
};
use windows_sys::Win32::System::Threading::{
    CREATE_NO_WINDOW, CREATE_SUSPENDED, CreateProcessW, EXTENDED_STARTUPINFO_PRESENT, GetExitCodeProcess, INFINITE,
    InitializeProcThreadAttributeList, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, PROCESS_INFORMATION,
    ResumeThread, STARTF_USESTDHANDLES, STARTUPINFOEXW, UpdateProcThreadAttribute, WaitForSingleObject,
};

/// The container's name. One for all analyses: it holds nothing between runs except Java's own
/// temporary files, in the container's folder.
const CONTAINER: &str = "JavaByte.Analysis";
/// Java, the native bridge's Python, and a margin.
const MAX_PROCESSES: u32 = 4;

/// A wide, NUL-terminated copy of a string, for Windows calls.
fn wide(text: impl AsRef<OsStr>) -> Vec<u16> {
    text.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}

/// The container's SID, creating the container the first time.
struct ContainerSid(PSID);

impl ContainerSid {
    fn get() -> Result<Self, String> {
        let name = wide(CONTAINER);
        let mut sid: PSID = null_mut();
        // SAFETY: valid NUL-terminated strings; sid receives a SID freed in Drop.
        let created = unsafe { CreateAppContainerProfile(name.as_ptr(), name.as_ptr(), name.as_ptr(), null(), 0, &mut sid) };
        if created >= 0 {
            return Ok(Self(sid));
        }
        // 0x800700B7: the container exists from an earlier run.
        if created as u32 == 0x8007_0000 | ERROR_ALREADY_EXISTS {
            // SAFETY: as above.
            let derived = unsafe { DeriveAppContainerSidFromAppContainerName(name.as_ptr(), &mut sid) };
            if derived >= 0 {
                return Ok(Self(sid));
            }
            return Err(format!("the AppContainer exists but its identity could not be read (0x{derived:08X})"));
        }
        Err(format!("Windows refused to create the AppContainer (0x{created:08X})"))
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

/// Adds (`grant`) or removes the container's access to one file or folder.
fn set_access(sid: &ContainerSid, path: &Path, writable: bool, grant: bool) -> Result<(), String> {
    let name = wide(path);
    let folder = path.is_dir();
    let mut old: *mut ACL = null_mut();
    let mut descriptor = null_mut();
    // SAFETY: name is NUL-terminated; out-pointers are valid; descriptor is freed below.
    let read = unsafe { GetNamedSecurityInfoW(name.as_ptr(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, null_mut(), null_mut(), &mut old, null_mut(), &mut descriptor) };
    if read != ERROR_SUCCESS {
        return Err(format!("cannot read the access list of {} (error {read})", path.display()));
    }
    let entry = EXPLICIT_ACCESS_W {
        grfAccessPermissions: if writable { GENERIC_ALL } else { GENERIC_READ | GENERIC_EXECUTE },
        grfAccessMode: if grant { GRANT_ACCESS } else { REVOKE_ACCESS },
        grfInheritance: if folder { SUB_CONTAINERS_AND_OBJECTS_INHERIT } else { NO_INHERITANCE },
        Trustee: TRUSTEE_W {
            pMultipleTrustee: null_mut(),
            MultipleTrusteeOperation: NO_MULTIPLE_TRUSTEE,
            TrusteeForm: TRUSTEE_IS_SID,
            TrusteeType: TRUSTEE_IS_WELL_KNOWN_GROUP,
            ptstrName: sid.0.cast(),
        },
    };
    let mut new: *mut ACL = null_mut();
    // SAFETY: entry and old are valid; new is freed below. Setting the list also passes the
    // change down to everything inside a folder.
    let result = unsafe {
        let made = SetEntriesInAclW(1, &entry, old, &mut new);
        let set = if made == ERROR_SUCCESS {
            SetNamedSecurityInfoW(name.as_ptr(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, null_mut(), null_mut(), new, null())
        } else {
            made
        };
        LocalFree(new.cast());
        LocalFree(descriptor);
        set
    };
    if result == ERROR_SUCCESS { Ok(()) } else { Err(format!("cannot change the access list of {} (error {result})", path.display())) }
}

/// The grants for one run, removed when dropped, whatever happens to the program.
struct Grants<'a> {
    sid: &'a ContainerSid,
    given: Vec<(PathBuf, bool)>,
}

impl Drop for Grants<'_> {
    fn drop(&mut self) {
        for (path, writable) in &self.given {
            if let Err(problem) = set_access(self.sid, path, *writable, false) {
                eprintln!("javabyte-sandbox: {problem}");
            }
        }
    }
}

/// Starts `program` in the container and waits for it. Returns its exit code, or an error when
/// it could not be started.
fn start_and_wait(sid: &ContainerSid, program: &[OsString]) -> Result<u32, String> {
    let mut command_line = wide(crate::plan::command_line(program));
    // SAFETY: plain Windows calls on handles this function owns or the process's own standard
    // handles; every handle opened here is closed before returning (the job last, which ends any
    // process still in it).
    unsafe {
        let job = CreateJobObjectW(null(), null());
        if job.is_null() {
            return Err("cannot create a job object".into());
        }
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
        limits.BasicLimitInformation.ActiveProcessLimit = MAX_PROCESSES;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, (&raw const limits).cast(), size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32);

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
        // In the job before it runs a single instruction.
        AssignProcessToJobObject(job, process.hProcess);
        ResumeThread(process.hThread);
        WaitForSingleObject(process.hProcess, INFINITE);
        let mut code = 0u32;
        GetExitCodeProcess(process.hProcess, &mut code);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        Ok(code)
    }
}

/// Whether AppContainers work here: starts a harmless program in one.
pub fn check() -> Result<(), String> {
    let sid = ContainerSid::get()?;
    let system = std::env::var_os("SystemRoot").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("C:\\Windows"));
    let probe = [system.join("System32").join("cmd.exe").into_os_string(), "/c".into(), "exit 0".into()];
    match start_and_wait(&sid, &probe)? {
        0 => Ok(()),
        code => Err(format!("a test program in the AppContainer ended with code {code}")),
    }
}

/// `appcontainer`: runs the program with the grants given, and returns its exit code.
pub fn run(read: &[PathBuf], write: &[PathBuf], program: &[OsString]) -> i32 {
    let sid = match ContainerSid::get() {
        Ok(sid) => sid,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            return crate::ISOLATION_FAILED;
        }
    };
    let mut grants = Grants { sid: &sid, given: Vec::new() };
    for (paths, writable) in [(read, false), (write, true)] {
        for path in paths {
            match set_access(&sid, path, writable, true) {
                Ok(()) => grants.given.push((path.clone(), writable)),
                // Under Program Files the list cannot be changed without administrator rights,
                // and every AppContainer may already read there; elsewhere the program says so.
                Err(problem) => eprintln!("javabyte-sandbox: {problem}"),
            }
        }
    }
    // Java's temporary files go to the container's own folder, the one place it may write
    // besides the run's folder.
    if let Some(folder) = sid.folder() {
        // SAFETY: single-threaded at this point; the variables are read by the child only.
        unsafe {
            std::env::set_var("TEMP", &folder);
            std::env::set_var("TMP", &folder);
        }
    }
    let code = match start_and_wait(&sid, program) {
        Ok(code) => code as i32,
        Err(problem) => {
            eprintln!("javabyte-sandbox: {problem}");
            crate::ISOLATION_FAILED
        }
    };
    drop(grants);
    code
}
