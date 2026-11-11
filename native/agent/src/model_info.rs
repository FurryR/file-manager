use std::collections::HashMap;

use serde_json::Value;

#[derive(Clone, Default)]
pub struct ModelInfo {
    pub provider: Option<String>,
    pub context_window: Option<u64>,
    pub max_output: Option<u64>,
    pub input_per_million: Option<f64>,
    pub output_per_million: Option<f64>,
    pub cache_read_per_million: Option<f64>,
    pub cache_write_per_million: Option<f64>,
    pub request_price: Option<f64>,
    /// Reasoning efforts the provider/model accepts. An empty list means the
    /// model has no configurable effort beyond the provider default.
    pub efforts: Vec<String>,
    pub thinking: bool,
    pub tool_call: bool,
    pub vision: bool,
}

#[derive(Default)]
pub struct ModelCatalog {
    pub models: Vec<String>,
    pub info: HashMap<String, ModelInfo>,
}

impl ModelCatalog {
    pub fn from_json(text: &str) -> Self {
        let mut catalog = Self::default();
        let Ok(value) = serde_json::from_str::<Value>(text) else {
            return catalog;
        };
        if let Some(models) = value.get("models").and_then(Value::as_array) {
            for model in models {
                let Some(model) = model.as_str().filter(|model| !model.is_empty()) else {
                    continue;
                };
                if !catalog.models.iter().any(|known| known == model) {
                    catalog.models.push(model.to_owned());
                }
            }
        }
        if let Some(entries) = value.get("info").and_then(Value::as_object) {
            for (name, entry) in entries {
                let info = catalog.info.entry(name.clone()).or_default();
                info.provider = entry
                    .get("provider")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                    .map(str::to_owned);
                info.context_window = entry.get("context_window").and_then(as_u64);
                info.max_output = entry.get("max_output").and_then(as_u64);
                info.input_per_million = entry.get("input_per_million").and_then(Value::as_f64);
                info.output_per_million = entry.get("output_per_million").and_then(Value::as_f64);
                info.cache_read_per_million =
                    entry.get("cache_read_per_million").and_then(Value::as_f64);
                info.cache_write_per_million =
                    entry.get("cache_write_per_million").and_then(Value::as_f64);
                info.request_price = entry.get("request_price").and_then(Value::as_f64);
                info.efforts = entry
                    .get("efforts")
                    .and_then(Value::as_array)
                    .map(|values| {
                        values
                            .iter()
                            .filter_map(Value::as_str)
                            .map(str::trim)
                            .filter(|value| !value.is_empty())
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default();
                info.thinking = entry.get("thinking").and_then(Value::as_bool).unwrap_or(false);
                info.tool_call = entry
                    .get("tool_call")
                    .and_then(Value::as_bool)
                    .unwrap_or(true);
                info.vision = entry.get("vision").and_then(Value::as_bool).unwrap_or(false);
            }
        }
        if catalog.models.is_empty() {
            catalog.models.extend(catalog.info.keys().cloned());
            catalog.models.sort();
        }
        catalog
    }
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
