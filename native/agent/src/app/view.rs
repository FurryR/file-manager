use super::App;

impl App {
    pub(crate) fn set_transcript_map(
        &mut self,
        top: u16,
        height: u16,
        reasoning_headers: Vec<(usize, usize)>,
        tool_headers: Vec<(usize, usize)>,
    ) {
        self.transcript_top = top;
        self.transcript_height = height;
        self.reasoning_headers = reasoning_headers;
        self.tool_headers = tool_headers;
    }

    pub(crate) fn sync_view(&mut self, content_height: usize, viewport_height: usize) {
        self.content_height = content_height;
        self.viewport_height = viewport_height;
        let maximum = content_height.saturating_sub(viewport_height);
        if self.follow {
            self.scroll = maximum;
        } else {
            self.scroll = self.scroll.min(maximum);
            if self.scroll == maximum {
                self.follow = true;
            }
        }
    }

    pub(super) fn scroll_page(&mut self, up: bool) {
        let page = self.viewport_height.saturating_sub(1).max(1);
        self.scroll_by(page, up);
    }

    pub(super) fn scroll_lines(&mut self, up: bool, lines: usize) {
        self.scroll_by(lines.max(1), up);
    }

    fn scroll_by(&mut self, amount: usize, up: bool) {
        let maximum = self.content_height.saturating_sub(self.viewport_height);
        if maximum == 0 {
            self.scroll = 0;
            self.follow = true;
            return;
        }
        if self.follow {
            self.scroll = maximum;
        }
        if up {
            self.follow = false;
            self.scroll = self.scroll.saturating_sub(amount);
        } else {
            self.scroll = (self.scroll + amount).min(maximum);
            if self.scroll >= maximum {
                self.follow = true;
            }
        }
    }
}
