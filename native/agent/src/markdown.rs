use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use unicode_width::UnicodeWidthStr;

use crate::model::{Entry, Role};
use crate::text::{sanitize, wrap_segments};
use crate::theme;

pub fn render(entries: &[Entry], width: usize) -> Vec<Line<'static>> {
    let mut output = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        if index > 0 {
            output.push(Line::default());
        }
        output.push(label(entry.role));
        render_entry(&mut output, &entry.content, width);
    }
    output
}

fn label(role: Role) -> Line<'static> {
    let (style, name) = match role {
        Role::User => (theme::user(), "you"),
        Role::Assistant => (theme::assistant(), "agent"),
        Role::Notice => (theme::notice(), "agent"),
        Role::Error => (theme::error(), "error"),
    };
    Line::from(vec![
        Span::styled("▌ ", style),
        Span::styled(name, style.add_modifier(Modifier::BOLD)),
    ])
}

fn render_entry(output: &mut Vec<Line<'static>>, content: &str, width: usize) {
    let clean = sanitize(content);
    let start = output.len();
    let mut in_code = false;

    for raw in clean.split('\n') {
        let line = raw.trim_end();
        if line.trim_start().starts_with("```") {
            if in_code {
                in_code = false;
            } else {
                let language = line.trim().trim_start_matches('`').trim();
                in_code = true;
                if !language.is_empty() {
                    output.push(Line::from(Span::styled(
                        language.to_owned(),
                        theme::code_lang(),
                    )));
                }
            }
            continue;
        }
        if in_code {
            output.extend(code_lines(line, width));
        } else {
            output.extend(prose_lines(line, width));
        }
    }

    if output.len() == start {
        output.push(Line::default());
    }
}

fn code_lines(line: &str, width: usize) -> Vec<Line<'static>> {
    let inner = width.saturating_sub(2).max(1);
    wrap_segments(&[(theme::code(), line.to_owned())], inner, false)
        .into_iter()
        .map(|wrapped| {
            let used: usize = wrapped
                .spans
                .iter()
                .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
                .sum();
            let padding = width.saturating_sub(2 + used);
            let mut spans = Vec::with_capacity(wrapped.spans.len() + 2);
            spans.push(Span::styled("│ ", theme::code_bar()));
            spans.extend(wrapped.spans);
            if padding > 0 {
                spans.push(Span::styled(" ".repeat(padding), theme::code()));
            }
            Line::from(spans)
        })
        .collect()
}

fn prose_lines(line: &str, width: usize) -> Vec<Line<'static>> {
    let trimmed = line.trim_start();
    if trimmed.is_empty() {
        return vec![Line::default()];
    }
    if let Some(rest) = heading(trimmed) {
        let style = theme::text().fg(theme::ACCENT).add_modifier(Modifier::BOLD);
        return wrap_segments(&[(style, rest.to_owned())], width, true);
    }
    if let Some(rest) = trimmed.strip_prefix("> ") {
        return prefixed(rest, width, "│ ", "  ", theme::muted(), theme::muted());
    }
    if let Some(rest) = trimmed
        .strip_prefix("- ")
        .or_else(|| trimmed.strip_prefix("* "))
        .or_else(|| trimmed.strip_prefix("+ "))
    {
        return prefixed(rest, width, "• ", "  ", theme::accent(), theme::text());
    }
    if let Some((marker, rest)) = numbered(trimmed) {
        let indent = " ".repeat(UnicodeWidthStr::width(marker));
        return prefixed(rest, width, marker, &indent, theme::muted(), theme::text());
    }
    wrap_segments(&inline(trimmed, theme::text()), width, true)
}

fn heading(line: &str) -> Option<&str> {
    let hashes = line.chars().take_while(|c| *c == '#').count();
    if hashes == 0 || hashes > 6 {
        return None;
    }
    let rest = line[hashes..].strip_prefix(' ')?;
    Some(rest.trim_start())
}

fn numbered(line: &str) -> Option<(&str, &str)> {
    let digits = line.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return None;
    }
    let rest = &line[digits..];
    if let Some(rest) = rest.strip_prefix(". ").or_else(|| rest.strip_prefix(") ")) {
        Some((&line[..digits + 2], rest))
    } else {
        None
    }
}

fn prefixed(
    content: &str,
    width: usize,
    first_prefix: &str,
    next_prefix: &str,
    prefix_style: Style,
    content_style: Style,
) -> Vec<Line<'static>> {
    let prefix_width =
        UnicodeWidthStr::width(first_prefix).max(UnicodeWidthStr::width(next_prefix));
    let inner = width.saturating_sub(prefix_width).max(1);
    let mut lines = wrap_segments(&[(content_style, content.to_owned())], inner, true);
    for (index, line) in lines.iter_mut().enumerate() {
        let prefix = if index == 0 {
            first_prefix
        } else {
            next_prefix
        };
        line.spans
            .insert(0, Span::styled(prefix.to_owned(), prefix_style));
    }
    lines
}

fn inline(text: &str, base: Style) -> Vec<(Style, String)> {
    let mut output = Vec::new();
    let mut normal = String::new();
    let mut index = 0;

    while index < text.len() {
        let rest = &text[index..];
        if let Some(after) = rest.strip_prefix('`') {
            if let Some(end) = after.find('`') {
                push_normal(&mut output, &mut normal, base);
                output.push((theme::inline_code(), after[..end].to_owned()));
                index += 1 + end + 1;
                continue;
            }
        } else if let Some(after) = rest.strip_prefix("**") {
            if let Some(end) = after.find("**") {
                push_normal(&mut output, &mut normal, base);
                output.push((base.add_modifier(Modifier::BOLD), after[..end].to_owned()));
                index += 2 + end + 2;
                continue;
            }
        }
        let c = rest.chars().next().unwrap();
        normal.push(c);
        index += c.len_utf8();
    }
    push_normal(&mut output, &mut normal, base);
    output
}

fn push_normal(output: &mut Vec<(Style, String)>, normal: &mut String, style: Style) {
    if normal.is_empty() {
        return;
    }
    output.push((style, std::mem::take(normal)));
}
