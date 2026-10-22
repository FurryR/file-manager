use ratatui::layout::{Alignment, Constraint, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Clear, Paragraph};
use ratatui::Frame;
use unicode_width::UnicodeWidthStr;

use crate::app::{App, Popup};
use crate::input::InputView;
use crate::markdown;
use crate::text;
use crate::theme;

const MIN_INPUT_TEXT_ROWS: u16 = 3;
const MAX_INPUT_TEXT_ROWS: u16 = 8;
const INPUT_TOP_PADDING: u16 = 1;
const MODE: &str = "Build";

#[derive(Default)]
pub struct UiState {
    revision: u64,
    width: u16,
    rendered: Option<markdown::Rendered>,
}

pub fn draw(frame: &mut Frame, app: &mut App, state: &mut UiState) {
    let outer = frame.area();
    let area = Rect::new(
        outer.x.saturating_add(2),
        outer.y,
        outer.width.saturating_sub(4),
        outer.height.saturating_sub(1),
    );
    if area.width < 24 || area.height < 10 {
        frame.render_widget(
            Paragraph::new("Terminal too small; resize to continue.")
                .style(theme::muted())
                .alignment(Alignment::Center),
            area,
        );
        return;
    }

    let input_width = area.width as usize;
    let active = !app.is_pending();
    let max_text_rows = area
        .height
        .saturating_sub(10)
        .clamp(MIN_INPUT_TEXT_ROWS, MAX_INPUT_TEXT_ROWS);
    let needed = app.input().row_count(input_width).max(1) as u16;
    let text_rows = needed.clamp(MIN_INPUT_TEXT_ROWS, max_text_rows);
    let input_rows = text_rows + INPUT_TOP_PADDING;

    let [header, body, _gap, input_area, meta_area, border_area, status_area] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Fill(1),
        Constraint::Length(1),
        Constraint::Length(input_rows),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .areas(area);

    draw_header(frame, app, header);
    draw_transcript(frame, app, state, body);
    draw_input(
        frame,
        app.input().view(input_width, text_rows as usize, active),
        input_area,
        active,
    );
    draw_meta(frame, app, meta_area, active);
    draw_status(frame, app, status_area);
    draw_border(frame, border_area, active);
    if let Some(popup) = app.popup() {
        draw_popup(frame, popup, area);
    }
}

fn draw_header(frame: &mut Frame, app: &App, area: ratatui::layout::Rect) {
    let line = Line::from(vec![
        Span::styled("◆ ", theme::brand()),
        Span::styled("agent", theme::brand()),
        Span::styled("  ·  ", theme::muted()),
        Span::styled(one_line(app.context()), theme::muted()),
    ]);
    frame.render_widget(Paragraph::new(line), area);
}

fn draw_transcript(
    frame: &mut Frame,
    app: &mut App,
    state: &mut UiState,
    area: ratatui::layout::Rect,
) {
    let width = area.width.max(1);
    if state.revision != app.revision() || state.width != width || state.rendered.is_none() {
        state.rendered = Some(markdown::render(
            app.transcript(),
            width as usize,
            app.thinking_frame(),
        ));
        state.revision = app.revision();
        state.width = width;
    }

    let rendered = state.rendered.as_ref().expect("rendered transcript");
    app.sync_view(rendered.lines.len(), area.height as usize);
    app.set_transcript_map(
        area.y,
        area.height,
        rendered.reasoning_headers.clone(),
        rendered.tool_headers.clone(),
    );

    if rendered.lines.is_empty() {
        if area.height > 0 {
            let centered = Rect {
                x: area.x,
                y: area.y + area.height.saturating_sub(1) / 2,
                width: area.width,
                height: 1,
            };
            frame.render_widget(
                Paragraph::new("No messages yet. Type below to start.")
                    .style(theme::muted())
                    .alignment(Alignment::Center),
                centered,
            );
        }
        return;
    }

    let start = app.scroll().min(rendered.lines.len());
    let end = (start + area.height as usize).min(rendered.lines.len());
    let visible = rendered.lines[start..end].to_vec();
    frame.render_widget(Paragraph::new(visible).style(theme::text()), area);
}

fn draw_input(frame: &mut Frame, view: InputView, area: ratatui::layout::Rect, active: bool) {
    let InputView { lines, cursor, .. } = view;
    let gutter = if active {
        theme::border_focus()
    } else {
        theme::border_idle()
    };
    let mut lines = lines;
    for _ in 0..INPUT_TOP_PADDING {
        lines.insert(
            0,
            Line::from(vec![Span::styled("┃ ", gutter), Span::raw(" ")]),
        );
    }
    frame.render_widget(Paragraph::new(lines).style(theme::input_bg()), area);
    if let Some(cursor) = cursor {
        frame.set_cursor_position(Position::new(
            area.x + cursor.x,
            area.y + INPUT_TOP_PADDING + cursor.y,
        ));
    }
}

fn draw_meta(frame: &mut Frame, app: &App, area: ratatui::layout::Rect, active: bool) {
    let gutter = if active {
        theme::border_focus()
    } else {
        theme::border_idle()
    };
    let (model, provider) = model_parts(app.model(), app.provider());
    let mut spans = vec![
        Span::styled("┃ ", gutter),
        Span::raw(" "),
        Span::styled(MODE, theme::mode()),
        Span::styled(" · ", theme::muted()),
        Span::styled(text::sanitize(&model), theme::text()),
    ];
    if let Some(provider) = provider {
        spans.push(Span::styled(format!(" {provider}"), theme::provider()));
    }
    spans.push(Span::styled(" · ", theme::muted()));
    spans.push(Span::styled(app.effort().to_owned(), theme::accent()));
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(theme::input_bg()),
        area,
    );

    let right = if let Some(elapsed) = app.pending_elapsed() {
        Some((
            format!(
                "{} {}s ",
                theme::spinner((elapsed.as_millis() / 100) as usize),
                elapsed.as_secs()
            ),
            theme::warn(),
        ))
    } else if !app.notice().is_empty() {
        Some((app.notice().to_owned(), theme::muted()))
    } else {
        None
    };
    if let Some((text, style)) = right {
        frame.render_widget(
            Paragraph::new(Line::from(Span::styled(text, style)))
                .style(theme::input_bg())
                .alignment(Alignment::Right),
            area,
        );
    }
}

fn draw_status(frame: &mut Frame, app: &App, area: Rect) {
    let muted = theme::muted();
    let mut right = Vec::new();
    let tokens = app.context_tokens();
    if tokens > 0 {
        let mut text = format_tokens(tokens);
        if let Some(window) = app.context_window().filter(|window| *window > 0) {
            let percent = (tokens as f64 / window as f64 * 100.0).round();
            if percent < 1.0 {
                text.push_str(" (<1%)");
            } else {
                text.push_str(&format!(" ({percent:.0}%)"));
            }
        }
        right.push(Span::styled(text, muted));
        if let Some(cost) = app.cost() {
            right.push(Span::styled(" · ", muted));
            right.push(Span::styled(format!("${cost:.2}"), muted));
        }
    }
    if !right.is_empty() {
        right.push(Span::styled("  ", muted));
    }
    right.push(Span::styled("ctrl+p", Style::new().fg(Color::White)));
    right.push(Span::styled(" commands", muted));

    let right_width: usize = right
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum();
    let gap = usize::from(right_width > 0);
    let left_width = (area.width as usize).saturating_sub(right_width + gap);
    let path = if left_width > 0 {
        markdown::fit(app.cwd(), left_width)
    } else {
        String::new()
    };
    let mut spans = Vec::with_capacity(right.len() + 2);
    let mut used = 0;
    if !path.is_empty() {
        used += UnicodeWidthStr::width(path.as_str());
        spans.push(Span::styled(path, muted));
    }
    let padding = (area.width as usize).saturating_sub(used + right_width);
    if padding > 0 {
        spans.push(Span::raw(" ".repeat(padding)));
    }
    spans.extend(right);
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(Style::new().bg(Color::Black)),
        area,
    );
}

fn format_tokens(tokens: u64) -> String {
    if tokens >= 1_000_000 {
        format!("{:.1}M", tokens as f64 / 1_000_000.0)
    } else if tokens >= 1_000 {
        format!("{:.1}K", tokens as f64 / 1_000.0)
    } else {
        tokens.to_string()
    }
}

fn draw_popup(frame: &mut Frame, popup: &Popup, area: Rect) {
    let full = frame.area();
    frame
        .buffer_mut()
        .set_style(full, Style::new().add_modifier(Modifier::DIM));

    let pad = 2;
    let natural = popup
        .items
        .iter()
        .map(|item| {
            let label = UnicodeWidthStr::width(text::sanitize(&item.label).as_str());
            let hint = UnicodeWidthStr::width(item.hint.as_str());
            label + if hint > 0 { hint + 2 } else { 0 }
        })
        .max()
        .unwrap_or(0)
        .max(UnicodeWidthStr::width(popup.title));
    let width = ((natural as u16).saturating_add(pad * 2))
        .clamp(24, 72)
        .min(area.width.saturating_sub(4));
    let height = (popup.items.len() as u16 + 2)
        .min(area.height.saturating_sub(2))
        .max(3);
    let box_area = Rect {
        x: area.x + area.width.saturating_sub(width) / 2,
        y: area.y + area.height.saturating_sub(height) / 2,
        width,
        height,
    };
    frame.render_widget(Clear, box_area);
    frame.buffer_mut().set_style(box_area, theme::popup_bg());

    let inner_x = box_area.x + pad;
    let inner_width = box_area.width.saturating_sub(pad * 2);
    frame.render_widget(
        Paragraph::new(Span::styled(popup.title, theme::muted())),
        Rect {
            x: inner_x,
            y: box_area.y,
            width: inner_width,
            height: 1,
        },
    );

    let visible = height.saturating_sub(2) as usize;
    let start = if visible > 0 && popup.selected >= visible {
        popup.selected + 1 - visible
    } else {
        0
    };
    for (offset, (index, item)) in popup
        .items
        .iter()
        .enumerate()
        .skip(start)
        .take(visible.max(1))
        .enumerate()
    {
        let selected = index == popup.selected;
        let y = box_area.y + 1 + offset as u16;
        frame.buffer_mut().set_style(
            Rect {
                x: box_area.x,
                y,
                width: box_area.width,
                height: 1,
            },
            if selected {
                theme::popup_selected()
            } else {
                theme::popup_bg()
            },
        );
        let label = text::sanitize(&item.label);
        let label_style = if selected {
            Style::new().fg(Color::White).add_modifier(Modifier::BOLD)
        } else {
            theme::text()
        };
        let hint_style = if selected {
            Style::new().fg(Color::White).add_modifier(Modifier::DIM)
        } else {
            theme::provider()
        };
        let mut spans = vec![Span::styled(label.clone(), label_style)];
        if !item.hint.is_empty() {
            let used = UnicodeWidthStr::width(label.as_str());
            let hint = UnicodeWidthStr::width(item.hint.as_str());
            let gap = (inner_width as usize).saturating_sub(used + hint);
            if gap > 0 {
                spans.push(Span::raw(" ".repeat(gap)));
            }
            spans.push(Span::styled(item.hint.clone(), hint_style));
        }
        frame.render_widget(
            Paragraph::new(Line::from(spans)),
            Rect {
                x: inner_x,
                y,
                width: inner_width,
                height: 1,
            },
        );
    }
}

fn draw_border(frame: &mut Frame, area: ratatui::layout::Rect, active: bool) {
    let gutter = if active { theme::ACCENT } else { theme::BORDER };
    let width = area.width as usize;
    let line = Line::from(vec![
        Span::styled("╹", Style::new().fg(gutter)),
        Span::styled(
            "▀".repeat(width.saturating_sub(1)),
            Style::new().fg(theme::INPUT_BG),
        ),
    ]);
    frame.render_widget(
        Paragraph::new(line).style(Style::new().bg(Color::Black)),
        area,
    );
}

fn model_parts(model: &str, provider: &str) -> (String, Option<String>) {
    let (name, derived) = match model.split_once('/') {
        Some((prefix, name)) => (name, Some(prefix.to_owned())),
        None => (model, None),
    };
    let label = if provider.is_empty() {
        derived
    } else {
        Some(provider.to_owned())
    };
    (display_model(name), label)
}

fn display_model(model: &str) -> String {
    model
        .split(|c| c == '-' || c == '_')
        .filter(|part| !part.is_empty())
        .map(|part| {
            if part.eq_ignore_ascii_case("deepseek") {
                "DeepSeek".to_owned()
            } else if part.eq_ignore_ascii_case("gpt") {
                "GPT".to_owned()
            } else {
                let mut chars = part.chars();
                let Some(first) = chars.next() else {
                    return String::new();
                };
                let mut word = first.to_uppercase().collect::<String>();
                word.push_str(chars.as_str());
                word
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn one_line(value: &str) -> String {
    text::sanitize(value).replace('\n', " ")
}
