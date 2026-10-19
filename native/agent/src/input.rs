use ratatui::layout::Position;
use ratatui::text::{Line, Span};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use crate::text;
use crate::theme;

const MAX_BYTES: usize = 64 * 1024;

#[derive(Default)]
pub struct Input {
    text: String,
    cursor: usize,
}

pub struct InputView {
    pub lines: Vec<Line<'static>>,
    pub cursor: Option<Position>,
    pub total_rows: usize,
    pub first_row: usize,
}

impl Input {
    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }

    pub fn clear(&mut self) {
        self.text.clear();
        self.cursor = 0;
    }

    pub fn set(&mut self, value: String) {
        let mut clean = text::sanitize(&value);
        if clean.len() > MAX_BYTES {
            clean.truncate(MAX_BYTES);
            while !clean.is_char_boundary(clean.len()) {
                clean.pop();
            }
        }
        self.cursor = clean.len();
        self.text = clean;
    }

    pub fn take(&mut self) -> String {
        self.cursor = 0;
        std::mem::take(&mut self.text)
    }

    pub fn insert(&mut self, value: &str) -> bool {
        let clean = text::sanitize(value);
        if clean.is_empty() {
            return true;
        }
        if self.text.len() + clean.len() > MAX_BYTES {
            return false;
        }
        self.text.insert_str(self.cursor, &clean);
        self.cursor += clean.len();
        true
    }

    pub fn backspace(&mut self) {
        if let Some((start, _)) = self.text[..self.cursor].grapheme_indices(true).next_back() {
            self.text.replace_range(start..self.cursor, "");
            self.cursor = start;
        }
    }

    pub fn delete(&mut self) {
        if let Some(grapheme) = self.text[self.cursor..].graphemes(true).next() {
            let end = self.cursor + grapheme.len();
            self.text.replace_range(self.cursor..end, "");
        }
    }

    pub fn left(&mut self) {
        if let Some((start, _)) = self.text[..self.cursor].grapheme_indices(true).next_back() {
            self.cursor = start;
        }
    }

    pub fn right(&mut self) {
        if let Some(grapheme) = self.text[self.cursor..].graphemes(true).next() {
            self.cursor += grapheme.len();
        }
    }

    pub fn home(&mut self) {
        self.cursor = self.text[..self.cursor]
            .rfind('\n')
            .map(|index| index + 1)
            .unwrap_or(0);
    }

    pub fn end(&mut self) {
        self.cursor = self.text[self.cursor..]
            .find('\n')
            .map(|index| self.cursor + index)
            .unwrap_or(self.text.len());
    }

    pub fn up(&mut self) {
        let start = self.line_start();
        if start == 0 {
            return;
        }
        let column = self.text[start..self.cursor].graphemes(true).count();
        let previous_end = start - 1;
        let previous_start = self.text[..previous_end]
            .rfind('\n')
            .map(|index| index + 1)
            .unwrap_or(0);
        let mut position = previous_start;
        for _ in 0..column {
            if position >= previous_end {
                break;
            }
            let grapheme = self.text[position..previous_end]
                .graphemes(true)
                .next()
                .unwrap();
            position += grapheme.len();
        }
        self.cursor = position;
    }

    pub fn down(&mut self) {
        let end = self.line_end();
        if end >= self.text.len() {
            return;
        }
        let start = self.line_start();
        let column = self.text[start..self.cursor].graphemes(true).count();
        let next_start = end + 1;
        let next_end = self.text[next_start..]
            .find('\n')
            .map(|index| next_start + index)
            .unwrap_or(self.text.len());
        let mut position = next_start;
        for _ in 0..column {
            if position >= next_end {
                break;
            }
            let grapheme = self.text[position..next_end]
                .graphemes(true)
                .next()
                .unwrap();
            position += grapheme.len();
        }
        self.cursor = position;
    }

    pub fn delete_word(&mut self) {
        if self.cursor == 0 {
            return;
        }
        let indices: Vec<(usize, &str)> = self.text[..self.cursor].grapheme_indices(true).collect();
        let mut index = indices.len();
        while index > 0 && indices[index - 1].1.chars().all(char::is_whitespace) {
            index -= 1;
        }
        while index > 0 && !indices[index - 1].1.chars().all(char::is_whitespace) {
            index -= 1;
        }
        let start = indices.get(index).map(|(offset, _)| *offset).unwrap_or(0);
        self.text.replace_range(start..self.cursor, "");
        self.cursor = start;
    }

    pub fn view(&self, width: usize, max_rows: usize) -> InputView {
        let width = width.max(1);
        let text_width = width.saturating_sub(2).max(1);
        let mut rows: Vec<String> = Vec::new();
        let mut row = String::new();
        let mut column = 0usize;
        let mut cursor_row = 0usize;
        let mut cursor_column = 0usize;
        let mut found = false;

        for (index, grapheme) in self.text.grapheme_indices(true) {
            if grapheme == "\n" {
                if index == self.cursor {
                    cursor_row = rows.len();
                    cursor_column = column;
                    found = true;
                }
                rows.push(std::mem::take(&mut row));
                column = 0;
                continue;
            }
            let grapheme_width = UnicodeWidthStr::width(grapheme);
            if column + grapheme_width > text_width && column > 0 {
                rows.push(std::mem::take(&mut row));
                column = 0;
            }
            if index == self.cursor {
                cursor_row = rows.len();
                cursor_column = column;
                found = true;
            }
            row.push_str(grapheme);
            column += grapheme_width;
        }

        if !found {
            cursor_row = rows.len();
            cursor_column = column;
        }
        rows.push(row);

        let total_rows = rows.len();
        let visible_rows = max_rows.max(1).min(total_rows);
        let first_row = if cursor_row >= visible_rows {
            cursor_row + 1 - visible_rows
        } else {
            0
        };
        let first_row = first_row.min(total_rows.saturating_sub(visible_rows));

        let mut lines = Vec::with_capacity(visible_rows);
        for (offset, row) in rows[first_row..first_row + visible_rows].iter().enumerate() {
            let index = first_row + offset;
            let (prefix, style) = if index == 0 {
                ("❯ ", theme::prompt())
            } else {
                ("  ", theme::muted())
            };
            lines.push(Line::from(vec![
                Span::styled(prefix, style),
                Span::raw(row.clone()),
            ]));
        }

        let cursor = if cursor_row >= first_row && cursor_row < first_row + visible_rows {
            Some(Position::new(
                (2usize + cursor_column).min(width.saturating_sub(1)) as u16,
                (cursor_row - first_row) as u16,
            ))
        } else {
            None
        };

        InputView {
            lines,
            cursor,
            total_rows,
            first_row,
        }
    }

    fn line_start(&self) -> usize {
        self.text[..self.cursor]
            .rfind('\n')
            .map(|index| index + 1)
            .unwrap_or(0)
    }

    fn line_end(&self) -> usize {
        self.text[self.cursor..]
            .find('\n')
            .map(|index| self.cursor + index)
            .unwrap_or(self.text.len())
    }
}
