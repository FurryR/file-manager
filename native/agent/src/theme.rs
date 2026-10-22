use ratatui::style::{Color, Modifier, Style};

pub const ACCENT: Color = Color::Rgb(0xf5, 0xa6, 0x23);
pub const MODE: Color = Color::Rgb(0x7a, 0xa2, 0xf7);
pub const NOTICE: Color = Color::Rgb(0x7f, 0xb8, 0xe8);
pub const ERROR: Color = Color::Rgb(0xf0, 0x76, 0x76);
pub const WARN: Color = Color::Rgb(0xe5, 0xc0, 0x7b);
pub const MUTED: Color = Color::Rgb(0x80, 0x84, 0x88);
pub const BORDER: Color = Color::Rgb(0x4a, 0x4f, 0x55);
pub const CODE_FG: Color = Color::Rgb(0xc8, 0xcc, 0xc9);
pub const CODE_BG: Color = Color::Rgb(0x2a, 0x2e, 0x33);
pub const INPUT_BG: Color = Color::Rgb(0x32, 0x38, 0x3f);
pub const BLOCK_BG: Color = Color::Rgb(0x22, 0x26, 0x2b);
pub const USER_BG: Color = BLOCK_BG;
pub const EXEC_BG: Color = BLOCK_BG;
pub const POPUP_BG: Color = Color::Rgb(0x2a, 0x2e, 0x33);
pub const POPUP_SELECTED_BG: Color = Color::Rgb(0x3a, 0x40, 0x47);
pub const PROVIDER: Color = Color::Rgb(0xb4, 0xba, 0xc1);
pub const EXEC_OUTPUT: Color = Color::Rgb(0x9a, 0xa0, 0xa6);
pub const REASONING: Color = Color::Rgb(0xe0, 0xb0, 0x4f);
pub const DIFF_ADD: Color = Color::Rgb(0x7e, 0xc8, 0x7e);

pub const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

pub fn spinner(frame: usize) -> &'static str {
    SPINNER[frame % SPINNER.len()]
}

pub fn text() -> Style {
    Style::new().fg(Color::Reset)
}

pub fn input_bg() -> Style {
    Style::new().bg(INPUT_BG)
}

pub fn muted() -> Style {
    Style::new().fg(MUTED)
}

pub fn mode() -> Style {
    Style::new().fg(MODE)
}

pub fn brand() -> Style {
    Style::new().fg(ACCENT).add_modifier(Modifier::BOLD)
}

pub fn user() -> Style {
    Style::new().bg(USER_BG)
}

pub fn exec() -> Style {
    Style::new().bg(EXEC_BG)
}

pub fn exec_bar() -> Style {
    Style::new().fg(BORDER).bg(EXEC_BG)
}

pub fn exec_output() -> Style {
    Style::new().fg(EXEC_OUTPUT)
}

pub fn provider() -> Style {
    Style::new().fg(PROVIDER)
}

pub fn popup_bg() -> Style {
    Style::new().bg(POPUP_BG)
}

pub fn popup_selected() -> Style {
    Style::new().bg(POPUP_SELECTED_BG)
}

pub fn tool_active() -> Style {
    Style::new().fg(Color::White).add_modifier(Modifier::BOLD)
}

pub fn loading() -> Style {
    Style::new().fg(Color::White)
}

pub fn reasoning() -> Style {
    Style::new().fg(REASONING).add_modifier(Modifier::BOLD)
}

pub fn reasoning_dim() -> Style {
    Style::new().fg(REASONING).add_modifier(Modifier::DIM)
}

pub fn notice() -> Style {
    Style::new().fg(NOTICE)
}

pub fn error() -> Style {
    Style::new().fg(ERROR)
}

pub fn diff_add() -> Style {
    Style::new().fg(DIFF_ADD)
}

pub fn diff_del() -> Style {
    Style::new().fg(ERROR)
}

pub fn error_bg() -> Style {
    Style::new().fg(ERROR).bg(BLOCK_BG)
}

pub fn accent() -> Style {
    Style::new().fg(ACCENT)
}

pub fn warn() -> Style {
    Style::new().fg(WARN)
}

pub fn border_focus() -> Style {
    Style::new().fg(ACCENT)
}

pub fn border_idle() -> Style {
    Style::new().fg(BORDER)
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
