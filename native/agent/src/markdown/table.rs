use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use unicode_width::UnicodeWidthStr;

use crate::text::wrap_segments;
use crate::theme;

use super::inline::inline;

pub(super) fn is_table_line(line: &str) -> bool {
    line.trim_start().starts_with('|')
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Align {
    Left,
    Center,
    Right,
}

pub(super) fn render_table(
    output: &mut Vec<Line<'static>>,
    rows: &[&str],
    width: usize,
    base: Style,
) {
    let parsed: Vec<Vec<String>> = rows.iter().map(|row| parse_row(row)).collect();
    let columns = parsed.iter().map(Vec::len).max().unwrap_or(0);
    if columns == 0 {
        return;
    }

    let mut alignments = vec![Align::Left; columns];
    let mut body = Vec::with_capacity(parsed.len());
    for (index, cells) in parsed.into_iter().enumerate() {
        if index > 0 {
            if let Some(found) = separator(&cells) {
                for (column, alignment) in found.into_iter().enumerate().take(columns) {
                    alignments[column] = alignment;
                }
                continue;
            }
        }
        body.push(cells);
    }
    if body.is_empty() {
        return;
    }

    let widths = column_widths(&body, columns, width);
    let border = theme::border_idle();
    output.push(border_line(&widths, '┌', '┬', '┐', border));

    for (row_index, cells) in body.iter().enumerate() {
        let mut wrapped = Vec::with_capacity(columns);
        for (column, width) in widths.iter().enumerate() {
            let text = cells.get(column).map(String::as_str).unwrap_or("");
            let style = if row_index == 0 {
                base.add_modifier(Modifier::BOLD)
            } else {
                base
            };
            let segments = inline(text, style);
            wrapped.push(wrap_segments(&segments, *width, true));
        }
        let height = wrapped.iter().map(Vec::len).max().unwrap_or(1).max(1);
        for line_index in 0..height {
            let mut spans = vec![Span::styled("│", border)];
            for (column, lines) in wrapped.iter().enumerate() {
                spans.push(Span::styled(" ", base));
                let content = lines.get(line_index);
                let used: usize = content
                    .map(|line| {
                        line.spans
                            .iter()
                            .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
                            .sum()
                    })
                    .unwrap_or(0);
                let extra = widths[column].saturating_sub(used);
                let (left, right) = match alignments[column] {
                    Align::Left => (0, extra),
                    Align::Right => (extra, 0),
                    Align::Center => (extra / 2, extra - extra / 2),
                };
                if left > 0 {
                    spans.push(Span::styled(" ".repeat(left), base));
                }
                if let Some(line) = content {
                    spans.extend(line.spans.iter().cloned());
                }
                if right > 0 {
                    spans.push(Span::styled(" ".repeat(right), base));
                }
                spans.push(Span::styled(" ", base));
                spans.push(Span::styled("│", border));
            }
            output.push(Line::from(spans));
        }
        if row_index == 0 && body.len() > 1 {
            output.push(border_line(&widths, '├', '┼', '┤', border));
        }
    }

    output.push(border_line(&widths, '└', '┴', '┘', border));
}

fn border_line(
    widths: &[usize],
    left: char,
    middle: char,
    right: char,
    style: Style,
) -> Line<'static> {
    let mut text = String::new();
    text.push(left);
    for (index, width) in widths.iter().enumerate() {
        if index > 0 {
            text.push(middle);
        }
        text.push_str(&"─".repeat(*width + 2));
    }
    text.push(right);
    Line::from(Span::styled(text, style))
}

fn parse_row(line: &str) -> Vec<String> {
    let mut cells = Vec::new();
    let mut current = String::new();
    let mut content = line.trim();
    if let Some(rest) = content.strip_prefix('|') {
        content = rest;
    }
    let mut chars = content.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' if chars.peek() == Some(&'|') => {
                chars.next();
                current.push('|');
            }
            '|' => {
                cells.push(current.trim().to_owned());
                current.clear();
            }
            c => current.push(c),
        }
    }
    let tail = current.trim();
    if !tail.is_empty() {
        cells.push(tail.to_owned());
    }
    cells
}

fn separator(cells: &[String]) -> Option<Vec<Align>> {
    if cells.is_empty() {
        return None;
    }
    let mut output = Vec::with_capacity(cells.len());
    for cell in cells {
        let trimmed = cell.trim();
        let left = trimmed.starts_with(':');
        let right = trimmed.ends_with(':');
        let dashes = trimmed.trim_start_matches(':').trim_end_matches(':');
        if dashes.is_empty() || !dashes.chars().all(|c| c == '-') {
            return None;
        }
        output.push(match (left, right) {
            (true, true) => Align::Center,
            (false, true) => Align::Right,
            _ => Align::Left,
        });
    }
    Some(output)
}

fn plain_width(text: &str) -> usize {
    let cleaned = text.replace("**", "").replace('`', "");
    UnicodeWidthStr::width(cleaned.as_str())
}

fn column_widths(rows: &[Vec<String>], columns: usize, width: usize) -> Vec<usize> {
    let overhead = columns * 3 + 1;
    let mut natural = vec![1usize; columns];
    for row in rows {
        for (column, cell) in row.iter().enumerate().take(columns) {
            natural[column] = natural[column].max(plain_width(cell).max(1));
        }
    }
    if overhead >= width {
        return vec![1; columns];
    }
    let available = width - overhead;
    let total: usize = natural.iter().sum();
    if total <= available {
        return natural;
    }
    let mut widths = vec![1usize; columns];
    let pool = available.saturating_sub(columns);
    if pool == 0 {
        return widths;
    }
    let total = total.max(columns);
    let mut remainders = Vec::with_capacity(columns);
    let mut used = 0;
    for (width, natural) in widths.iter_mut().zip(natural.iter()) {
        let share = pool * natural / total;
        *width += share;
        used += share;
        remainders.push((pool * natural) % total);
    }
    let mut leftover = pool.saturating_sub(used);
    while leftover > 0 {
        let mut best = 0;
        for column in 1..columns {
            if remainders[column] > remainders[best] {
                best = column;
            }
        }
        widths[best] += 1;
        remainders[best] = 0;
        leftover -= 1;
    }
    widths
}
