use ratatui::style::{Color, Modifier, Style};

pub const ACCENT: Color = Color::Rgb(0xf5, 0xa6, 0x23);
pub const USER: Color = Color::Rgb(0x7a, 0xa2, 0xf7);
pub const ASSISTANT: Color = Color::Rgb(0x9e, 0xce, 0x6a);
pub const NOTICE: Color = Color::Rgb(0x7f, 0xb8, 0xe8);
pub const ERROR: Color = Color::Rgb(0xf0, 0x76, 0x76);
pub const WARN: Color = Color::Rgb(0xe5, 0xc0, 0x7b);
pub const OK: Color = Color::Rgb(0x9e, 0xce, 0x6a);
pub const MUTED: Color = Color::Rgb(0x80, 0x84, 0x88);
pub const BORDER: Color = Color::Rgb(0x4a, 0x4f, 0x55);
pub const RULE: Color = Color::Rgb(0x3a, 0x3f, 0x45);
pub const CODE_FG: Color = Color::Rgb(0xc8, 0xcc, 0xc9);
pub const CODE_BG: Color = Color::Rgb(0x2a, 0x2e, 0x33);

pub fn text() -> Style {
    Style::new().fg(Color::Reset)
}

pub fn muted() -> Style {
    Style::new().fg(MUTED)
}

pub fn brand() -> Style {
    Style::new().fg(ACCENT).add_modifier(Modifier::BOLD)
}

pub fn user() -> Style {
    Style::new().fg(USER)
}

pub fn assistant() -> Style {
    Style::new().fg(ASSISTANT)
}

pub fn notice() -> Style {
    Style::new().fg(NOTICE)
}

pub fn error() -> Style {
    Style::new().fg(ERROR)
}

pub fn accent() -> Style {
    Style::new().fg(ACCENT)
}

pub fn warn() -> Style {
    Style::new().fg(WARN)
}

pub fn ok() -> Style {
    Style::new().fg(OK)
}

pub fn rule() -> Style {
    Style::new().fg(RULE)
}

pub fn border_focus() -> Style {
    Style::new().fg(ACCENT)
}

pub fn border_idle() -> Style {
    Style::new().fg(BORDER)
}

pub fn prompt() -> Style {
    Style::new().fg(ACCENT).add_modifier(Modifier::BOLD)
}

pub fn code() -> Style {
    Style::new().fg(CODE_FG).bg(CODE_BG)
}

pub fn code_bar() -> Style {
    Style::new().fg(MUTED)
}

pub fn code_lang() -> Style {
    Style::new().fg(MUTED).add_modifier(Modifier::ITALIC)
}

pub fn inline_code() -> Style {
    Style::new().fg(CODE_FG).bg(CODE_BG)
}
