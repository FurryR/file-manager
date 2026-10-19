use std::io;
use std::os::fd::AsRawFd;

use hub_client::filehub::{
    hub_request::Op, hub_response, AiConfigResponse, GetAiConfigRequest, HubRequest,
};

pub struct Config {
    pub api_key: String,
    pub base_url: String,
    pub model: String,
}

pub struct Context {
    pub container_id: String,
}

pub fn load() -> io::Result<(Config, Context)> {
    let (socket, context) = hub_client::connect_from_env()?;
    match context.container_id.as_str() {
        "host" | "app" => {}
        id if id.starts_with("container-") && id.len() > "container-".len() => {}
        _ => return Err(io::Error::other("hub returned an invalid caller context")),
    }
    let response = hub_client::round_trip(
        socket.as_raw_fd(),
        HubRequest {
            id: 1,
            op: Some(Op::GetAiConfig(GetAiConfigRequest {})),
        },
    )?;
    if !response.ok {
        return Err(io::Error::other(
            "AI configuration is unavailable; check the app's AI settings",
        ));
    }
    let config = match response.result {
        Some(hub_response::Result::AiConfig(config)) => config,
        _ => return Err(io::Error::other("hub returned no AI configuration")),
    };
    Ok((
        Config::from(config),
        Context {
            container_id: context.container_id,
        },
    ))
}

impl From<AiConfigResponse> for Config {
    fn from(config: AiConfigResponse) -> Self {
        Self {
            api_key: config.api_key,
            base_url: config.base_url,
            model: config.model,
        }
    }
}
