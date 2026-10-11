use std::collections::HashMap;
use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, SeekFrom};

use crate::proto::{DupHandleResponse, HandleMode, OpenHandleResponse};

static NEXT_HANDLE_ID: AtomicU64 = AtomicU64::new(1);

pub struct HandleState {
    pub path: String,
    pub mode: HandleMode,
    pub size: u64,
    file: File,
}

pub struct HandleTable {
    handles: HashMap<u64, HandleState>,
}

impl HandleTable {
    pub fn new() -> Self {
        HandleTable { handles: HashMap::new() }
    }

    pub async fn open(&mut self, path: &str, mode: HandleMode) -> io::Result<OpenHandleResponse> {
        let path = crate::list::maybe_android_data_path(path);
        let p = Path::new(&path);

        let file = match mode {
            HandleMode::Read => File::open(&p).await?,
            HandleMode::Write => {
                if let Some(parent) = p.parent() {
                    tokio::fs::create_dir_all(parent).await?;
                }
                File::create(&p).await?
            }
            HandleMode::ReadWrite => {
                if let Some(parent) = p.parent() {
                    tokio::fs::create_dir_all(parent).await?;
                }
                let mut opts = tokio::fs::File::options();
                opts.read(true).write(true).create(true);
                opts.open(&p).await?
            }
        };

        let metadata = file.metadata().await?;
        let size = metadata.len();
        let handle_id = NEXT_HANDLE_ID.fetch_add(1, Ordering::SeqCst);

        self.handles.insert(handle_id, HandleState { path: path.clone(), mode, size, file });
        Ok(OpenHandleResponse { handle_id, size })
    }

    pub async fn read(&mut self, handle_id: u64, offset: u64, length: u32) -> io::Result<(Vec<u8>, bool)> {
        let state = self.handles.get_mut(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;

        state.file.seek(SeekFrom::Start(offset)).await?;
        let length = length as usize;
        let max_read = length.min((state.size.saturating_sub(offset)) as usize);
        if max_read == 0 {
            return Ok((Vec::new(), true));
        }
        let mut buf = vec![0u8; max_read];
        let n = state.file.read(&mut buf).await?;
        buf.truncate(n);
        let eof = (offset + n as u64) >= state.size;
        Ok((buf, eof))
    }

    pub async fn write(&mut self, handle_id: u64, offset: u64, data: &[u8]) -> io::Result<()> {
        let state = self.handles.get_mut(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;

        state.file.seek(SeekFrom::Start(offset)).await?;
        state.file.write_all(data).await?;
        let new_end = offset + data.len() as u64;
        if new_end > state.size { state.size = new_end; }
        Ok(())
    }

    pub async fn seek(&mut self, handle_id: u64, position: u64) -> io::Result<()> {
        let state = self.handles.get_mut(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;
        state.file.seek(SeekFrom::Start(position)).await?;
        Ok(())
    }

    pub async fn dup(&mut self, handle_id: u64) -> io::Result<DupHandleResponse> {
        let state = self.handles.get(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;

        let path = state.path.clone();
        let mode = state.mode;
        let size = state.size;

        let file = match mode {
            HandleMode::Read => File::open(&path).await?,
            HandleMode::Write => {
                let mut opts = tokio::fs::File::options();
                opts.write(true);
                opts.open(&path).await?
            }
            HandleMode::ReadWrite => {
                let mut opts = tokio::fs::File::options();
                opts.read(true).write(true);
                opts.open(&path).await?
            }
        };

        let new_id = NEXT_HANDLE_ID.fetch_add(1, Ordering::SeqCst);
        self.handles.insert(new_id, HandleState { path, mode, size, file });
        Ok(DupHandleResponse { handle_id: new_id, size })
    }

    pub async fn truncate(&mut self, handle_id: u64, new_size: u64) -> io::Result<()> {
        let state = self.handles.get_mut(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;
        state.file.set_len(new_size).await?;
        state.size = new_size;
        Ok(())
    }

    pub async fn close(&mut self, handle_id: u64) -> io::Result<()> {
        if self.handles.remove(&handle_id).is_some() { Ok(()) }
        else { Err(io::Error::new(io::ErrorKind::NotFound, "handle not found")) }
    }

    pub fn duplicate_fd(&self, handle_id: u64) -> io::Result<RawFd> {
        let state = self.handles.get(&handle_id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "handle not found"))?;
        let fd = unsafe { libc::dup(state.file.as_raw_fd()) };
        if fd < 0 { Err(io::Error::last_os_error()) } else { Ok(fd) }
    }

    pub fn get_size(&self, handle_id: u64) -> Option<u64> {
        self.handles.get(&handle_id).map(|s| s.size)
    }
}

#[cfg(test)]
mod tests {
    use super::HandleTable;
    use crate::proto::HandleMode;
    use std::io::Read;
    use std::os::fd::FromRawFd;

    #[tokio::test]
    async fn duplicated_handle_fd_reads_file_contents() {
        let path = std::env::temp_dir().join(format!(
            "file-daemon-handle-test-{}",
            std::process::id(),
        ));
        std::fs::write(&path, b"handle data").unwrap();

        let mut handles = HandleTable::new();
        let handle = handles.open(path.to_str().unwrap(), HandleMode::Read).await.unwrap();
        let fd = handles.duplicate_fd(handle.handle_id).unwrap();
        let mut file = unsafe { std::fs::File::from_raw_fd(fd) };
        let mut contents = Vec::new();
        file.read_to_end(&mut contents).unwrap();

        assert_eq!(contents, b"handle data");
        handles.close(handle.handle_id).await.unwrap();
        std::fs::remove_file(path).unwrap();
    }
}
