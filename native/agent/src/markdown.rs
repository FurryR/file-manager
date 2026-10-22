use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use crate::model::{Entry, Role};
use crate::text::{sanitize, wrap_segments};
use crate::theme;

mod inline;
mod table;

use inline::inline;
use table::{is_table_line, render_table};

pub struct Rendered {
    pub lines: Vec<Line<'static>>,
    pub reasoning_headers: Vec<(usize, usize)>,
    pub tool_headers: Vec<(usize, usize)>,
}

const EXEC_LEFT_PAD: usize = 3;
const EXEC_RIGHT_PAD: usize = 2;
const EXEC_PREVIEW_LINES: usize = 8;
const EXEC_EXPANDED_LINES: usize = 400;
const EXEC_PREVIEW_BYTES: usize = 64 * 1024;
const EXEC_EXPANDED_BYTES: usize = 256 * 1024;

pub fn render(entries: &[Entry], width: usize, spinner: usize) -> Rendered {
    let width = width.max(1);
    let mut output = Vec::new();
    let mut reasoning_headers = Vec::new();
    let mut tool_headers = Vec::new();
    if !entries.is_empty() {
        output.push(Line::default());
    }
    for (index, entry) in entries.iter().enumerate() {
        if index > 0 {
            output.push(Line::default());
        }
        match entry.role {
            Role::Reasoning => render_reasoning(
                &mut output,
                &mut reasoning_headers,
                index,
                entry,
                width,
                spinner,
            ),
            Role::User => render_user(&mut output, &entry.content, width),
            Role::Assistant => {
                render_entry(&mut output, &entry.content, width, theme::text());
            }
            Role::Tool => render_tool(&mut output, &mut tool_headers, index, entry, width, spinner),
            Role::Notice => {
                render_entry(&mut output, &entry.content, width, theme::notice());
            }
            Role::Error => render_error(&mut output, &entry.content, width),
        }
    }
    Rendered {
        lines: output,
        reasoning_headers,
        tool_headers,
    }
}

fn render_user(output: &mut Vec<Line<'static>>, content: &str, width: usize) {
    let start = output.len();
    render_entry(
        output,
        content,
        width.saturating_sub(3).max(1),
        theme::user(),
    );
    if output.len() > start {
        let text: String = output[start]
            .spans
            .iter()
            .map(|span| span.content.as_ref())
            .collect();
        let trimmed = text.trim();
        if trimmed.is_empty() || trimmed == "┃" {
            output.remove(start);
        }
    }
    output.insert(start, Line::default());
    for line in &mut output[start..] {
        *line = user_line(std::mem::take(line), width);
    }
    output.push(user_line(Line::default(), width));
}

fn user_line(mut line: Line<'static>, width: usize) -> Line<'static> {
    if let Some(first) = line.spans.first_mut() {
        if let Some(rest) = first.content.strip_prefix('┃') {
            first.content = rest.trim_start().to_owned().into();
            if first.content.is_empty() {
                line.spans.remove(0);
            }
        }
    }
    let used: usize = line
        .spans
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum();
    let mut spans = vec![
        Span::styled("┃", Style::new().fg(theme::ACCENT).bg(theme::USER_BG)),
        Span::styled("  ", theme::user()),
    ];
    spans.extend(line.spans);
    let used = used + 3;
    if used < width {
        spans.push(Span::styled(" ".repeat(width - used), theme::user()));
    }
    Line::from(spans).style(theme::user())
}

fn render_error(output: &mut Vec<Line<'static>>, content: &str, width: usize) {
    let start = output.len();
    render_entry(
        output,
        content,
        width.saturating_sub(3).max(1),
        theme::error(),
    );
    output.insert(start, Line::default());
    for line in &mut output[start..] {
        *line = error_line(std::mem::take(line), width);
    }
    output.push(error_line(Line::default(), width));
}

fn error_line(line: Line<'static>, width: usize) -> Line<'static> {
    let used: usize = line
        .spans
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum();
    let mut spans = vec![
        Span::styled("┃", Style::new().fg(theme::ERROR).bg(theme::BLOCK_BG)),
        Span::styled("  ", theme::error_bg()),
    ];
    spans.extend(line.spans);
    let used = used + 3;
    if used < width {
        spans.push(Span::styled(" ".repeat(width - used), theme::error_bg()));
    }
    Line::from(spans).style(theme::error_bg())
}

fn render_tool(
    output: &mut Vec<Line<'static>>,
    headers: &mut Vec<(usize, usize)>,
    index: usize,
    entry: &Entry,
    width: usize,
    spinner: usize,
) {
    if let Some(write) = &entry.write {
        render_write(output, entry, write, width);
        return;
    }
    let Some(tool) = &entry.tool else {
        let style = if entry.started.is_some() {
            theme::tool_active()
        } else {
            theme::muted()
        };
        output.push(Line::from(Span::styled(fit(&entry.content, width), style)));
        return;
    };

    // The whole block is the expand/collapse hit target, including its padding
    // rows and wrapped output lines.
    let block_start = output.len();
    // One row of breathing room above the block; the transcript already spaces
    // entries apart, but the block itself stays padded even when it is the
    // first or last entry.
    output.push(exec_block_line(Vec::new(), width));
    if let Some(description) = tool.description.as_deref() {
        exec_marked(
            output,
            width,
            "# ",
            description,
            theme::muted(),
            theme::muted(),
        );
        output.push(exec_block_line(Vec::new(), width));
    }
    let running = entry.started.is_some() && !tool.failed;
    let command_style = if tool.failed {
        theme::error()
    } else if running {
        theme::tool_active()
    } else {
        theme::text()
    };
    let (marker, marker_style) = if running {
        (format!("{} ", theme::spinner(spinner)), theme::loading())
    } else {
        ("$ ".to_owned(), theme::accent())
    };
    exec_marked(
        output,
        width,
        &marker,
        &tool.command,
        command_style,
        marker_style,
    );
    output.push(exec_block_line(Vec::new(), width));

    let mut text = display_bytes(&tool.output);
    let (limit, byte_limit) = if entry.expanded {
        (EXEC_EXPANDED_LINES, EXEC_EXPANDED_BYTES)
    } else {
        (EXEC_PREVIEW_LINES, EXEC_PREVIEW_BYTES)
    };
    let truncated = text.len() > byte_limit;
    if truncated {
        let mut end = byte_limit;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
    }
    let (rendered, line_truncated) = plain_lines(&text, width, theme::exec_output(), limit);
    for line in rendered {
        output.push(exec_block_line(line.spans, width));
    }
    let clipped = truncated || line_truncated;
    let expandable = entry.expanded || clipped;
    if clipped {
        output.push(exec_block_line(
            vec![Span::styled("…", theme::muted())],
            width,
        ));
    }

    output.push(exec_block_line(Vec::new(), width));
    if expandable {
        let label = if entry.expanded {
            "Click to collapse"
        } else {
            "Click to expand"
        };
        let style = if tool.failed {
            theme::error()
        } else {
            theme::muted()
        };
        output.push(exec_block_line(vec![Span::styled(label, style)], width));
        output.push(exec_block_line(Vec::new(), width));
        headers.extend((block_start..output.len()).map(|line| (line, index)));
    }
}

fn render_write(
    output: &mut Vec<Line<'static>>,
    entry: &Entry,
    write: &crate::model::WriteBlock,
    width: usize,
) {
    output.push(exec_block_line(Vec::new(), width));
    let path_style = if write.failed {
        theme::error()
    } else if entry.started.is_some() {
        theme::tool_active()
    } else {
        theme::text()
    };
    exec_marked(
        output,
        width,
        "✎ ",
        &write.path,
        path_style,
        if write.failed {
            theme::error()
        } else {
            theme::accent()
        },
    );
    output.push(exec_block_line(Vec::new(), width));

    if let Some(diff) = &write.diff {
        if diff.is_empty() {
            output.push(exec_block_line(
                vec![Span::styled("No changes", theme::muted())],
                width,
            ));
        } else {
            write_lines(output, width, diff, true);
        }
    } else {
        write_lines(output, width, &write.content, false);
    }

    output.push(exec_block_line(Vec::new(), width));
}

fn write_lines(output: &mut Vec<Line<'static>>, width: usize, text: &str, diff: bool) {
    for line in text.lines() {
        if line.is_empty() {
            output.push(exec_block_line(Vec::new(), width));
            continue;
        }
        let style = if diff {
            match line.as_bytes().first() {
                Some(b'+') => theme::diff_add(),
                Some(b'-') => theme::diff_del(),
                _ => theme::exec_output(),
            }
        } else {
            theme::exec_output()
        };
        let (wrapped, _) = plain_lines(line, width, style, usize::MAX);
        for wrapped_line in wrapped {
            output.push(exec_block_line(wrapped_line.spans, width));
        }
    }
}

fn display_bytes(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(text) => text.to_owned(),
        Err(error) => String::from_utf8_lossy(&bytes[..error.valid_up_to()]).into_owned(),
    }
}

fn exec_block_line(mut spans: Vec<Span<'static>>, width: usize) -> Line<'static> {
    for span in &mut spans {
        span.style = span.style.bg(theme::EXEC_BG);
    }
    let mut line = vec![
        Span::styled("┃", theme::exec_bar()),
        Span::styled("  ", theme::exec()),
    ];
    line.append(&mut spans);
    let used: usize = line
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum();
    if used < width {
        line.push(Span::styled(" ".repeat(width - used), theme::exec()));
    }
    Line::from(line).style(theme::exec())
}

fn exec_marked(
    output: &mut Vec<Line<'static>>,
    width: usize,
    marker: &str,
    text: &str,
    style: Style,
    marker_style: Style,
) {
    let marker_width = UnicodeWidthStr::width(marker);
    let inner = width
        .saturating_sub(EXEC_LEFT_PAD + EXEC_RIGHT_PAD + marker_width)
        .max(1);
    let clean = sanitize(text);
    let mut lines = wrap_segments(&[(style, clean)], inner, true);
    for (line_index, line) in lines.iter_mut().enumerate() {
        let mut spans = Vec::with_capacity(line.spans.len() + 1);
        if line_index == 0 {
            spans.push(Span::styled(marker.to_owned(), marker_style));
        } else {
            spans.push(Span::styled(" ".repeat(marker_width), style));
        }
        spans.append(&mut line.spans);
        output.push(exec_block_line(spans, width));
    }
}

fn plain_lines(text: &str, width: usize, style: Style, limit: usize) -> (Vec<Line<'static>>, bool) {
    let inner = width.saturating_sub(EXEC_LEFT_PAD + EXEC_RIGHT_PAD).max(1);
    let clean = sanitize(text);
    let mut source: Vec<&str> = clean.split('\n').collect();
    while source.last() == Some(&"") {
        source.pop();
    }
    let mut output = Vec::new();
    for line in source {
        let line = line.trim_end();
        let mut wrapped = wrap_segments(&[(style, line.to_owned())], inner, false);
        for line in &mut wrapped {
            if output.len() >= limit {
                return (output, true);
            }
            output.push(std::mem::take(line));
        }
    }
    (output, false)
}

pub(crate) fn fit(text: &str, width: usize) -> String {
    let clean = sanitize(text).replace('\n', " ");
    let width = width.max(1);
    let mut output = String::new();
    let mut used = 0usize;
    for grapheme in clean.graphemes(true) {
        let cell = UnicodeWidthStr::width(grapheme);
        if used + cell > width {
            if used < width {
                output.push('…');
            }
            break;
        }
        output.push_str(grapheme);
        used += cell;
    }
    output
}

fn render_reasoning(
    output: &mut Vec<Line<'static>>,
    headers: &mut Vec<(usize, usize)>,
    index: usize,
    entry: &Entry,
    width: usize,
    spinner: usize,
) {
    let label = match entry.duration {
        Some(duration) if duration.as_secs() < 1 => {
            format!("Thought ({}ms)", duration.as_millis())
        }
        Some(duration) => format!("Thought ({:.1}s)", duration.as_secs_f64()),
        None if entry.started.is_some() => format!("{} Thinking", theme::spinner(spinner)),
        None => "Thought".to_owned(),
    };
    let sign = if entry.duration.is_none() {
        ""
    } else if entry.expanded {
        "- "
    } else {
        "+ "
    };
    let style = if entry.expanded {
        theme::reasoning_dim()
    } else {
        theme::reasoning()
    };
    headers.push((output.len(), index));
    output.push(Line::from(Span::styled(format!("  {sign}{label}"), style)));
    if entry.expanded {
        output.push(Line::default());
        let start = output.len();
        render_entry(
            output,
            &entry.content,
            width.saturating_sub(2).max(1),
            theme::text(),
        );
        for line in &mut output[start..] {
            line.style = line.style.add_modifier(Modifier::DIM);
            line.spans.insert(0, Span::styled("  ", theme::text()));
        }
    }
}

fn render_entry(output: &mut Vec<Line<'static>>, content: &str, width: usize, style: Style) {
    let clean = sanitize(content);
    let lines: Vec<&str> = clean.split('\n').map(|line| line.trim_end()).collect();
    let start = output.len();
    let mut in_code = false;
    let mut index = 0;

    while index < lines.len() {
        let line = lines[index];
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
            index += 1;
            continue;
        }
        if in_code {
            output.extend(code_lines(line, width));
            index += 1;
            continue;
        }
        if is_table_line(line) {
            let mut end = index + 1;
            while end < lines.len() && is_table_line(lines[end]) {
                end += 1;
            }
            render_table(output, &lines[index..end], width, style);
            index = end;
            continue;
        }
        output.extend(prose_lines(line, width, style));
        index += 1;
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

fn prose_lines(line: &str, width: usize, base: Style) -> Vec<Line<'static>> {
    let trimmed = line.trim_start();
    if trimmed.is_empty() {
        return vec![Line::default()];
    }
    if let Some(rest) = heading(trimmed) {
        let style = base.fg(theme::ACCENT).add_modifier(Modifier::BOLD);
        return wrap_segments(&inline(rest, style), width, true);
    }
    if let Some(rest) = trimmed.strip_prefix("> ") {
        return prefixed(rest, width, "│ ", "  ", theme::muted(), theme::muted());
    }
    if let Some(rest) = trimmed
        .strip_prefix("- ")
        .or_else(|| trimmed.strip_prefix("* "))
        .or_else(|| trimmed.strip_prefix("+ "))
    {
        return prefixed(rest, width, "• ", "  ", theme::accent(), base);
    }
    if let Some((marker, rest)) = numbered(trimmed) {
        let indent = " ".repeat(UnicodeWidthStr::width(marker));
        return prefixed(rest, width, marker, &indent, theme::muted(), base);
    }
    wrap_segments(&inline(trimmed, base), width, true)
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
    let mut lines = wrap_segments(&inline(content, content_style), inner, true);
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
