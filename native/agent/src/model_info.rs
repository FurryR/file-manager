use std::collections::HashMap;

use serde_json::Value;

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

impl ModelCatalog {
    pub fn from_json(text: &str) -> Self {
        let mut catalog = Self::default();
        let Ok(value) = serde_json::from_str::<Value>(text) else {
            return catalog;
        };
        if let Some(models) = value.get("models").and_then(Value::as_array) {
            for model in models {
                if let Some(model) = model.as_str().filter(|model| !model.is_empty()) {
                    if !catalog.models.iter().any(|known| known == model) {
                        catalog.models.push(model.to_owned());
                    }
                }
            }
        }
        if let Some(entries) = value.get("info").and_then(Value::as_object) {
            for (name, entry) in entries {
                let info = catalog.info.entry(name.clone()).or_default();
                info.context_window = entry.get("context_window").and_then(as_u64);
                info.input_per_million = entry.get("input_per_million").and_then(Value::as_f64);
                info.output_per_million = entry.get("output_per_million").and_then(Value::as_f64);
                info.request_price = entry.get("request_price").and_then(Value::as_f64);
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
