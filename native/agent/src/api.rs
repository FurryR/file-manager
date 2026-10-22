use std::io;
use std::time::Duration;

use serde::Deserialize;
use tokio::sync::mpsc::UnboundedSender;

use crate::config::Config;
use crate::model::{AgentEvent, FunctionCall, Message, ToolCall, Usage};

const MAX_RESPONSE: usize = 8 * 1024 * 1024;

#[derive(Clone)]
pub struct Api {
    client: reqwest::Client,
    url: reqwest::Url,
    pricing_urls: Vec<reqwest::Url>,
    models_url: Option<reqwest::Url>,
    key: String,
}

#[derive(Deserialize)]
struct Chunk {
    #[serde(default)]
    choices: Vec<Choice>,
    #[serde(default)]
    usage: Option<WireUsage>,
}

#[derive(Deserialize)]
struct WireUsage {
    #[serde(default)]
    prompt_tokens: u64,
    #[serde(default)]
    completion_tokens: u64,
}

#[derive(Deserialize)]
struct Choice {
    #[serde(default)]
    delta: Delta,
}

#[derive(Deserialize, Default)]
struct Delta {
    #[serde(default)]
    content: Option<String>,
    #[serde(default)]
    reasoning_content: Option<String>,
    #[serde(default)]
    reasoning: Option<String>,
    #[serde(default)]
    tool_calls: Option<Vec<DeltaToolCall>>,
}

#[derive(Deserialize)]
struct DeltaToolCall {
    #[serde(default)]
    index: Option<usize>,
    #[serde(default)]
    id: Option<String>,
    #[serde(default, rename = "type")]
    kind: Option<String>,
    #[serde(default)]
    function: Option<DeltaFunction>,
}

#[derive(Deserialize, Default)]
struct DeltaFunction {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    arguments: Option<String>,
}

#[derive(Default)]
struct CallBuilder {
    id: String,
    kind: String,
    name: String,
    arguments: String,
}

impl CallBuilder {
    fn build(self) -> Option<ToolCall> {
        if self.name.is_empty() {
            return None;
        }
        Some(ToolCall {
            id: self.id,
            kind: if self.kind.is_empty() {
                "function".to_owned()
            } else {
                self.kind
            },
            function: FunctionCall {
                name: self.name,
                arguments: self.arguments,
            },
        })
    }
}

impl Api {
    pub fn new(config: Config) -> io::Result<Self> {
        let url = endpoint(&config.base_url)?;
        let pricing_urls = pricing_endpoints(&url);
        let models_url = models_endpoint(&url);
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(20))
            .timeout(Duration::from_secs(600))
            .build()
            .map_err(|_| io::Error::other("could not initialize the HTTP client"))?;
        Ok(Self {
            client,
            url,
            pricing_urls,
            models_url,
            key: config.api_key,
        })
    }

    pub async fn catalog(&self) -> crate::model_info::ModelCatalog {
        crate::model_info::fetch(
            &self.client,
            &self.pricing_urls,
            self.models_url.as_ref(),
            &self.key,
        )
        .await
    }

    pub async fn chat(
        &self,
        model: &str,
        effort: &str,
        messages: &[Message],
        tools: &serde_json::Value,
        events: &UnboundedSender<AgentEvent>,
    ) -> Result<Message, String> {
        let mut body = serde_json::json!({
            "model": model,
            "messages": messages,
            "stream": true,
            "stream_options": { "include_usage": true },
        });
        if !effort.is_empty() && effort != "auto" {
            body["reasoning_effort"] = serde_json::Value::String(effort.to_owned());
        }
        if let Some(tools) = tools.as_array().filter(|tools| !tools.is_empty()) {
            body["tools"] = serde_json::Value::Array(tools.clone());
            body["tool_choice"] = serde_json::json!("auto");
        }
        let mut request = self.client.post(self.url.clone()).json(&body);
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

        let mut buffer = String::new();
        let mut content = String::new();
        let mut calls: Vec<CallBuilder> = Vec::new();
        let mut received = 0usize;
        let mut finished = false;
        let mut tool_calling = false;
        let mut usage: Option<Usage> = None;

        while !finished {
            let Some(chunk) = response
                .chunk()
                .await
                .map_err(|_| "Could not read the API response.".to_owned())?
            else {
                break;
            };
            received += chunk.len();
            if received > MAX_RESPONSE {
                return Err("API response is too large.".to_owned());
            }
            buffer.push_str(&String::from_utf8_lossy(&chunk));

            while let Some(index) = buffer.find('\n') {
                let line = buffer[..index].trim_end_matches('\r').to_owned();
                buffer.drain(..=index);
                finished = apply_line(
                    &line,
                    &mut content,
                    &mut calls,
                    events,
                    &mut tool_calling,
                    &mut usage,
                )?;
                if finished {
                    break;
                }
            }
        }
        if !finished && !buffer.trim().is_empty() {
            let line = std::mem::take(&mut buffer);
            apply_line(
                line.trim_end_matches('\r'),
                &mut content,
                &mut calls,
                events,
                &mut tool_calling,
                &mut usage,
            )?;
        }

        if let Some(usage) = usage {
            let _ = events.send(AgentEvent::Usage(usage));
        }

        let tool_calls: Vec<ToolCall> = calls.into_iter().filter_map(CallBuilder::build).collect();
        let message = Message {
            role: "assistant".to_owned(),
            content: (!content.is_empty()).then_some(content),
            tool_calls: (!tool_calls.is_empty()).then_some(tool_calls),
            tool_call_id: None,
        };
        if message.content_text().trim().is_empty() && !message.has_tools() {
            return Err("API returned no assistant message.".to_owned());
        }
        Ok(message)
    }
}

fn apply_line(
    line: &str,
    content: &mut String,
    calls: &mut Vec<CallBuilder>,
    events: &UnboundedSender<AgentEvent>,
    tool_calling: &mut bool,
    usage: &mut Option<Usage>,
) -> Result<bool, String> {
    let Some(data) = line.strip_prefix("data:") else {
        return Ok(false);
    };
    let data = data.trim_start();
    if data.is_empty() {
        return Ok(false);
    }
    if data == "[DONE]" {
        return Ok(true);
    }
    let chunk: Chunk = serde_json::from_str(data)
        .map_err(|_| "API returned an invalid streaming Chat Completions response.".to_owned())?;
    if let Some(reported) = chunk.usage {
        *usage = Some(Usage {
            prompt_tokens: reported.prompt_tokens,
            completion_tokens: reported.completion_tokens,
        });
    }
    let Some(choice) = chunk.choices.into_iter().next() else {
        return Ok(false);
    };
    let delta = choice.delta;
    let reasoning = delta
        .reasoning_content
        .or(delta.reasoning)
        .filter(|value| !value.is_empty());
    if let Some(reasoning) = reasoning {
        let _ = events.send(AgentEvent::ReasoningDelta(reasoning));
    }
    if let Some(text) = delta.content.filter(|value| !value.is_empty()) {
        content.push_str(&text);
        let _ = events.send(AgentEvent::AnswerDelta(text));
    }
    let tool_calls = delta.tool_calls.unwrap_or_default();
    if !tool_calls.is_empty() && !*tool_calling {
        *tool_calling = true;
        let _ = events.send(AgentEvent::ToolCalling);
    }
    for call in tool_calls {
        let index = call.index.unwrap_or(calls.len());
        if calls.len() <= index {
            calls.resize_with(index + 1, CallBuilder::default);
        }
        let builder = &mut calls[index];
        if let Some(id) = call.id.filter(|value| !value.is_empty()) {
            builder.id = id;
        }
        if let Some(kind) = call.kind.filter(|value| !value.is_empty()) {
            builder.kind = kind;
        }
        if let Some(function) = call.function {
            if let Some(name) = function.name.filter(|value| !value.is_empty()) {
                builder.name.push_str(&name);
            }
            if let Some(arguments) = function.arguments {
                builder.arguments.push_str(&arguments);
            }
        }
    }
    Ok(false)
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

fn pricing_endpoints(chat: &reqwest::Url) -> Vec<reqwest::Url> {
    let mut urls = Vec::new();
    let mut derived = chat.clone();
    let path = derived.path();
    let path = path.strip_suffix("/chat/completions").unwrap_or(path);
    let path = path.strip_suffix("/v1").unwrap_or(path);
    derived.set_path(&format!("{}/api/pricing", path.trim_end_matches('/')));
    urls.push(derived);

    let mut origin = chat.clone();
    origin.set_path("/api/pricing");
    if urls.first().is_some_and(|first| first != &origin) {
        urls.push(origin);
    }
    urls
}

fn models_endpoint(chat: &reqwest::Url) -> Option<reqwest::Url> {
    let mut url = chat.clone();
    let path = url.path();
    let path = path.strip_suffix("/chat/completions").unwrap_or(path);
    url.set_path(&format!("{}/models", path.trim_end_matches('/')));
    Some(url)
}
