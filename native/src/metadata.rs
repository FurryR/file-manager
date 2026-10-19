//! Preserve metadata before a staged same-directory replacement is published.
use std::{
    collections::BTreeMap,
    ffi::CString,
    fs::OpenOptions,
    io, mem,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
        unix::{ffi::OsStrExt, fs::OpenOptionsExt},
    },
    path::Path,
};

pub fn prepare_replacement(original: &str, replacement: &str) -> io::Result<()> {
    let original_path = crate::list::maybe_android_data_path(original);
    let replacement_path = crate::list::maybe_android_data_path(replacement);
    let original = Path::new(&original_path);
    let replacement = Path::new(&replacement_path);
    if !original.is_absolute() || !replacement.is_absolute() {
        return Err(io::Error::other("replacement paths must be absolute"));
    }
    let original_parent = open_parent(original)?;
    let replacement_parent = open_parent(replacement)?;
    let source_directory = stat(original_parent.as_raw_fd())?;
    let destination_directory = stat(replacement_parent.as_raw_fd())?;
    if source_directory.st_dev != destination_directory.st_dev
        || source_directory.st_ino != destination_directory.st_ino
    {
        return Err(io::Error::other(
            "replacement must be in the same directory",
        ));
    }
    // Open both entries relative to the very same pinned directory. Parent
    // renames cannot redirect either lookup between the identity check and open.
    let source = open_entry(original_parent.as_raw_fd(), original)?;
    let target = open_entry(original_parent.as_raw_fd(), replacement)?;
    let old = stat(source.as_raw_fd())?;
    let new = stat(target.as_raw_fd())?;
    if old.st_mode & libc::S_IFMT != libc::S_IFREG || new.st_mode & libc::S_IFMT != libc::S_IFREG {
        return Err(io::Error::other(
            "replacement metadata requires two regular files",
        ));
    }
    if old.st_dev == new.st_dev && old.st_ino == new.st_ino {
        return Err(io::Error::other("replacement must have a different inode"));
    }
    if new.st_nlink != 1 {
        return Err(io::Error::other(
            "staging file must not have additional hard links",
        ));
    }

    // Obtain all original attributes before modifying the staging inode. A
    // failed read is never interpreted as permission to discard labels/ACLs.
    let original_attributes = attributes(source.as_raw_fd())?;
    let target_attributes = attributes(target.as_raw_fd())?;
    if original_attributes.is_some() != target_attributes.is_some() {
        return Err(io::Error::other(
            "source and replacement disagree about extended attribute support",
        ));
    }
    if old.st_uid != new.st_uid || old.st_gid != new.st_gid {
        if unsafe { libc::fchown(target.as_raw_fd(), old.st_uid, old.st_gid) } != 0 {
            return Err(io::Error::last_os_error());
        }
    }
    let mode = old.st_mode & 0o7777;
    if let Some(original_attributes) = original_attributes.as_ref() {
        // Re-read after chown: the kernel may have cleared capabilities. Copy
        // attributes before making the stage read-only; user.* xattr updates
        // need write access even when the caller owns the inode.
        let current = attributes(target.as_raw_fd())?
            .ok_or_else(|| io::Error::other("extended attributes became unavailable"))?;
        for name in current
            .keys()
            .filter(|name| !original_attributes.contains_key(*name))
        {
            let name = CString::new(name.as_slice()).map_err(io::Error::other)?;
            if unsafe { libc::fremovexattr(target.as_raw_fd(), name.as_ptr()) } != 0 {
                return Err(io::Error::last_os_error());
            }
        }
        for (name, value) in original_attributes {
            if current.get(name) == Some(value) {
                continue;
            }
            let name = CString::new(name.as_slice()).map_err(io::Error::other)?;
            if unsafe {
                libc::fsetxattr(
                    target.as_raw_fd(),
                    name.as_ptr(),
                    value.as_ptr().cast(),
                    value.len(),
                    0,
                )
            } != 0
            {
                return Err(io::Error::last_os_error());
            }
        }
    }
    // chown and ACL assignment can change mode. Restore it only after all
    // attribute changes, then verify both representations agree with source.
    let current = stat(target.as_raw_fd())?;
    if current.st_mode & 0o7777 != mode && unsafe { libc::fchmod(target.as_raw_fd(), mode) } != 0 {
        return Err(io::Error::last_os_error());
    }
    if let Some(original_attributes) = original_attributes.as_ref() {
        if attributes(target.as_raw_fd())?.as_ref() != Some(original_attributes) {
            return Err(io::Error::other(
                "replacement extended attributes could not be preserved",
            ));
        }
    }
    let final_stat = stat(target.as_raw_fd())?;
    if final_stat.st_uid != old.st_uid
        || final_stat.st_gid != old.st_gid
        || final_stat.st_mode & 0o7777 != mode
    {
        return Err(io::Error::other(
            "replacement owner, group or permissions could not be preserved",
        ));
    }
    Ok(())
}
fn open_parent(path: &Path) -> io::Result<std::fs::File> {
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::other("replacement path has no parent"))?;
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC)
        .open(parent)
}
fn open_entry(parent: RawFd, path: &Path) -> io::Result<OwnedFd> {
    let name = path
        .file_name()
        .ok_or_else(|| io::Error::other("replacement path has no filename"))?;
    let name = CString::new(name.as_bytes()).map_err(io::Error::other)?;
    let fd = unsafe {
        libc::openat(
            parent,
            name.as_ptr(),
            libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}
fn stat(fd: RawFd) -> io::Result<libc::stat> {
    let mut value = unsafe { mem::zeroed() };
    if unsafe { libc::fstat(fd, &mut value) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(value)
}
fn attributes(fd: RawFd) -> io::Result<Option<BTreeMap<Vec<u8>, Vec<u8>>>> {
    let names = match buffer(|ptr, length| unsafe { libc::flistxattr(fd, ptr.cast(), length) }) {
        Ok(names) => names,
        Err(error)
            if error.raw_os_error() == Some(libc::ENOTSUP)
                || error.raw_os_error() == Some(libc::EOPNOTSUPP) =>
        {
            return Ok(None)
        }
        Err(error) => return Err(error),
    };
    let mut result = BTreeMap::new();
    for name in names
        .split(|byte| *byte == 0)
        .filter(|name| !name.is_empty())
    {
        let key = CString::new(name).map_err(io::Error::other)?;
        let value =
            buffer(|ptr, length| unsafe { libc::fgetxattr(fd, key.as_ptr(), ptr.cast(), length) })?;
        result.insert(name.to_vec(), value);
    }
    Ok(Some(result))
}
fn buffer(mut read: impl FnMut(*mut u8, usize) -> isize) -> io::Result<Vec<u8>> {
    for _ in 0..3 {
        let size = read(std::ptr::null_mut(), 0);
        if size < 0 {
            return Err(io::Error::last_os_error());
        }
        if size > 1024 * 1024 {
            return Err(io::Error::other("extended attribute data exceeds one MiB"));
        }
        let mut bytes = vec![0u8; size as usize];
        if size == 0 {
            return Ok(bytes);
        }
        let count = read(bytes.as_mut_ptr(), bytes.len());
        if count >= 0 {
            bytes.truncate(count as usize);
            return Ok(bytes);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::ERANGE) {
            return Err(error);
        }
    }
    Err(io::Error::other(
        "extended attributes changed repeatedly while reading",
    ))
}
