pub const EFFORTS: [&str; 4] = ["auto", "low", "medium", "high"];

pub enum PopupKind {
    Commands,
    Models,
    Variants,
    Sessions,
    Approval(u64),
}

pub struct Popup {
    pub title: &'static str,
    pub kind: PopupKind,
    pub items: Vec<PopupItem>,
    pub selected: usize,
}

pub struct PopupItem {
    pub label: String,
    pub hint: String,
    pub(super) action: Option<PopupAction>,
    pub(super) session: Option<String>,
}

#[derive(Clone, Copy)]
pub(super) enum PopupAction {
    Clear,
    Compact,
    Models,
    Variants,
    Sessions,
    Help,
    Exit,
    NewSession,
    Allow,
    Deny,
}

impl Popup {
    pub(super) fn approval(id: u64, name: &str, reason: &str) -> Self {
        Self {
            title: "Approve tool call",
            kind: PopupKind::Approval(id),
            items: vec![
                PopupItem {
                    label: "Allow".to_owned(),
                    hint: reason.to_owned(),
                    action: Some(PopupAction::Allow),
                    session: None,
                },
                PopupItem {
                    label: "Deny".to_owned(),
                    hint: format!("Skip {name}"),
                    action: Some(PopupAction::Deny),
                    session: None,
                },
            ],
            selected: 0,
        }
    }

    pub(super) fn commands() -> Self {
        Self {
            title: "Commands",
            kind: PopupKind::Commands,
            items: vec![
                PopupItem {
                    label: "/compact".to_owned(),
                    hint: "Summarize the conversation".to_owned(),
                    action: Some(PopupAction::Compact),
                    session: None,
                },
                PopupItem {
                    label: "/models".to_owned(),
                    hint: "Switch model".to_owned(),
                    action: Some(PopupAction::Models),
                    session: None,
                },
                PopupItem {
                    label: "/variants".to_owned(),
                    hint: "Switch reasoning effort".to_owned(),
                    action: Some(PopupAction::Variants),
                    session: None,
                },
                PopupItem {
                    label: "/clear".to_owned(),
                    hint: "Clear the conversation".to_owned(),
                    action: Some(PopupAction::Clear),
                    session: None,
                },
                PopupItem {
                    label: "/sessions".to_owned(),
                    hint: "Switch conversation".to_owned(),
                    action: Some(PopupAction::Sessions),
                    session: None,
                },
                PopupItem {
                    label: "/help".to_owned(),
                    hint: "Show keyboard shortcuts".to_owned(),
                    action: Some(PopupAction::Help),
                    session: None,
                },
                PopupItem {
                    label: "/exit".to_owned(),
                    hint: "Exit the agent".to_owned(),
                    action: Some(PopupAction::Exit),
                    session: None,
                },
            ],
            selected: 0,
        }
    }

    pub(super) fn models(models: &[String], current: &str, provider: &str) -> Self {
        let selected = models
            .iter()
            .position(|model| model == current)
            .unwrap_or(0);
        Self {
            title: "Models",
            kind: PopupKind::Models,
            items: models
                .iter()
                .map(|model| PopupItem {
                    label: model.clone(),
                    hint: provider.to_owned(),
                    action: None,
                    session: None,
                })
                .collect(),
            selected,
        }
    }

    pub(super) fn sessions(sessions: &[crate::hub::SessionEntry], current: &str) -> Self {
        let mut items = vec![PopupItem {
            label: "New session".to_owned(),
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
                hint: format!("{} messages", session.message_count),
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
        }
    }

    pub(super) fn variants(current: &str) -> Self {
        let selected = EFFORTS
            .iter()
            .position(|effort| *effort == current)
            .unwrap_or(EFFORTS.len() - 1);
        let hints = [
            "Use the provider default",
            "Faster, less reasoning",
            "Balanced reasoning",
            "More reasoning",
        ];
        Self {
            title: "Reasoning effort",
            kind: PopupKind::Variants,
            items: EFFORTS
                .iter()
                .zip(hints)
                .map(|(effort, hint)| PopupItem {
                    label: (*effort).to_owned(),
                    hint: hint.to_owned(),
                    action: None,
                    session: None,
                })
                .collect(),
            selected,
        }
    }
}
