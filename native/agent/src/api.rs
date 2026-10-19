use std::io;
use std::time::Duration;

use serde::Deserialize;

use crate::config::Config;
use crate::model::Message;

const MAX_RESPONSE: usize = 4 * 1024 * 1024;

#[derive(Clone)]
pub struct Api {
    client: reqwest::Client,
    url: reqwest::Url,
    key: String,
    model: String,
}

#[derive(Deserialize)]
struct Completion {
    choices: Vec<Choice>,
}

#[derive(Deserialize)]
struct Choice {
    message: Message,
}

impl Api {
    pub fn new(config: Config) -> io::Result<Self> {
        if config.model.trim().is_empty() {
            return Err(io::Error::other(
                "set a model in the app's AI settings first",
            ));
        }
        let url = endpoint(&config.base_url)?;
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(20))
            .timeout(Duration::from_secs(180))
            .build()
            .map_err(|_| io::Error::other("could not initialize the HTTP client"))?;
        Ok(Self {
            client,
            url,
            key: config.api_key,
            model: config.model,
        })
    }

    pub fn model(&self) -> &str {
        &self.model
    }

    pub async fn complete(&self, messages: Vec<Message>) -> Result<String, String> {
        let mut request = self.client.post(self.url.clone()).json(&serde_json::json!({
            "model": &self.model,
            "messages": messages,
            "stream": false,
        }));
        if !self.key.is_empty() {
            request = request.bearer_auth(&self.key);
        }
        let mut response = request.send().await.map_err(|error| {
            if error.is_timeout() {
                "Request timed out; try again."
            } else {
                "Connection failed; check the API address, network and TLS certificate."
            }
            .to_owned()
        })?;
        if !response.status().is_success() {
            return Err(format!(
                "API returned HTTP {}; check the model, API key and provider limits.",
                response.status().as_u16()
            ));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_RESPONSE as u64)
        {
            return Err("API response is too large.".to_owned());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| "Could not read the API response.".to_owned())?
        {
            if bytes.len() + chunk.len() > MAX_RESPONSE {
                return Err("API response is too large.".to_owned());
            }
            bytes.extend_from_slice(&chunk);
        }
        let reply: Completion = serde_json::from_slice(&bytes)
            .map_err(|_| "API returned an invalid Chat Completions response.".to_owned())?;
        reply
            .choices
            .into_iter()
            .next()
            .map(|choice| choice.message.content)
            .filter(|content| !content.trim().is_empty())
            .ok_or_else(|| "API returned no assistant message.".to_owned())
    }
}

fn endpoint(base: &str) -> io::Result<reqwest::Url> {
    let base = base.trim().trim_end_matches('/');
    let address = if base.ends_with("/chat/completions") {
        base.to_owned()
    } else {
        format!("{base}/chat/completions")
    };
    let url = reqwest::Url::parse(&address)
        .map_err(|_| io::Error::other("invalid API base URL in AI settings"))?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(io::Error::other(
            "AI base URL must be an HTTP(S) URL without credentials, query or fragment",
        ));
    }
    Ok(url)
}
