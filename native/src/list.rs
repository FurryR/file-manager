use std::ffi::CString;
use std::io;
use std::mem;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::UNIX_EPOCH;
use tokio::fs;

use crate::proto::{Account, Accounts, FileEntry, Stat};

/// True when the daemon runs inside a container (chroot/proot). Container paths
/// are real paths in the container, so the Android `/Android/data` UID trick
/// must not be applied.
static CONTAINER_MODE: AtomicBool = AtomicBool::new(false);

pub fn set_container_mode(enabled: bool) {
    CONTAINER_MODE.store(enabled, Ordering::Relaxed);
}

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
        let uid = metadata.uid();
        let gid = metadata.gid();
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
            uid,
            gid,
            owner: user_name(uid),
            group: group_name(gid),
        });
    }
    Ok(result)
}

pub fn stat_path(path: &str) -> io::Result<Stat> {
    let cpath =
        CString::new(path).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let mut stat = mem::MaybeUninit::<libc::statvfs>::uninit();
    let result = unsafe { libc::statvfs(cpath.as_ptr(), stat.as_mut_ptr()) };
    let (usable_bytes, total_bytes) = if result == 0 {
        let stat = unsafe { stat.assume_init() };
        (
            (stat.f_bavail as i64) * (stat.f_frsize as i64),
            (stat.f_blocks as i64) * (stat.f_frsize as i64),
        )
    } else {
        (0, 0)
    };
    let p = Path::new(path);
    let exists = p.exists();
    let is_directory = if exists { p.is_dir() } else { false };
    let modified_at_ms = p
        .metadata()
        .ok()
        .and_then(|metadata| metadata.modified().ok())
        .and_then(|modified| modified.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0);
    // Creation availability follows listing; actual mutations remain authoritative.
    let can_list = std::fs::read_dir(path).is_ok();
    Ok(Stat {
        usable_bytes,
        total_bytes,
        exists,
        is_directory,
        can_create_children: can_list,
        modified_at_ms,
    })
}

pub fn maybe_android_data_path(path: &str) -> String {
    if CONTAINER_MODE.load(Ordering::Relaxed) {
        return path.to_string();
    }
    if unsafe { libc::getuid() } == 0 {
        return path.to_string();
    }
    path.replace("/Android/data", "/Android/\u{200b}data")
}

/// Change the permission bits of [path], in the same way a recursive `chmod`
/// would, without following symlinks.
pub fn set_mode(path: &str, mode: u32, apply_files: bool, apply_dirs: bool) -> io::Result<()> {
    let target = maybe_android_data_path(path);
    set_mode_one(Path::new(&target), mode)?;
    walk_children(Path::new(&target), apply_files, apply_dirs, &|child, _| {
        set_mode_one(child, mode)
    })
}

fn set_mode_one(path: &Path, mode: u32) -> io::Result<()> {
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
}

/// Change owner and group; -1 keeps the current id. Mirrors `chown -R`.
pub fn set_owner(
    path: &str,
    uid: i32,
    gid: i32,
    apply_files: bool,
    apply_dirs: bool,
) -> io::Result<()> {
    let target = maybe_android_data_path(path);
    set_owner_one(Path::new(&target), uid, gid)?;
    walk_children(Path::new(&target), apply_files, apply_dirs, &|child, _| {
        set_owner_one(child, uid, gid)
    })
}

fn set_owner_one(path: &Path, uid: i32, gid: i32) -> io::Result<()> {
    let cpath = CString::new(path.as_os_str().as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let rc = unsafe { libc::chown(cpath.as_ptr(), uid as libc::uid_t, gid as libc::gid_t) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Set access and modification time; the UI only exposes the latter, so both
/// carry the requested value. Mirrors `touch -d`.
pub fn set_times(
    path: &str,
    modified_at_ms: i64,
    apply_files: bool,
    apply_dirs: bool,
) -> io::Result<()> {
    let target = maybe_android_data_path(path);
    set_times_one(Path::new(&target), modified_at_ms)?;
    walk_children(Path::new(&target), apply_files, apply_dirs, &|child, _| {
        set_times_one(child, modified_at_ms)
    })
}

fn set_times_one(path: &Path, modified_at_ms: i64) -> io::Result<()> {
    let seconds = modified_at_ms.div_euclid(1000);
    let nanos = modified_at_ms.rem_euclid(1000) * 1_000_000;
    let times = [
        libc::timespec { tv_sec: seconds as libc::time_t, tv_nsec: nanos as libc::c_long },
        libc::timespec { tv_sec: seconds as libc::time_t, tv_nsec: nanos as libc::c_long },
    ];
    let cpath = CString::new(path.as_os_str().as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let rc = unsafe { libc::utimensat(libc::AT_FDCWD, cpath.as_ptr(), times.as_ptr(), 0) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Visit every descendant that the recursive operation is asked to touch.
/// Symlinks are skipped so a link target outside the tree is never changed.
fn walk_children(
    root: &Path,
    apply_files: bool,
    apply_dirs: bool,
    apply: &dyn Fn(&Path, bool) -> io::Result<()>,
) -> io::Result<()> {
    if !root.is_dir() || (!apply_files && !apply_dirs) {
        return Ok(());
    }
    for entry in std::fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        let metadata = std::fs::symlink_metadata(&path)?;
        if metadata.file_type().is_symlink() {
            continue;
        }
        let is_dir = metadata.is_dir();
        if is_dir {
            if apply_dirs {
                apply(&path, true)?;
            }
            walk_children(&path, apply_files, apply_dirs, apply)?;
        } else if apply_files {
            apply(&path, false)?;
        }
    }
    Ok(())
}

fn user_name(uid: u32) -> String {
    unsafe {
        let mut pwd: libc::passwd = mem::zeroed();
        let mut result: *mut libc::passwd = std::ptr::null_mut();
        let mut buffer = vec![0 as libc::c_char; 1024];
        let rc = libc::getpwuid_r(
            uid,
            &mut pwd,
            buffer.as_mut_ptr(),
            buffer.len() as libc::size_t,
            &mut result,
        );
        if rc == 0 && !result.is_null() && !pwd.pw_name.is_null() {
            return std::ffi::CStr::from_ptr(pwd.pw_name)
                .to_string_lossy()
                .into_owned();
        }
    }
    uid.to_string()
}

fn group_name(gid: u32) -> String {
    unsafe {
        let mut grp: libc::group = mem::zeroed();
        let mut result: *mut libc::group = std::ptr::null_mut();
        let mut buffer = vec![0 as libc::c_char; 1024];
        let rc = libc::getgrgid_r(
            gid,
            &mut grp,
            buffer.as_mut_ptr(),
            buffer.len() as libc::size_t,
            &mut result,
        );
        if rc == 0 && !result.is_null() && !grp.gr_name.is_null() {
            return std::ffi::CStr::from_ptr(grp.gr_name)
                .to_string_lossy()
                .into_owned();
        }
    }
    gid.to_string()
}

/// Every user and group the daemon can see. Android hosts usually expose only
/// `/system/etc/passwd`; containers carry the full `/etc/passwd` and
/// `/etc/group`, so the same request serves both.
pub fn accounts() -> Accounts {
    Accounts {
        users: read_accounts("/etc/passwd", "/system/etc/passwd"),
        groups: read_accounts("/etc/group", "/system/etc/group"),
    }
}

fn read_accounts(primary: &str, fallback: &str) -> Vec<Account> {
    let raw = std::fs::read_to_string(primary)
        .or_else(|_| std::fs::read_to_string(fallback))
        .unwrap_or_default();
    raw.lines()
        .filter_map(|line| {
            let mut fields = line.split(':');
            let name = fields.next()?.trim();
            let _password = fields.next()?;
            let id = fields.next()?.trim().parse::<u32>().ok()?;
            if name.is_empty() {
                return None;
            }
            Some(Account {
                id,
                name: name.to_string(),
                description: String::new(),
                icon: String::new(),
            })
        })
        .collect()
}
