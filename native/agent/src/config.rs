use std::io;

use crate::hub::Hub;

pub struct Config {
    pub api_key: String,
    pub base_url: String,
    pub model: String,
    pub effort: String,
    pub provider: String,
}

pub async fn load() -> io::Result<(Config, Hub)> {
    let mut hub = Hub::connect().await?;
    let config = hub.ai_config().await.map_err(|message| {
        io::Error::other(if message.is_empty() {
            "AI configuration is unavailable; check the app's AI settings".to_owned()
        } else {
            message
        })
    })?;
    Ok((Config::from(config), hub))
}

impl From<hub_client::filehub::AiConfigResponse> for Config {
    fn from(config: hub_client::filehub::AiConfigResponse) -> Self {
        Self {
            api_key: config.api_key,
            base_url: config.base_url,
            model: config.model,
            effort: config.effort,
            provider: config.provider,
        }
    }
}
