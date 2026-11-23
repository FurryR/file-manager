use tokio::fs::File;

use crate::copy;
use crate::list;
use crate::proto::{
    AccountsRequest, Response, SetModeRequest, SetOwnerRequest, SetTimesRequest,
};

use super::util::{error_response, ok_response};

pub(super) async fn list(req_id: u64, path: String) -> Response {
    match list::list_files(&path).await {
        Ok(entries) => Response {
            id: req_id,
            ok: true,
            entries,
            ..Response::default()
        },
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn create_file(req_id: u64, path: String) -> Response {
    match File::create(list::maybe_android_data_path(&path)).await {
        Ok(_) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn stat(req_id: u64, path: String) -> Response {
    match list::stat_path(&path) {
        Ok(stat) => Response {
            id: req_id,
            ok: true,
            stat: Some(stat),
            ..Response::default()
        },
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn rename(req_id: u64, path: String, new_name: String) -> Response {
    let target_path = std::path::Path::new(&list::maybe_android_data_path(&path))
        .parent()
        .unwrap_or(std::path::Path::new("/"))
        .join(&new_name);
    match tokio::fs::rename(list::maybe_android_data_path(&path), &target_path).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn delete(req_id: u64, path: String) -> Response {
    match copy::delete_recursive(&list::maybe_android_data_path(&path)).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn create_dir(req_id: u64, path: String) -> Response {
    match tokio::fs::create_dir(list::maybe_android_data_path(&path)).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn set_mode(req_id: u64, req: SetModeRequest) -> Response {
    match list::set_mode(&req.path, req.mode, req.apply_files, req.apply_dirs) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn set_owner(req_id: u64, req: SetOwnerRequest) -> Response {
    match list::set_owner(&req.path, req.uid, req.gid, req.apply_files, req.apply_dirs) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn set_times(req_id: u64, req: SetTimesRequest) -> Response {
    match list::set_times(
        &req.path,
        req.modified_at_ms,
        req.apply_files,
        req.apply_dirs,
    ) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn accounts(req_id: u64, _req: AccountsRequest) -> Response {
    Response {
        id: req_id,
        ok: true,
        accounts: Some(list::accounts()),
        ..Response::default()
    }
}
