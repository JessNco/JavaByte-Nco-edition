//! What a helper that was ended from outside leaves behind, and how the next helper finds and
//! clears it.
//!
//! The desktop app ends the helper with `TerminateProcess` when a run ignores Stop, times out,
//! or its window closes. That runs none of the helper's own clean-up, so each run records what
//! it must undo before it changes anything, in a place the next helper looks:
//!
//! - an AppContainer run keeps a journal, one file per run in the person's local app data,
//!   naming its container and every path it was granted;
//! - a Windows Sandbox run keeps its working folder (with the staged copy of the sample) under
//!   a name of its own in TEMP.
//!
//! A live helper holds a lock on its journal or folder: a file opened with no sharing, which
//! Windows releases when the process ends however it ends. So a lock the next helper can take
//! belongs to a helper that has gone, and what it guards can be cleared.
//!
//! The names and the journal's format are worked out here without touching the system, so they
//! can be tested everywhere.

use std::hash::{BuildHasher, Hasher};
use std::path::{Path, PathBuf};

/// The start of every per-run AppContainer's name. The rest is [`unique_suffix`].
pub const CONTAINER_PREFIX: &str = "JavaByte.Analysis.";
/// The one container earlier versions shared between all runs. Nothing runs in it any more; it
/// is deleted when found, though the grants it was left with cannot be traced.
pub const LEGACY_CONTAINER: &str = "JavaByte.Analysis";
/// The start of every Windows Sandbox working folder's name in TEMP.
pub const ROOT_PREFIX: &str = "javabyte-sandbox-";
/// The lock file inside a Windows Sandbox working folder.
pub const LOCK_FILE: &str = "lock";
/// The ending of a journal file's name.
pub const JOURNAL_EXTENSION: &str = "grants";
/// The most entries a clean-up walk visits, so a folder filled from inside cannot keep the
/// helper busy for ever.
pub const WALK_LIMIT: usize = 100_000;

/// A name part no other helper will choose: this process's ID and 64 random bits. The ID keeps
/// two helpers apart; the random part keeps a later helper that is given the same ID apart
/// from an earlier one.
pub fn unique_suffix() -> String {
    // RandomState is seeded from the operating system's random source.
    let random = std::collections::hash_map::RandomState::new().build_hasher().finish();
    format!("{}-{random:016x}", std::process::id())
}

/// Whether `suffix` has the shape [`unique_suffix`] gives: digits, a dash, 16 hex digits.
fn is_unique_suffix(suffix: &str) -> bool {
    let Some((pid, random)) = suffix.split_once('-') else { return false };
    !pid.is_empty() && pid.bytes().all(|b| b.is_ascii_digit()) && random.len() == 16 && random.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Whether `name` is a per-run container this helper made. Only these are ever deleted, so a
/// damaged journal cannot make the helper delete another app's container.
pub fn is_container_name(name: &str) -> bool {
    name.strip_prefix(CONTAINER_PREFIX).is_some_and(is_unique_suffix)
}

/// What kind of Windows Sandbox working folder a name in TEMP is.
#[derive(Debug, PartialEq)]
pub enum RootKind {
    /// A folder from this version, guarded by its [`LOCK_FILE`].
    Current,
    /// A folder from an earlier version, named by process ID alone and never locked. It is old
    /// by now, so it is cleared.
    Legacy,
}

/// Which kind of working folder `name` is, or None if it is not one.
pub fn root_kind(name: &str) -> Option<RootKind> {
    let suffix = name.strip_prefix(ROOT_PREFIX)?;
    if is_unique_suffix(suffix) {
        Some(RootKind::Current)
    } else if !suffix.is_empty() && suffix.bytes().all(|b| b.is_ascii_digit()) {
        Some(RootKind::Legacy)
    } else {
        None
    }
}

/// What one run's journal says: its container, and each path granted with whether it was
/// writable, in the order they were granted.
#[derive(Debug, Default, PartialEq)]
pub struct Journal {
    /// The container's name, if the run got as far as naming it.
    pub container: Option<String>,
    /// The paths granted to the container, and whether each was writable.
    pub grants: Vec<(PathBuf, bool)>,
}

/// The journal line that names the container. Written before the container is made.
pub fn container_line(name: &str) -> String {
    format!("container\t{name}\n")
}

/// The journal line for one grant, written before the grant is made. None for a path that
/// cannot be written down exactly (not valid Unicode); such a path must not be granted, or the
/// grant could not be undone later.
pub fn grant_line(path: &Path, writable: bool) -> Option<String> {
    let text = path.to_str()?;
    // Windows forbids these in names; refusing them keeps one line one entry.
    if text.contains(['\t', '\n', '\r']) {
        return None;
    }
    Some(format!("{}\t{text}\n", if writable { "write" } else { "read" }))
}

/// Reads a journal. A last line without its line break was cut short when the helper was
/// ended, and is ignored: its path is incomplete, and the grant it announces was never made.
pub fn parse_journal(text: &str) -> Journal {
    let mut journal = Journal::default();
    let complete = match text.rfind('\n') {
        Some(end) => &text[..end],
        None => return journal,
    };
    for line in complete.split('\n') {
        match line.split_once('\t') {
            Some(("container", name)) if journal.container.is_none() => journal.container = Some(name.to_string()),
            Some(("read", path)) if !path.is_empty() => journal.grants.push((PathBuf::from(path), false)),
            Some(("write", path)) if !path.is_empty() => journal.grants.push((PathBuf::from(path), true)),
            _ => {}
        }
    }
    journal
}

/// Every file and folder under `root`, `root` first, without following links of any kind: a
/// link made from inside the sandbox must not lead the clean-up to change files elsewhere.
/// `is_link` says whether an entry is one (on Windows, any reparse point, junctions included).
/// Stops at [`WALK_LIMIT`] entries.
pub fn walk(root: &Path, is_link: impl Fn(&std::fs::Metadata) -> bool) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut pending = vec![root.to_path_buf()];
    while let Some(path) = pending.pop() {
        if found.len() >= WALK_LIMIT {
            break;
        }
        let Ok(meta) = std::fs::symlink_metadata(&path) else { continue };
        if is_link(&meta) {
            continue;
        }
        if meta.is_dir()
            && let Ok(entries) = std::fs::read_dir(&path)
        {
            pending.extend(entries.flatten().map(|e| e.path()));
        }
        found.push(path);
    }
    found
}

/// Whether an entry is a link the clean-up must not follow or change.
pub fn is_link(meta: &std::fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_REPARSE_POINT;
        meta.file_type().is_symlink() || meta.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
    }
    #[cfg(not(windows))]
    {
        meta.file_type().is_symlink()
    }
}

/// Opens `path` with no sharing, which only works while no other process has it open. For a
/// lock this helper creates, `create` is true; for another helper's, false.
#[cfg(windows)]
pub fn lock(path: &Path, create: bool) -> std::io::Result<std::fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    std::fs::OpenOptions::new().read(true).write(true).create_new(create).share_mode(0).open(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_unique_and_recognised() {
        let (a, b) = (unique_suffix(), unique_suffix());
        assert_ne!(a, b, "two runs in one process still differ");
        assert!(is_container_name(&format!("{CONTAINER_PREFIX}{a}")));
        assert!(!is_container_name(LEGACY_CONTAINER), "the old shared container is handled apart");
        assert!(!is_container_name("Microsoft.WindowsCalculator"), "another app's container is never touched");
        assert!(!is_container_name(&format!("{CONTAINER_PREFIX}1-xyz")));
        assert_eq!(root_kind(&format!("{ROOT_PREFIX}{a}")), Some(RootKind::Current));
        assert_eq!(root_kind(&format!("{ROOT_PREFIX}4120")), Some(RootKind::Legacy));
        assert_eq!(root_kind(&format!("{ROOT_PREFIX}notes")), None);
        assert_eq!(root_kind("javabyte-other"), None);
    }

    #[test]
    fn a_journal_reads_back_and_ignores_a_line_cut_short() {
        let mut text = container_line("JavaByte.Analysis.1-00000000000000ab");
        text += &grant_line(Path::new("C:\\Java\\runtime"), false).unwrap();
        text += &grant_line(Path::new("C:\\Users\\a\\Documents\\JavaByte\\run 1"), true).unwrap();
        text += "write\tC:\\Users\\a\\Docu"; // the helper was ended while writing this
        let journal = parse_journal(&text);
        assert_eq!(journal.container.as_deref(), Some("JavaByte.Analysis.1-00000000000000ab"));
        assert_eq!(
            journal.grants,
            vec![(PathBuf::from("C:\\Java\\runtime"), false), (PathBuf::from("C:\\Users\\a\\Documents\\JavaByte\\run 1"), true)]
        );
        assert_eq!(parse_journal("container\tJavaByte.Anal"), Journal::default(), "nothing complete yet");
        assert_eq!(grant_line(Path::new("C:\\a\tb"), true), None);
    }

    #[test]
    fn the_walk_does_not_follow_links() {
        let root = std::env::temp_dir().join(format!("javabyte-sandbox-test-{}", unique_suffix()));
        let outside = root.with_extension("outside");
        std::fs::create_dir_all(root.join("a").join("b")).unwrap();
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(root.join("a").join("b").join("f"), b"x").unwrap();
        std::fs::write(outside.join("secret"), b"x").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, root.join("a").join("link")).unwrap();
        let found = walk(&root, is_link);
        assert_eq!(found[0], root, "the root comes first");
        assert!(found.contains(&root.join("a").join("b").join("f")));
        assert!(!found.iter().any(|p| p.ends_with("secret") || p.ends_with("link")), "{found:?}");
        let _ = std::fs::remove_dir_all(&root);
        let _ = std::fs::remove_dir_all(&outside);
    }
}
