use ratatui::layout::{Alignment, Constraint, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Clear, Paragraph};
use ratatui::Frame;
use unicode_width::UnicodeWidthStr;

use crate::app::{App, ApprovalState, Popup};
use crate::input::InputView;
use crate::markdown;
use crate::text;
use crate::theme;

const MIN_INPUT_TEXT_ROWS: u16 = 3;
const MAX_INPUT_TEXT_ROWS: u16 = 8;
const INPUT_TOP_PADDING: u16 = 1;
const APPROVAL_ROWS: u16 = 5;
const POPUP_PAD_X: u16 = 3;
const POPUP_PAD_Y: u16 = 1;
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
    let input_rows = if app.approval().is_some() {
        APPROVAL_ROWS
    } else {
        text_rows + INPUT_TOP_PADDING
    };

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
    if let Some(approval) = app.approval() {
        draw_approval(frame, approval, input_area, active);
    } else {
        draw_input(
            frame,
            app.input().view(input_width, text_rows as usize, active),
            input_area,
            active,
        );
        draw_command_overlay(frame, app, input_area, area);
    }
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
        Span::styled(one_line(app.title()), theme::brand()),
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
    let mut lines = rendered.lines.clone();
    lines.extend(markdown::queued(app.queued(), width as usize));
    app.sync_view(lines.len(), area.height as usize);
    app.set_transcript_map(
        area.y,
        area.height,
        rendered.reasoning_headers.clone(),
        rendered.tool_headers.clone(),
    );

    if lines.is_empty() {
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

    let start = app.scroll().min(lines.len());
    let end = (start + area.height as usize).min(lines.len());
    let visible = lines[start..end].to_vec();
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

fn draw_approval(frame: &mut Frame, approval: &ApprovalState, area: Rect, active: bool) {
    let gutter = if active {
        theme::border_focus()
    } else {
        theme::border_idle()
    };
    frame.buffer_mut().set_style(area, theme::input_bg());
    let width = area.width as usize;
    let inner = width.saturating_sub(3).max(1);
    let title = format!("Approve {}", approval.name);
    let title_width = UnicodeWidthStr::width(title.as_str());
    let detail = if approval.detail.is_empty() {
        approval.reason.clone()
    } else {
        approval.detail.clone()
    };
    let mut rows = vec![approval_line(
        gutter,
        vec![
            Span::styled(title, theme::tool_active()),
            Span::styled("  ", theme::muted()),
            Span::styled(
                markdown::fit(&detail, inner.saturating_sub(title_width + 2)),
                theme::text(),
            ),
        ],
        width,
    )];
    if approval.confirm_always {
        rows.push(approval_line(
            gutter,
            vec![Span::styled(
                "Allow this access every time?",
                theme::warn(),
            )],
            width,
        ));
    } else if !approval.reason.is_empty() && approval.detail != approval.reason {
        rows.push(approval_line(
            gutter,
            vec![Span::styled(
                markdown::fit(&approval.reason, inner),
                theme::muted(),
            )],
            width,
        ));
    } else {
        rows.push(approval_line(gutter, Vec::new(), width));
    }
    rows.push(approval_line(gutter, Vec::new(), width));
    rows.push(approval_line(gutter, Vec::new(), width));
    rows.push(approval_line(
        gutter,
        if approval.confirm_always {
            vec![
                Span::styled("Enter", theme::tool_active()),
                Span::styled(" confirm   ", theme::muted()),
                Span::styled("Esc", theme::tool_active()),
                Span::styled(" back", theme::muted()),
            ]
        } else {
            vec![
                Span::styled("Enter", theme::tool_active()),
                Span::styled(" select   ", theme::muted()),
                Span::styled("Esc", theme::tool_active()),
                Span::styled(" deny and stop", theme::muted()),
            ]
        },
        width,
    ));
    frame.render_widget(Paragraph::new(rows).style(theme::input_bg()), area);

    if !approval.confirm_always {
        let mut spans = vec![Span::styled("  ", theme::input_bg())];
        for (index, label) in crate::app::APPROVAL_BUTTONS.iter().enumerate() {
            let selected = index == approval.selected;
            let style = if selected {
                Style::new()
                    .fg(Color::Black)
                    .bg(theme::ACCENT)
                    .add_modifier(Modifier::BOLD)
            } else {
                theme::muted().bg(theme::INPUT_BG)
            };
            spans.push(Span::styled(format!(" {label} "), style));
            spans.push(Span::styled(" ", theme::input_bg()));
        }
        frame.render_widget(
            Paragraph::new(Line::from(spans)),
            Rect {
                x: area.x,
                y: area.y + 3,
                width: area.width,
                height: 1,
            },
        );
    }
}

fn approval_line(gutter: Style, mut spans: Vec<Span<'static>>, width: usize) -> Line<'static> {
    let mut line = vec![Span::styled("┃ ", gutter), Span::styled(" ", theme::input_bg())];
    line.append(&mut spans);
    let used: usize = line
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum();
    if used < width {
        line.push(Span::styled(" ".repeat(width - used), theme::input_bg()));
    }
    Line::from(line).style(theme::input_bg())
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
    if app.yolo() {
        spans.push(Span::styled(" · ", theme::muted()));
        spans.push(Span::styled("YOLO", theme::warn()));
    }
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(theme::input_bg()),
        area,
    );
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
    let interrupt = app.pending_elapsed().map(|elapsed| {
        let hint = if app.esc_armed() {
            " esc again to interrupt"
        } else {
            " esc interrupt"
        };
        let spinner = format!(" {}", theme::spinner((elapsed.as_millis() / 100) as usize));
        (spinner, hint)
    });
    let reserved = interrupt
        .as_ref()
        .map(|(spinner, hint)| UnicodeWidthStr::width(spinner.as_str()) + hint.len())
        .unwrap_or(0);
    let path = if left_width > reserved {
        markdown::fit(app.cwd(), left_width - reserved)
    } else {
        String::new()
    };
    let mut spans = Vec::with_capacity(right.len() + 6);
    let mut used = 0;
    if !path.is_empty() {
        used += UnicodeWidthStr::width(path.as_str());
        spans.push(Span::styled(path, muted));
    }
    if let Some((spinner, hint)) = interrupt {
        used += UnicodeWidthStr::width(spinner.as_str()) + hint.len();
        spans.push(Span::styled(spinner, theme::warn()));
        spans.push(Span::styled(
            hint,
            if app.esc_armed() {
                theme::error()
            } else {
                theme::muted()
            },
        ));
    }
    let padding = (area.width as usize).saturating_sub(used + right_width);
    if padding > 0 {
        spans.push(Span::raw(" ".repeat(padding)));
    }
    spans.extend(right);
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(Style::new().bg(Color::Reset)),
        area,
    );
}

fn draw_command_overlay(frame: &mut Frame, app: &App, input_area: Rect, area: Rect) {
    if !app.command_active() {
        return;
    }
    let matches = app.command_matches();
    let height = (matches.len().max(1) as u16 + 2).min(8);
    let natural = matches
        .iter()
        .map(|(command, hint)| command.len() + hint.len() + 4)
        .max()
        .unwrap_or("no matching command".len());
    let width = ((natural as u16) + POPUP_PAD_X * 2)
        .clamp(28, 72)
        .min(area.width.saturating_sub(2));
    let box_area = Rect {
        x: input_area.x + (input_area.width.saturating_sub(width)) / 2,
        y: input_area.y.saturating_sub(height),
        width,
        height,
    };
    frame.render_widget(Clear, box_area);
    frame.buffer_mut().set_style(box_area, theme::popup_bg());
    if matches.is_empty() {
        frame.render_widget(
            Paragraph::new(Span::styled("no matching command", theme::muted())),
            Rect {
                x: box_area.x + POPUP_PAD_X,
                y: box_area.y + POPUP_PAD_Y,
                width: box_area.width.saturating_sub(POPUP_PAD_X * 2),
                height: 1,
            },
        );
        return;
    }
    let rows = height.saturating_sub(2) as usize;
    let start = app.command_selection().saturating_sub(rows.saturating_sub(1));
    for (offset, (command, hint)) in matches.iter().skip(start).take(rows).enumerate() {
        let index = start + offset;
        let selected = index == app.command_selection();
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
        let style = if selected {
            Style::new().fg(Color::White).add_modifier(Modifier::BOLD)
        } else {
            theme::text()
        };
        frame.render_widget(
            Paragraph::new(Line::from(vec![
                Span::styled((*command).to_owned(), style),
                Span::styled(format!("  {hint}"), theme::muted()),
            ])),
            Rect {
                x: box_area.x + POPUP_PAD_X,
                y,
                width: box_area.width.saturating_sub(POPUP_PAD_X * 2),
                height: 1,
            },
        );
    }
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

enum PopupRow {
    Group(String),
    Item(usize),
}

fn draw_popup(frame: &mut Frame, popup: &Popup, area: Rect) {
    let full = frame.area();
    frame
        .buffer_mut()
        .set_style(full, Style::new().add_modifier(Modifier::DIM));

    let visible = popup.visible();
    let selected_item = visible.get(popup.selected).copied();
    let mut rows: Vec<PopupRow> = Vec::new();
    let mut seen = None;
    for index in &visible {
        let Some(item) = popup.items.get(*index) else {
            continue;
        };
        if item.group.is_some() && item.group != seen {
            seen = item.group.clone();
            rows.push(PopupRow::Group(item.group.clone().unwrap_or_default()));
        }
        rows.push(PopupRow::Item(*index));
    }
    let natural = popup
        .items
        .iter()
        .map(|item| {
            let label = UnicodeWidthStr::width(text::sanitize(&item.label).as_str());
            let hint = UnicodeWidthStr::width(item.hint.as_str());
            let group = item
                .group
                .as_deref()
                .map(UnicodeWidthStr::width)
                .unwrap_or(0);
            (label + if hint > 0 { hint + 2 } else { 0 }).max(group)
        })
        .max()
        .unwrap_or(0)
        .max(UnicodeWidthStr::width(popup.title));
    let width = ((natural as u16).saturating_add(POPUP_PAD_X * 2))
        .clamp(30, 78)
        .min(area.width.saturating_sub(4));
    let content_rows = (rows.len() as u16)
        .min(area.height.saturating_sub(7))
        .max(1);
    let height = content_rows + POPUP_PAD_Y * 2 + 2 + u16::from(popup.searchable);
    let box_area = Rect {
        x: area.x + area.width.saturating_sub(width) / 2,
        y: area.y + area.height.saturating_sub(height) / 2,
        width,
        height,
    };
    frame.render_widget(Clear, box_area);
    frame.buffer_mut().set_style(box_area, theme::popup_bg());

    let inner_x = box_area.x + POPUP_PAD_X;
    let inner_width = box_area.width.saturating_sub(POPUP_PAD_X * 2);
    let title_y = box_area.y + POPUP_PAD_Y;
    frame.render_widget(
        Paragraph::new(Span::styled(popup.title, theme::muted())),
        Rect {
            x: inner_x,
            y: title_y,
            width: inner_width,
            height: 1,
        },
    );
    if popup.searchable {
        let text = if popup.query.is_empty() {
            Span::styled("Type to search", theme::muted())
        } else {
            Span::styled(format!("Search: {}", popup.query), theme::text())
        };
        frame.render_widget(
            Paragraph::new(text),
            Rect {
                x: inner_x,
                y: title_y + 1,
                width: inner_width,
                height: 1,
            },
        );
    }

    let list_y = title_y + 1 + u16::from(popup.searchable);
    let visible_target = content_rows as usize;
    let selected_row = rows
        .iter()
        .position(|row| matches!(row, PopupRow::Item(index) if Some(*index) == selected_item))
        .unwrap_or(0);
    let start = selected_row.saturating_sub(visible_target.saturating_sub(1));
    for (offset, row) in rows.iter().skip(start).take(visible_target).enumerate() {
        let y = list_y + offset as u16;
        match row {
            PopupRow::Group(group) => {
                frame.render_widget(
                    Paragraph::new(Span::styled(
                        group.clone(),
                        Style::new()
                            .fg(theme::PROVIDER)
                            .add_modifier(Modifier::BOLD),
                    )),
                    Rect {
                        x: inner_x,
                        y,
                        width: inner_width,
                        height: 1,
                    },
                );
            }
            PopupRow::Item(index) => {
                let Some(item) = popup.items.get(*index) else {
                    continue;
                };
                let selected = Some(*index) == selected_item;
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
        Paragraph::new(line).style(Style::new().bg(Color::Reset)),
        area,
    );
}

fn model_parts(model: &str, provider: &str) -> (String, Option<String>) {
    let (name, derived) = match model.split_once('/') {
        Some((prefix, name)) => (name, Some(prefix.to_owned())),
        None => (model, None),
    };
    let label = derived.or_else(|| (!provider.is_empty()).then(|| provider.to_owned()));
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
