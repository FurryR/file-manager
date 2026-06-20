use std::ffi::CString;
use std::io;
use std::mem;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::time::UNIX_EPOCH;
use tokio::fs;

use crate::proto::{FileEntry, Stat};

pub async fn list_files(path: &str) -> io::Result<Vec<FileEntry>> {
    let list_path = maybe_android_data_path(path);
    let mut entries = fs::read_dir(Path::new(&list_path)).await?;
    let mut result = Vec::new();
    while let Some(entry) = entries.next_entry().await? {
        let metadata = entry.metadata().await?;
        let is_symlink = metadata.file_type().is_symlink();
        let is_directory = if is_symlink {
            tokio::fs::metadata(entry.path())
                .await
                .map(|m| m.is_dir())
                .unwrap_or(false)
        } else {
            metadata.is_dir()
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        let display_path = if list_path != path {
            format!("{}/{}", path.trim_end_matches('/'), name)
        } else {
            entry.path().to_string_lossy().into_owned()
        };
        let modified_at_ms = metadata
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis() as i64)
            .unwrap_or(0);
        let mode = metadata.permissions().mode();
        let size = metadata.len();
        let real_path = if is_symlink {
            std::fs::read_link(entry.path())
                .map(|p| p.to_string_lossy().into_owned())
                .unwrap_or_else(|_| display_path.clone())
        } else {
            display_path.clone()
        };
        result.push(FileEntry {
            name,
            path: display_path,
            is_directory,
            modified_at_ms,
            is_symlink,
            real_path,
            mode,
            size: size as i64,
        });
    }
    Ok(result)
}

pub fn stat_path(path: &str) -> io::Result<Stat> {
    let cpath =
        CString::new(path).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let mut stat = mem::MaybeUninit::<libc::statvfs>::uninit();
    let result = unsafe { libc::statvfs(cpath.as_ptr(), stat.as_mut_ptr()) };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    let stat = unsafe { stat.assume_init() };
    let p = Path::new(path);
    let exists = p.exists();
    let is_directory = if exists { p.is_dir() } else { false };
    Ok(Stat {
        usable_bytes: (stat.f_bavail as i64) * (stat.f_frsize as i64),
        total_bytes: (stat.f_blocks as i64) * (stat.f_frsize as i64),
        exists,
        is_directory,
    })
}

pub fn maybe_android_data_path(path: &str) -> String {
    if unsafe { libc::getuid() } == 0 {
        return path.to_string();
    }
    path.replace("/Android/data", "/Android/\u{200b}data")
}
