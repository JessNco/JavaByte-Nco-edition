//! Saved files: only the report's own, only inside the run folder; copies in an AES zip.

use javabyte_gui_backend::artifacts::{self, ZIP_PASSWORD};
use javabyte_gui_protocol::views::Artifact;
use std::io::Read;
use std::path::{Path, PathBuf};

fn folder(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("javabyte-artifacts-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("run/dropped/C_/Users")).unwrap();
    std::fs::write(dir.join("run/dropped/C_/Users/stage.bin"), b"MZ\x90\x00 pretend payload").unwrap();
    std::fs::write(dir.join("outside.txt"), b"not the run's").unwrap();
    dir
}

#[test]
fn only_the_reports_own_files_inside_the_folder() {
    let dir = folder("resolve");
    let run = dir.join("run");
    let listed = vec!["dropped/C_/Users/stage.bin".to_string(), "../outside.txt".to_string(), "/etc/passwd".to_string()];
    assert!(artifacts::resolve(&run, &listed, "dropped/C_/Users/stage.bin").is_ok());
    // Listed by a (hostile or damaged) report, but leading out of the folder: refused.
    assert!(artifacts::resolve(&run, &listed, "../outside.txt").is_err());
    assert!(artifacts::resolve(&run, &listed, "/etc/passwd").is_err());
    // Inside the folder but not listed: refused.
    assert!(artifacts::resolve(&run, &listed, "dropped").is_err());
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(dir.join("outside.txt"), run.join("dropped/link.bin")).unwrap();
        let with_link = vec!["dropped/link.bin".to_string()];
        assert!(artifacts::resolve(&run, &with_link, "dropped/link.bin").is_err(), "a link out of the folder is refused");
    }
}

#[test]
fn pages_are_64_kb_and_say_the_total() {
    let dir = folder("pages");
    let big = dir.join("big.bin");
    std::fs::write(&big, vec![7u8; 150_000]).unwrap();
    let first = artifacts::read_page(&big, 0).unwrap();
    assert_eq!((first.offset, first.total, first.bytes.len()), (0, 150_000, 65_536));
    let last = artifacts::read_page(&big, 131_072).unwrap();
    assert_eq!(last.bytes.len(), 150_000 - 131_072);
    assert!(artifacts::read_page(&big, 999_999).unwrap().bytes.is_empty());
}

#[test]
fn copies_go_into_an_aes_zip_with_the_warning() {
    let dir = folder("zip");
    let run = dir.join("run");
    let source = run.join("dropped/C_/Users/stage.bin");
    let artifact = Artifact {
        path: "dropped/C_/Users/stage.bin".into(),
        name: "stage.bin".into(),
        kind: "dropped".into(),
        size: 22,
        sha256: artifacts::sha256_of(&source).unwrap(),
        origin: Some("C:\\Users\\stage.bin".into()),
    };
    let zip_path = dir.join("copy.zip");
    artifacts::write_zip(&zip_path, &run, &[(artifact.clone(), source.clone()), (artifact.clone(), source.clone())]).unwrap();

    // Raw headers: AES (method 99 with the 0x9901 extra field), never ZipCrypto alone.
    let raw = std::fs::read(&zip_path).unwrap();
    let headers: Vec<usize> = raw.windows(4).enumerate().filter(|(_, w)| *w == b"PK\x03\x04").map(|(i, _)| i).collect();
    assert_eq!(headers.len(), 3, "two files and the warning");
    for at in headers {
        let method = u16::from_le_bytes([raw[at + 8], raw[at + 9]]);
        assert_eq!(method, 99, "every entry is AES-encrypted");
    }
    assert!(raw.windows(2).any(|w| w == [0x01, 0x99]), "the AES extra field is present");
    assert!(!raw.windows(7).any(|w| w == b"payload"), "nothing readable without the password");

    // With the password: the file unchanged, a second copy renamed, and the warning.
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    let mut names: Vec<String> = archive.file_names().map(|n| n.unwrap().into_owned()).collect();
    names.sort();
    assert_eq!(names, vec!["2-stage.bin", "README-WARNING.txt", "stage.bin"]);
    let mut content = Vec::new();
    archive.by_name_decrypt("stage.bin", ZIP_PASSWORD.as_bytes()).unwrap().read_to_end(&mut content).unwrap();
    assert_eq!(content, std::fs::read(&source).unwrap());
    let mut info = String::new();
    archive.by_name_decrypt("README-WARNING.txt", ZIP_PASSWORD.as_bytes()).unwrap().read_to_string(&mut info).unwrap();
    assert!(info.contains("may be malware") && info.contains(&artifact.sha256), "{info}");
    assert!(archive.by_name_decrypt("stage.bin", b"wrong").is_err(), "the wrong password does not open it");
    if let Some(keep) = std::env::var_os("KEEP_ZIP") { std::fs::copy(&zip_path, keep).unwrap(); }
}

#[test]
fn plain_copies_are_logged_and_never_next_to_the_sample() {
    let dir = folder("as-is");
    let run = dir.join("run");
    let source = run.join("dropped/C_/Users/stage.bin");
    let sample = dir.join("samples/x.jar");
    std::fs::create_dir_all(sample.parent().unwrap()).unwrap();
    std::fs::write(&sample, b"PK").unwrap();
    assert!(artifacts::check_destination(&dir.join("samples/stage.bin"), &sample).is_err(), "not into the sample's folder");
    assert!(artifacts::check_destination(&dir.join("stage.bin"), &sample).is_ok());
    let artifact = Artifact { path: "dropped/C_/Users/stage.bin".into(), name: "stage.bin".into(), kind: "dropped".into(), size: 22, sha256: "abc".into(), origin: None };
    artifacts::save_as_is(&dir.join("stage.bin"), &run, &artifact, &source).unwrap();
    let log = std::fs::read_to_string(run.join(artifacts::ACTIONS_LOG)).unwrap();
    assert!(log.contains("saved as is: dropped/C_/Users/stage.bin"), "{log}");
    assert!(Path::new(&dir.join("stage.bin")).is_file());
}
