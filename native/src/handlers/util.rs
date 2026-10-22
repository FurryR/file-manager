use std::io;
use std::os::fd::OwnedFd;

use crate::proto::Response;

pub(super) fn close_unexpected_fd(fd: Option<OwnedFd>) {
    drop(fd);
}

pub(super) fn ok_response(id: u64) -> Response {
    Response {
        id,
        ok: true,
        ..Response::default()
    }
}

pub(super) fn error_response(id: u64, error: io::Error) -> Response {
    Response {
        id,
        ok: false,
        error: error.to_string(),
        ..Response::default()
    }
}
