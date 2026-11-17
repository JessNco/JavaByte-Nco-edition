//! Files a run saved: listing them, reading them a page at a time, and saving copies.
//!
//! Saved files may be live malware: a dropped `.jar` or `.exe` is exactly what the sample meant
//! to run (docs/GUI-PLAN.md, "Saving files a sample produced"). So:
//!
//! - only files named in the report's `artifacts` list are touched, and only inside the run's
//!   folder: a path with `..`, an absolute path, or a link leading out is refused;
//! - copies go into an AES-256 zip with the password `infected` by default; a plain copy is
//!   written only on an explicit request, which is logged in the run's folder;
//! - nothing here ever opens or runs a file.

use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{self, Artifact, FilePage, PAGE_BYTES};
use sha2::{Digest, Sha256};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};

/// The password every analysis tool and malware archive uses, so it is not a secret: the
/// encryption is there to keep scanners and careless double-clicks away from the file.
pub const ZIP_PASSWORD: &str = "infected";
/// The log of plain copies, in the run's folder.
pub const ACTIONS_LOG: &str = "gui-actions.log";

/// A saved file's real path, if `relative` is one of the report's artifacts and stays inside
/// the run's folder.
pub fn resolve(run_folder: &Path, artifacts: &[String], relative: &str) -> Result<PathBuf, String> {
    let refused = || t!("error-artifact-refused", path = relative);
    if !artifacts.iter().any(|a| a == relative) {
        return Err(refused());
    }
    // Older versions of the tool wrote '\\' on Windows; either separator works on any system.
    let portable = relative.replace('\\', "/");
    let rel = Path::new(&portable);
    if rel.is_absolute() || !rel.components().all(|c| matches!(c, Component::Normal(_))) {
        return Err(refused());
    }
    let root = run_folder.canonicalize().map_err(|e| t!("error-report-unreadable", detail = e.to_string()))?;
    let full = run_folder.join(rel).canonicalize().map_err(|_| refused())?;
    // A link inside the folder that leads out of it is refused too.
    if !full.starts_with(&root) || !full.is_file() {
        return Err(refused());
    }
    Ok(full)
}

/// Every saved file of a run, with size, SHA-256 and origin. Files that are missing or lead out
/// of the folder are left out.
pub fn list(run_folder: &Path, report: &javabyte_gui_protocol::report::Report) -> Vec<Artifact> {
    let origins = views::artifact_origins(report);
    report
        .artifacts
        .iter()
        .filter_map(|relative| {
            let full = resolve(run_folder, &report.artifacts, relative).ok()?;
            let size = full.metadata().ok()?.len();
            Some(Artifact {
                name: relative.rsplit(['/', '\\']).next().unwrap_or(relative).to_string(),
                kind: views::artifact_kind(relative).to_string(),
                sha256: sha256_of(&full).ok()?,
                origin: origins.get(relative).cloned(),
                path: relative.clone(),
                size,
            })
        })
        .collect()
}

/// SHA-256 of a file, in lowercase hex, read in pieces so large files need little memory.
pub fn sha256_of(path: &Path) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher)?;
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// One page of a saved file, starting at `offset`.
pub fn read_page(path: &Path, offset: u64) -> Result<FilePage, String> {
    let fail = |e: std::io::Error| t!("error-artifact-read", detail = e.to_string());
    let mut file = std::fs::File::open(path).map_err(fail)?;
    let total = file.metadata().map_err(fail)?.len();
    let offset = offset.min(total);
    file.seek(SeekFrom::Start(offset)).map_err(fail)?;
    let mut bytes = Vec::with_capacity(PAGE_BYTES as usize);
    file.take(PAGE_BYTES).read_to_end(&mut bytes).map_err(fail)?;
    Ok(FilePage { offset, total, bytes })
}

/// Refuses to save into the folder the sample is in, so a copy can never end up next to it
/// (where it could be taken for part of the sample, or run by it).
pub fn check_destination(destination: &Path, sample: &Path) -> Result<(), String> {
    let folder = |p: &Path| p.parent().and_then(|d| d.canonicalize().ok());
    let sample_folder = if sample.is_dir() { sample.canonicalize().ok() } else { folder(sample) };
    if sample_folder.is_some() && folder(destination) == sample_folder {
        return Err(t!("error-save-sample-folder"));
    }
    Ok(())
}

/// Writes the chosen files into an AES-256 zip with the password `infected`, plus a text file
/// with each file's SHA-256, the run it came from, and a warning.
pub fn write_zip(destination: &Path, run_folder: &Path, files: &[(Artifact, PathBuf)]) -> Result<(), String> {
    use zip::write::SimpleFileOptions;
    let fail = |e: &dyn std::fmt::Display| t!("error-save", detail = e.to_string());
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .with_aes_encryption(zip::AesMode::Aes256, ZIP_PASSWORD);
    let out = std::fs::File::create(destination).map_err(|e| fail(&e))?;
    let mut zip = zip::ZipWriter::new(out);
    let mut used: Vec<String> = Vec::new();
    let mut info = format!("{}\n\n{}\n", t!("zip-warning"), t!("zip-run", folder = run_folder.display().to_string()));
    for (artifact, path) in files {
        let name = unique_name(&safe_zip_name(&artifact.name), &mut used);
        zip.start_file(name.as_str(), options).map_err(|e| fail(&e))?;
        let mut input = std::fs::File::open(path).map_err(|e| fail(&e))?;
        std::io::copy(&mut input, &mut zip).map_err(|e| fail(&e))?;
        info.push_str(&format!("\n{name}\n  SHA-256: {}\n  {}\n", artifact.sha256, artifact.origin.clone().unwrap_or_default())); // not-ui-text: layout of the info file
    }
    zip.start_file("README-WARNING.txt", options).map_err(|e| fail(&e))?; // not-ui-text: a file name
    zip.write_all(info.as_bytes()).map_err(|e| fail(&e))?;
    zip.finish().map_err(|e| fail(&e))?;
    Ok(())
}

/// Copies one file as it is, after the person confirmed the warning, and records it in the
/// run's folder.
pub fn save_as_is(destination: &Path, run_folder: &Path, artifact: &Artifact, source: &Path) -> Result<(), String> {
    std::fs::copy(source, destination).map_err(|e| t!("error-save", detail = e.to_string()))?;
    let line = format!(
        "{} saved as is: {} (SHA-256 {}) to {}\n", // not-ui-text: a log line for analysts, kept in English
        crate::session::now_for_log(),
        artifact.path,
        artifact.sha256,
        destination.display()
    );
    let mut log = std::fs::OpenOptions::new().create(true).append(true).open(run_folder.join(ACTIONS_LOG)).map_err(|e| t!("error-save", detail = e.to_string()))?;
    log.write_all(line.as_bytes()).map_err(|e| t!("error-save", detail = e.to_string()))
}

/// A name that is safe inside a zip: no folders, no control characters, not empty.
fn safe_zip_name(name: &str) -> String {
    let cleaned: String = name.chars().map(|c| if c.is_control() || "/\\:*?\"<>|".contains(c) { '_' } else { c }).take(120).collect();
    let cleaned = cleaned.trim_matches(['.', ' ']).to_string();
    if cleaned.is_empty() { "file".into() } else { cleaned }
}

/// `name`, or `2-name`, `3-name` if already used in the zip.
fn unique_name(name: &str, used: &mut Vec<String>) -> String {
    let mut candidate = name.to_string();
    let mut n = 2;
    while used.contains(&candidate) {
        candidate = format!("{n}-{name}");
        n += 1;
    }
    used.push(candidate.clone());
    candidate
}
