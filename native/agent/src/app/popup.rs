use std::time::{SystemTime, UNIX_EPOCH};

use crate::model_info::ModelInfo;

pub enum PopupKind {
    Commands,
    Models,
    Effort,
    Sessions,
}

pub struct Popup {
    pub title: &'static str,
    pub kind: PopupKind,
    pub items: Vec<PopupItem>,
    pub selected: usize,
    /// Incremental filter for the model picker.
    pub query: String,
    pub searchable: bool,
}

#[derive(Clone)]
pub struct PopupItem {
    pub label: String,
    /// Small heading printed once above the group it starts.
    pub group: Option<String>,
    pub hint: String,
    pub(super) action: Option<PopupAction>,
    pub(super) session: Option<String>,
}

#[derive(Clone, Copy)]
pub(super) enum PopupAction {
    Clear,
    Compact,
    Models,
    Effort,
    Sessions,
    Help,
    Exit,
    NewSession,
    Yolo,
    Undo,
    Redo,
}

impl Popup {
    pub fn visible(&self) -> Vec<usize> {
        let query = self.query.trim().to_lowercase();
        self.items
            .iter()
            .enumerate()
            .filter(|(_, item)| {
                query.is_empty()
                    || item.label.to_lowercase().contains(&query)
                    || item
                        .group
                        .as_deref()
                        .is_some_and(|group| group.to_lowercase().contains(&query))
            })
            .map(|(index, _)| index)
            .collect()
    }

    pub fn selected_item(&self) -> Option<&PopupItem> {
        let visible = self.visible();
        visible
            .get(self.selected)
            .and_then(|index| self.items.get(*index))
    }

    pub fn move_selection(&mut self, down: bool) {
        let total = self.visible().len();
        if total == 0 {
            self.selected = 0;
            return;
        }
        self.selected = if down {
            (self.selected + 1) % total
        } else {
            self.selected.saturating_sub(1)
        };
    }

    pub(super) fn commands() -> Self {
        Self {
            title: "Commands",
            kind: PopupKind::Commands,
            items: vec![
                item("/compact", "Summarize the conversation into a new context", Some(PopupAction::Compact)),
                item("/effort", "Switch reasoning effort", Some(PopupAction::Effort)),
                item("/models", "Switch model", Some(PopupAction::Models)),
                item("/new", "Start a new conversation", Some(PopupAction::NewSession)),
                item("/sessions", "Switch conversation", Some(PopupAction::Sessions)),
                item("/undo", "Interrupt and revert the last message", Some(PopupAction::Undo)),
                item("/redo", "Restore the reverted message", Some(PopupAction::Redo)),
                item("/clear", "Clear the conversation", Some(PopupAction::Clear)),
                item("/yolo", "Auto-approve every tool in this terminal", Some(PopupAction::Yolo)),
                item("/help", "Show keyboard shortcuts", Some(PopupAction::Help)),
                item("/exit", "Exit the agent", Some(PopupAction::Exit)),
            ],
            selected: 0,
            query: String::new(),
            searchable: false,
        }
    }

    pub(super) fn models(
        models: &[String],
        info: &std::collections::HashMap<String, ModelInfo>,
        current: &str,
    ) -> Self {
        let mut list: Vec<(String, Option<String>, String, String)> = Vec::new();
        for model in models {
            let detail = info.get(model);
            let provider = detail.and_then(|detail| detail.provider.clone());
            let label = provider
                .as_deref()
                .and_then(|provider| model.strip_prefix(&format!("{provider}/")))
                .unwrap_or(model)
                .to_owned();
            let hint = detail
                .and_then(|detail| detail.context_window)
                .map(format_tokens)
                .unwrap_or_default();
            list.push((label, provider, hint, model.clone()));
        }
        list.sort_by(|left, right| {
            left.1
                .as_deref()
                .unwrap_or("")
                .cmp(right.1.as_deref().unwrap_or(""))
                .then_with(|| left.0.cmp(&right.0))
        });
        let items: Vec<PopupItem> = list
            .into_iter()
            .map(|(label, provider, hint, qualified)| PopupItem {
                label,
                group: provider,
                hint,
                action: None,
                session: Some(qualified),
            })
            .collect();
        let selected = items
            .iter()
            .position(|item| item.session.as_deref() == Some(current))
            .unwrap_or(0);
        Self {
            title: "Models",
            kind: PopupKind::Models,
            items,
            selected,
            query: String::new(),
            searchable: true,
        }
    }

    pub(super) fn sessions(sessions: &[crate::hub::SessionEntry], current: &str) -> Self {
        let mut items = vec![PopupItem {
            label: "New session".to_owned(),
            group: None,
            hint: "Start a new conversation".to_owned(),
            action: Some(PopupAction::NewSession),
            session: None,
        }];
        for session in sessions {
            let title = session.title.clone();
            items.push(PopupItem {
                label: if title.is_empty() {
                    "New conversation".to_owned()
                } else {
                    title
                },
                group: None,
                hint: age(session.created_at),
                action: None,
                session: Some(session.id.clone()),
            });
        }
        let selected = sessions
            .iter()
            .position(|session| session.id == current)
            .map(|index| index + 1)
            .unwrap_or(0);
        Self {
            title: "Conversations",
            kind: PopupKind::Sessions,
            items,
            selected,
            query: String::new(),
            searchable: false,
        }
    }

    pub(super) fn efforts(efforts: &[String], current: &str) -> Self {
        let mut values: Vec<String> = vec!["auto".to_owned()];
        for effort in efforts {
            if !values.iter().any(|known| known == effort) {
                values.push(effort.clone());
            }
        }
        let selected = values
            .iter()
            .position(|effort| effort == current)
            .unwrap_or(0);
        Self {
            title: "Reasoning effort",
            kind: PopupKind::Effort,
            items: values
                .into_iter()
                .map(|effort| PopupItem {
                    label: effort.clone(),
                    group: None,
                    hint: if effort == "auto" {
                        "Use the provider default".to_owned()
                    } else {
                        "Send as reasoning_effort".to_owned()
                    },
                    action: None,
                    session: Some("__effort__".to_owned()),
                })
                .collect(),
            selected,
            query: String::new(),
            searchable: false,
        }
    }
}

fn item(label: &str, hint: &str, action: Option<PopupAction>) -> PopupItem {
    PopupItem {
        label: label.to_owned(),
        group: None,
        hint: hint.to_owned(),
        action,
        session: None,
    }
}

fn format_tokens(tokens: u64) -> String {
    if tokens >= 1_000_000 {
        format!("{:.0}M ctx", tokens as f64 / 1_000_000.0)
    } else if tokens >= 1_000 {
        format!("{:.0}K ctx", tokens as f64 / 1_000.0)
    } else {
        format!("{tokens} ctx")
    }
}

fn age(created_at: u64) -> String {
    if created_at == 0 {
        return String::new();
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0);
    let seconds = now.saturating_sub(created_at) / 1000;
    if seconds < 60 {
        "just now".to_owned()
    } else if seconds < 3600 {
        format!("{}m ago", seconds / 60)
    } else if seconds < 86_400 {
        format!("{}h ago", seconds / 3600)
    } else if seconds < 86_400 * 30 {
        format!("{}d ago", seconds / 86_400)
    } else if seconds < 86_400 * 365 {
        format!("{}mo ago", seconds / (86_400 * 30))
    } else {
        format!("{}y ago", seconds / (86_400 * 365))
    }
}
