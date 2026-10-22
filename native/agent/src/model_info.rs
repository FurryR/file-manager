use std::collections::HashMap;

use reqwest::{Client, Url};
use serde_json::Value;

const MAX_METADATA: u64 = 4 * 1024 * 1024;

#[derive(Clone, Default)]
pub struct ModelInfo {
    pub context_window: Option<u64>,
    pub input_per_million: Option<f64>,
    pub output_per_million: Option<f64>,
    pub request_price: Option<f64>,
}

#[derive(Default)]
pub struct ModelCatalog {
    pub models: Vec<String>,
    pub info: HashMap<String, ModelInfo>,
}

pub async fn fetch(
    client: &Client,
    pricing: &[Url],
    models: Option<&Url>,
    key: &str,
) -> ModelCatalog {
    let mut catalog = ModelCatalog::default();
    for url in pricing {
        if let Some(json) = get(client, url, key).await {
            apply_pricing(&json, &mut catalog);
        }
        if !catalog.info.is_empty() {
            break;
        }
    }
    if let Some(url) = models {
        if let Some(json) = get(client, url, key).await {
            apply_models(&json, &mut catalog);
        }
    }
    if catalog.models.is_empty() {
        catalog.models.extend(catalog.info.keys().cloned());
        catalog.models.sort();
    }
    catalog
}

async fn get(client: &Client, url: &Url, key: &str) -> Option<Value> {
    let mut request = client.get(url.clone()).header("Accept", "application/json");
    if !key.is_empty() {
        request = request.bearer_auth(key);
    }
    let response = request.send().await.ok()?;
    if !response.status().is_success()
        || response
            .content_length()
            .is_some_and(|length| length > MAX_METADATA)
    {
        return None;
    }
    let body = response.text().await.ok()?;
    if body.len() as u64 > MAX_METADATA {
        return None;
    }
    serde_json::from_str(&body).ok()
}

fn apply_pricing(json: &Value, catalog: &mut ModelCatalog) {
    let group = json
        .get("group_ratio")
        .and_then(Value::as_object)
        .and_then(|groups| {
            groups
                .get("default")
                .and_then(Value::as_f64)
                .or_else(|| groups.values().find_map(Value::as_f64))
        })
        .unwrap_or(1.0);
    let Some(entries) = json.get("data").and_then(Value::as_array) else {
        return;
    };
    for entry in entries {
        let Some(name) = entry.get("model_name").and_then(Value::as_str) else {
            continue;
        };
        let info = catalog.info.entry(name.to_owned()).or_default();
        if info.context_window.is_none() {
            info.context_window = context_of(entry);
        }
        let quota_type = entry.get("quota_type").and_then(Value::as_u64).unwrap_or(0);
        let model_price = entry
            .get("model_price")
            .and_then(Value::as_f64)
            .unwrap_or(0.0);
        if quota_type == 1 && model_price > 0.0 {
            info.request_price = Some(model_price * group);
            continue;
        }
        let ratio = entry
            .get("model_ratio")
            .and_then(Value::as_f64)
            .unwrap_or(0.0);
        if ratio <= 0.0 {
            continue;
        }
        let input = ratio * group * 2.0;
        let completion = entry
            .get("completion_ratio")
            .and_then(Value::as_f64)
            .unwrap_or(1.0);
        info.input_per_million = Some(input);
        info.output_per_million = Some(input * completion.max(0.0));
    }
}

fn apply_models(json: &Value, catalog: &mut ModelCatalog) {
    let Some(entries) = json.get("data").and_then(Value::as_array) else {
        return;
    };
    for entry in entries {
        let Some(id) = entry
            .get("id")
            .and_then(Value::as_str)
            .or_else(|| entry.get("model").and_then(Value::as_str))
        else {
            continue;
        };
        if !catalog.models.iter().any(|known| known == id) {
            catalog.models.push(id.to_owned());
        }
        let context = context_of(entry);
        if let Some(context) = context {
            catalog
                .info
                .entry(id.to_owned())
                .or_default()
                .context_window = Some(context);
        }
    }
}

fn context_of(value: &Value) -> Option<u64> {
    const KEYS: [&str; 8] = [
        "context_length",
        "max_prompt_tokens",
        "max_model_len",
        "context_window",
        "max_context_length",
        "max_input_tokens",
        "input_token_limit",
        "context_size",
    ];
    for key in KEYS {
        if let Some(found) = value.get(key).and_then(as_u64).filter(|found| *found > 0) {
            return Some(found);
        }
    }
    for key in ["meta", "metadata", "top_provider", "limits", "capabilities"] {
        if let Some(found) = value.get(key).and_then(context_of) {
            return Some(found);
        }
    }
    None
}

fn as_u64(value: &Value) -> Option<u64> {
    value
        .as_u64()
        .or_else(|| {
            value
                .as_f64()
                .filter(|number| *number > 0.0)
                .map(|number| number as u64)
        })
        .or_else(|| value.as_str().and_then(|text| text.parse().ok()))
}
