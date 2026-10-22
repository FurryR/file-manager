use std::io;
use std::os::fd::OwnedFd;

use hub_client::filehub::{
    self, hub_request::Op, hub_response, ExecRequest, GetAiConfigRequest, GlobRequest, HubRequest,
    ListTargetsRequest, ReadFileRequest, SetAiSelectionRequest, WaitExecRequest, WriteFileRequest,
};

/// A control connection to the file-manager hub, with the caller's container.
pub struct Hub {
    connection: hub_client::Connection,
    next_id: u32,
    pub caller: String,
}

pub struct ExecStream {
    pub stdout: OwnedFd,
    pub stderr: OwnedFd,
    pub exec_id: u64,
}

#[derive(Clone)]
pub struct Target {
    pub name: String,
    pub running: bool,
    pub elevated: bool,
}

impl Hub {
    /// Connect and perform the Hello handshake using `$HUB_SOCKET`.
    pub async fn connect() -> io::Result<Self> {
        let (connection, root) = hub_client::Connection::connect_from_env().await?;
        match root.container_id.as_str() {
            "host" | "app" => {}
            id if id.starts_with("container-") && id.len() > "container-".len() => {}
            _ => return Err(io::Error::other("hub returned an invalid caller context")),
        }
        Ok(Self {
            connection,
            next_id: 1,
            caller: root.container_id,
        })
    }

    async fn request(&mut self, op: Op) -> Result<filehub::HubResponse, String> {
        let (response, fds) = self.request_with_fds(op).await?;
        // Unexpected descriptors are closed when this vector drops.
        drop(fds);
        Ok(response)
    }

    async fn request_with_fds(
        &mut self,
        op: Op,
    ) -> Result<(filehub::HubResponse, Vec<OwnedFd>), String> {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);
        if self.next_id == 0 {
            self.next_id = 1;
        }
        let (response, fds) = self
            .connection
            .round_trip_with_fds(HubRequest { id, op: Some(op) }, &[])
            .await
            .map_err(|error| error.to_string())?;
        if !response.ok {
            return Err(if response.error.is_empty() {
                "hub request failed".to_owned()
            } else {
                response.error
            });
        }
        Ok((response, fds))
    }

    pub async fn ai_config(&mut self) -> Result<filehub::AiConfigResponse, String> {
        let response = self.request(Op::GetAiConfig(GetAiConfigRequest {})).await?;
        match response.result {
            Some(hub_response::Result::AiConfig(config)) => Ok(config),
            _ => Err("hub returned no AI configuration".to_owned()),
        }
    }

    pub async fn set_ai_selection(&mut self, model: &str, effort: &str) -> Result<(), String> {
        self.request(Op::SetAiSelection(SetAiSelectionRequest {
            model: model.to_owned(),
            effort: effort.to_owned(),
        }))
        .await?;
        Ok(())
    }

    pub async fn targets(&mut self) -> Result<Vec<Target>, String> {
        let response = self.request(Op::ListTargets(ListTargetsRequest {})).await?;
        match response.result {
            Some(hub_response::Result::Targets(targets)) => Ok(targets
                .targets
                .into_iter()
                .map(|target| Target {
                    name: target.name,
                    running: target.running,
                    elevated: target.elevated,
                })
                .collect()),
            _ => Err("hub returned no target list".to_owned()),
        }
    }

    pub async fn read(
        &mut self,
        container_id: &str,
        path: &str,
        offset: u64,
        length: u32,
    ) -> Result<filehub::ReadFileResponse, String> {
        let response = self
            .request(Op::ReadFile(ReadFileRequest {
                path: path.to_owned(),
                offset,
                length,
                container_id: container_id.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::ReadFile(read)) => Ok(read),
            _ => Err("hub returned no read result".to_owned()),
        }
    }

    pub async fn write(
        &mut self,
        container_id: &str,
        path: &str,
        data: Vec<u8>,
    ) -> Result<u64, String> {
        let response = self
            .request(Op::WriteFile(WriteFileRequest {
                path: path.to_owned(),
                data,
                container_id: container_id.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::WriteFile(write)) => Ok(write.bytes_written),
            _ => Err("hub returned no write result".to_owned()),
        }
    }

    pub async fn glob(
        &mut self,
        container_id: &str,
        pattern: &str,
        path: &str,
        max_results: u32,
    ) -> Result<filehub::GlobResponse, String> {
        let response = self
            .request(Op::Glob(GlobRequest {
                pattern: pattern.to_owned(),
                path: path.to_owned(),
                max_results,
                container_id: container_id.to_owned(),
            }))
            .await?;
        match response.result {
            Some(hub_response::Result::Glob(glob)) => Ok(glob),
            _ => Err("hub returned no glob result".to_owned()),
        }
    }

    pub async fn exec(
        &mut self,
        container_id: &str,
        command: &str,
        cwd: &str,
    ) -> Result<ExecStream, String> {
        let (response, fds) = self
            .request_with_fds(Op::Exec(ExecRequest {
                command: "sh".to_owned(),
                args: vec!["-c".to_owned(), command.to_owned()],
                env: Default::default(),
                cwd: cwd.to_owned(),
                user: None,
                term: String::new(),
                rows: 0,
                cols: 0,
                nice: None,
                container_id: container_id.to_owned(),
                pipes: true,
            }))
            .await?;
        let exec_id = match response.result {
            Some(hub_response::Result::Exec(exec)) => exec.exec_id,
            _ => return Err("hub returned no exec result".to_owned()),
        };
        if fds.len() != 2 {
            return Err(format!(
                "hub returned {} exec descriptors, expected stdout and stderr",
                fds.len()
            ));
        }
        let mut fds = fds.into_iter();
        Ok(ExecStream {
            stdout: fds.next().expect("checked two descriptors"),
            stderr: fds.next().expect("checked two descriptors"),
            exec_id,
        })
    }

    pub async fn wait_exec(&mut self, exec_id: u64) -> Result<i32, String> {
        let response = self
            .request(Op::WaitExec(WaitExecRequest { exec_id }))
            .await?;
        match response.result {
            Some(hub_response::Result::Wait(wait)) => Ok(wait.exit_code),
            _ => Err("hub returned no wait result".to_owned()),
        }
    }
}
