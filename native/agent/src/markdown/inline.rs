use ratatui::style::{Modifier, Style};

use crate::theme;

pub(super) fn inline(text: &str, base: Style) -> Vec<(Style, String)> {
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
