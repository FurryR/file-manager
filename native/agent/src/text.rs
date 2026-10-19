use ratatui::style::Style;
use ratatui::text::{Line, Span};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

pub fn sanitize(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut chars = input.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                output.push('\n');
            }
            '\n' => output.push('\n'),
            '\t' => output.push_str("    "),
            c if c.is_control() => {}
            c => output.push(c),
        }
    }
    output
}

struct Cell {
    style: Style,
    grapheme: String,
    width: usize,
}

pub fn wrap_segments(
    segments: &[(Style, String)],
    width: usize,
    word_wrap: bool,
) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut output = Vec::new();
    let mut cells: Vec<Cell> = Vec::new();
    let mut used = 0usize;

    for (style, text) in segments {
        for grapheme in text.graphemes(true) {
            let cell_width = UnicodeWidthStr::width(grapheme);
            if used + cell_width > width && used > 0 {
                if word_wrap {
                    if let Some(split) = word_break(&cells) {
                        let tail = cells.split_off(split + 1);
                        cells.truncate(split);
                        if !cells.is_empty() {
                            output.push(line_from_cells(&cells));
                        }
                        cells = tail;
                        used = cells.iter().map(|cell| cell.width).sum();
                    }
                }
                if used + cell_width > width && used > 0 {
                    output.push(line_from_cells(&cells));
                    cells.clear();
                    used = 0;
                }
            }
            if cell_width == 0 {
                if let Some(last) = cells.last_mut() {
                    last.grapheme.push_str(grapheme);
                } else {
                    cells.push(Cell {
                        style: *style,
                        grapheme: grapheme.to_owned(),
                        width: 0,
                    });
                }
                continue;
            }
            cells.push(Cell {
                style: *style,
                grapheme: grapheme.to_owned(),
                width: cell_width,
            });
            used += cell_width;
        }
    }

    if !cells.is_empty() || output.is_empty() {
        output.push(line_from_cells(&cells));
    }
    output
}

fn word_break(cells: &[Cell]) -> Option<usize> {
    cells
        .iter()
        .rposition(|cell| cell.grapheme.chars().all(char::is_whitespace))
        .filter(|index| index + 1 < cells.len())
}

fn line_from_cells(cells: &[Cell]) -> Line<'static> {
    let mut spans: Vec<Span<'static>> = Vec::new();
    let mut style: Option<Style> = None;
    let mut buffer = String::new();
    for cell in cells {
        if style != Some(cell.style) {
            if let Some(previous) = style {
                spans.push(Span::styled(std::mem::take(&mut buffer), previous));
            }
            style = Some(cell.style);
        }
        buffer.push_str(&cell.grapheme);
    }
    if let Some(previous) = style {
        spans.push(Span::styled(buffer, previous));
    }
    Line::from(spans)
}
