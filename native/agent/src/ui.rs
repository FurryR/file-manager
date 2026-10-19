use std::time::Duration;

use ratatui::layout::{Alignment, Constraint, Layout, Position};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, BorderType, Padding, Paragraph};
use ratatui::Frame;

use crate::app::App;
use crate::input::InputView;
use crate::markdown;
use crate::text;
use crate::theme;

const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

#[derive(Default)]
pub struct UiState {
    revision: u64,
    width: u16,
    lines: Vec<Line<'static>>,
    ready: bool,
}

pub fn draw(frame: &mut Frame, app: &mut App, state: &mut UiState) {
    let area = frame.area();
    if area.width < 24 || area.height < 8 {
        frame.render_widget(
            Paragraph::new("Terminal too small; resize to continue.")
                .style(theme::muted())
                .alignment(Alignment::Center),
            area,
        );
        return;
    }

    let input_width = area.width.saturating_sub(2) as usize;
    let max_input_rows = ((area.height.saturating_sub(4) as usize) / 2).clamp(1, 8);
    let input_rows = {
        let view = app.input().view(input_width, max_input_rows);
        view.lines.len().max(1) as u16
    };
    let input_height = input_rows + 2;

    let [header, rule, body, input_area, status] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Fill(1),
        Constraint::Length(input_height),
        Constraint::Length(1),
    ])
    .areas(area);

    draw_header(frame, app, header);
    draw_rule(frame, rule);
    draw_transcript(frame, app, state, body);
    draw_input(
        frame,
        app,
        app.input().view(input_width, max_input_rows),
        input_area,
    );
    draw_status(frame, app, status);
}

fn draw_header(frame: &mut Frame, app: &App, area: ratatui::layout::Rect) {
    let split = (area.width / 2).clamp(1, 40);
    let [left, right] =
        Layout::horizontal([Constraint::Fill(1), Constraint::Length(split)]).areas(area);

    let left_line = Line::from(vec![
        Span::styled("◆ ", theme::brand()),
        Span::styled("agent", theme::brand()),
        Span::styled("  ·  ", theme::muted()),
        Span::styled(one_line(app.context()), theme::muted()),
    ]);
    frame.render_widget(Paragraph::new(left_line), left);

    let model = Line::from(Span::styled(
        format!("{} ", one_line(app.model())),
        theme::muted(),
    ));
    frame.render_widget(Paragraph::new(model).alignment(Alignment::Right), right);
}

fn draw_rule(frame: &mut Frame, area: ratatui::layout::Rect) {
    if area.width == 0 {
        return;
    }
    let rule = Line::from(Span::styled("─".repeat(area.width as usize), theme::rule()));
    frame.render_widget(Paragraph::new(rule), area);
}

fn draw_transcript(
    frame: &mut Frame,
    app: &mut App,
    state: &mut UiState,
    area: ratatui::layout::Rect,
) {
    let width = area.width.saturating_sub(2).max(1);
    if !state.ready || state.revision != app.revision() || state.width != width {
        state.lines = markdown::render(app.transcript(), width as usize);
        state.revision = app.revision();
        state.width = width;
        state.ready = true;
    }

    app.sync_view(state.lines.len(), area.height as usize);

    if state.lines.is_empty() {
        frame.render_widget(
            Paragraph::new("No messages yet. Type below to start.")
                .style(theme::muted())
                .alignment(Alignment::Center),
            area,
        );
        return;
    }

    let start = app.scroll().min(state.lines.len());
    let end = (start + area.height as usize).min(state.lines.len());
    let visible = state.lines[start..end].to_vec();
    let paragraph = Paragraph::new(visible)
        .block(Block::default().padding(Padding::new(2, 0, 0, 0)))
        .style(theme::text());
    frame.render_widget(paragraph, area);
}

fn draw_input(frame: &mut Frame, app: &App, view: InputView, area: ratatui::layout::Rect) {
    let InputView {
        lines,
        cursor,
        total_rows,
        first_row,
    } = view;
    let visible_rows = lines.len();
    let mut block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(if app.is_pending() {
            theme::border_idle()
        } else {
            theme::border_focus()
        })
        .title_top(
            Line::from(Span::styled(
                if app.is_pending() {
                    " message · waiting "
                } else {
                    " message "
                },
                theme::muted(),
            ))
            .left_aligned(),
        );
    if total_rows > visible_rows {
        block = block.title_top(
            Line::from(Span::styled(
                format!(" {}/{} ", first_row + 1, total_rows),
                theme::muted(),
            ))
            .right_aligned(),
        );
    }
    frame.render_widget(Paragraph::new(lines).block(block), area);
    if let Some(cursor) = cursor {
        frame.set_cursor_position(Position::new(area.x + 1 + cursor.x, area.y + 1 + cursor.y));
    }
}

fn draw_status(frame: &mut Frame, app: &App, area: ratatui::layout::Rect) {
    let [left, right] =
        Layout::horizontal([Constraint::Fill(1), Constraint::Length(16)]).areas(area);

    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(app.notice(), theme::muted()))),
        left,
    );

    let (label, style) = if let Some(elapsed) = app.pending_elapsed() {
        (
            format!("{} {}s", spinner(elapsed), elapsed.as_secs()),
            theme::warn(),
        )
    } else if !app.follow() {
        ("history".to_owned(), theme::warn())
    } else {
        ("ready".to_owned(), theme::ok())
    };
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(format!("{label} "), style)))
            .alignment(Alignment::Right),
        right,
    );
}

fn spinner(elapsed: Duration) -> &'static str {
    SPINNER[(elapsed.as_millis() / 100) as usize % SPINNER.len()]
}

fn one_line(value: &str) -> String {
    text::sanitize(value).replace('\n', " ")
}
