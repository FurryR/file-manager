//! A single-file terminal chat UI with a current-thread async runtime. Configuration comes from
//! the hub; credentials are never put in argv, terminal output, or local files.
use std::env;
use std::fs::OpenOptions;
use std::future::Future;
use std::io::{self, Write};
use std::mem;
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::unix::fs::OpenOptionsExt;
use std::pin::Pin;
use std::time::{Duration, Instant};

use hub_client::filehub::{
    hub_request::Op, hub_response, AiConfigResponse, GetAiConfigRequest, HubRequest,
};
use serde::{Deserialize, Serialize};
use tokio::io::unix::AsyncFd;
use tokio::signal::unix::{signal, SignalKind};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

const HELP: &str = "agent - terminal AI chat configured in the file manager\n\nEnter: send   Ctrl+C: cancel request / exit   Ctrl+Q: exit\nBackspace: delete   Ctrl+U: clear input   PageUp/PageDown: scroll\n/clear: clear conversation   /help: shortcuts   /exit: exit\n\nSet the API base URL, API key and model in the app's AI settings first.";
const MAX_RESPONSE: usize = 4 * 1024 * 1024;
type Reply = Pin<Box<dyn Future<Output = Result<String, String>>>>;

#[derive(Clone, Serialize, Deserialize)]
struct Message {
    role: String,
    content: String,
}

#[derive(Deserialize)]
struct Completion {
    choices: Vec<Choice>,
}

#[derive(Deserialize)]
struct Choice {
    message: Message,
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args: Vec<_> = env::args_os().skip(1).collect();
    if args.len() == 1 && (args[0] == "--help" || args[0] == "-h") {
        println!("{HELP}");
        return;
    }
    if !args.is_empty() {
        eprintln!("Usage: agent [--help]");
        std::process::exit(2);
    }
    if let Err(error) = run().await {
        // Errors from HTTP and configuration validation are deliberately redacted.
        eprintln!("agent: {error}");
        std::process::exit(2);
    }
}

fn config_from_hub() -> io::Result<AiConfigResponse> {
    let socket = hub_client::connect_from_env()?;
    let response = hub_client::round_trip(
        socket.as_raw_fd(),
        HubRequest {
            id: 1,
            op: Some(Op::GetAiConfig(GetAiConfigRequest {})),
        },
    )?;
    if !response.ok {
        return Err(io::Error::other(
            "AI configuration is unavailable; check the app's AI settings",
        ));
    }
    match response.result {
        Some(hub_response::Result::AiConfig(config)) => Ok(config),
        _ => Err(io::Error::other("hub returned no AI configuration")),
    }
}

fn endpoint(base: &str) -> io::Result<reqwest::Url> {
    let base = base.trim().trim_end_matches('/');
    let address = if base.ends_with("/chat/completions") {
        base.to_owned()
    } else {
        format!("{base}/chat/completions")
    };
    let url = reqwest::Url::parse(&address)
        .map_err(|_| io::Error::other("invalid API base URL in AI settings"))?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(io::Error::other(
            "AI base URL must be an HTTP(S) URL without credentials, query or fragment",
        ));
    }
    Ok(url)
}

async fn complete(
    client: reqwest::Client,
    url: reqwest::Url,
    key: String,
    model: String,
    messages: Vec<Message>,
) -> Result<String, String> {
    let mut request = client.post(url).json(&serde_json::json!({
        "model": model, "messages": messages, "stream": false,
    }));
    if !key.is_empty() {
        request = request.bearer_auth(key);
    }
    let mut response = request.send().await.map_err(|error| {
        if error.is_timeout() {
            "Request timed out; try again."
        } else {
            "Connection failed; check the API address, network and TLS certificate."
        }
        .to_owned()
    })?;
    if !response.status().is_success() {
        // Never print a server error body: it can echo the Authorization header.
        return Err(format!(
            "API returned HTTP {}; check the model, API key and provider limits.",
            response.status().as_u16()
        ));
    }
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RESPONSE as u64)
    {
        return Err("API response is too large.".to_owned());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "Could not read the API response.".to_owned())?
    {
        if bytes.len() + chunk.len() > MAX_RESPONSE {
            return Err("API response is too large.".to_owned());
        }
        bytes.extend_from_slice(&chunk);
    }
    let reply: Completion = serde_json::from_slice(&bytes)
        .map_err(|_| "API returned an invalid Chat Completions response.".to_owned())?;
    reply
        .choices
        .into_iter()
        .next()
        .map(|choice| choice.message.content)
        .filter(|content| !content.trim().is_empty())
        .ok_or_else(|| "API returned no assistant message.".to_owned())
}

async fn run() -> io::Result<()> {
    let config = config_from_hub()?;
    if config.model.trim().is_empty() {
        return Err(io::Error::other(
            "set a model in the app's AI settings first",
        ));
    }
    let url = endpoint(&config.base_url)?;
    // Use Android's system getaddrinfo resolver so DNS follows its active
    // network/VPN. Reqwest delegates this blocking OS call to Tokio's blocking
    // pool; the UI/network task runtime remains current_thread. Android does
    // not provide the /etc/resolv.conf used by alternate DNS resolvers.
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(180))
        .build()
        .map_err(|_| io::Error::other("could not initialize the HTTP client"))?;
    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut hangup = signal(SignalKind::hangup())?;
    let mut quit = signal(SignalKind::quit())?;
    let mut suspend = signal(SignalKind::from_raw(libc::SIGTSTP))?;
    let mut resize = signal(SignalKind::window_change())?;
    let terminal = Terminal::enter()?;
    let mut input = String::new();
    let mut parser = Input::default();
    let mut history: Vec<Message> = Vec::new();
    let mut transcript = vec![(
        "agent".to_owned(),
        "Ready. Type a message; /help shows shortcuts.".to_owned(),
    )];
    let mut pending: Option<Reply> = None;
    let mut submitted = String::new();
    let mut started = Instant::now();
    let mut notice =
        "Enter send | Ctrl+C cancel/exit | Ctrl+Q exit | PageUp/PageDown scroll".to_owned();
    let mut scroll = 0usize;
    let mut ticker = tokio::time::interval(Duration::from_millis(250));
    loop {
        render(
            &terminal,
            &config.model,
            &transcript,
            &input,
            &notice,
            scroll,
            pending.is_some().then_some(started.elapsed()),
        )?;
        tokio::select! {
            _ = terminate.recv() => break,
            _ = interrupt.recv() => break,
            _ = hangup.recv() => break,
            _ = quit.recv() => break,
            _ = suspend.recv() => break,
            _ = resize.recv() => {},
            _ = ticker.tick(), if pending.is_some() => {},
            reply = async { pending.as_mut().unwrap().await }, if pending.is_some() => {
                pending = None;
                match reply {
                    Ok(answer) => {
                        history.push(Message { role: "user".to_owned(), content: mem::take(&mut submitted) });
                        history.push(Message { role: "assistant".to_owned(), content: answer.clone() });
                        transcript.push(("assistant".to_owned(), answer));
                        notice = "Enter send | Ctrl+C exit | /clear clear conversation".to_owned();
                    }
                    Err(error) => {
                        transcript.push(("error".to_owned(), error));
                        if input.is_empty() { input = mem::take(&mut submitted); }
                        notice = "Request failed; edit your message and press Enter to retry.".to_owned();
                    }
                }
                scroll = 0;
            },
            bytes = terminal.read() => {
                let bytes = bytes?;
                if bytes.is_empty() { break; }
                for byte in bytes {
                    match parser.feed(byte) {
                        Some(Key::Exit) => return Ok(()),
                        Some(Key::Cancel) => {
                            if pending.take().is_some() {
                                if input.is_empty() { input = mem::take(&mut submitted); }
                                transcript.push(("agent".to_owned(), "Request cancelled.".to_owned()));
                                notice = "Request cancelled. Enter retries; Ctrl+C exits.".to_owned();
                            } else { return Ok(()); }
                        }
                        Some(Key::Clear) => input.clear(),
                        Some(Key::Backspace) => {
                            if let Some((start, _)) = input.grapheme_indices(true).next_back() { input.truncate(start); }
                        }
                        Some(Key::Text(text)) => {
                            if input.len() + text.len() <= 64 * 1024 { input.push_str(&text); }
                            else { notice = "Message reached the 64 KiB input limit.".to_owned(); }
                        }
                        Some(Key::Scroll(up)) => {
                            let page = terminal.size().0.saturating_sub(6).max(1);
                            scroll = if up { scroll.saturating_add(page) } else { scroll.saturating_sub(page) };
                        }
                        Some(Key::Submit) => {
                            if pending.is_some() {
                                notice = "Waiting for the reply. Ctrl+C cancels; Ctrl+Q exits.".to_owned();
                                continue;
                            }
                            match input.trim() {
                                "" => continue,
                                "/exit" | "/quit" => return Ok(()),
                                "/clear" => {
                                    history.clear(); transcript.clear(); input.clear(); scroll = 0;
                                    notice = "Conversation cleared.".to_owned();
                                    continue;
                                }
                                "/help" => {
                                    transcript.push(("agent".to_owned(), HELP.to_owned()));
                                    input.clear(); scroll = 0;
                                    continue;
                                }
                                _ => {}
                            }
                            submitted = mem::take(&mut input);
                            transcript.push(("you".to_owned(), submitted.clone()));
                            let mut messages = history.clone();
                            messages.push(Message { role: "user".to_owned(), content: submitted.clone() });
                            pending = Some(Box::pin(complete(client.clone(), url.clone(), config.api_key.clone(),
                                config.model.clone(), messages)));
                            started = Instant::now(); scroll = 0;
                            notice = "Waiting for the reply. Ctrl+C cancels; Ctrl+Q exits.".to_owned();
                        }
                        None => {}
                    }
                }
            }
        }
    }
    Ok(())
}

struct Terminal {
    input: AsyncFd<OwnedFd>,
    original: libc::termios,
}

impl Terminal {
    fn enter() -> io::Result<Self> {
        if unsafe { libc::isatty(libc::STDOUT_FILENO) } != 1 {
            return Err(io::Error::other("an interactive terminal is required"));
        }
        // A fresh tty open avoids changing O_NONBLOCK on the parent shell's fd.
        let tty: OwnedFd = OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(libc::O_NONBLOCK | libc::O_NOCTTY | libc::O_CLOEXEC)
            .open("/dev/tty")?
            .into();
        let input = AsyncFd::new(tty)?;
        let mut original = unsafe { mem::zeroed::<libc::termios>() };
        if unsafe { libc::tcgetattr(input.as_raw_fd(), &mut original) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let mut raw = original;
        unsafe { libc::cfmakeraw(&mut raw) };
        if unsafe { libc::tcsetattr(input.as_raw_fd(), libc::TCSANOW, &raw) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let terminal = Self { input, original };
        print!("\x1b[?1049h\x1b[?2004h\x1b[?25l");
        io::stdout().flush()?;
        Ok(terminal)
    }

    fn size(&self) -> (usize, usize) {
        let mut size = unsafe { mem::zeroed::<libc::winsize>() };
        if unsafe { libc::ioctl(self.input.as_raw_fd(), libc::TIOCGWINSZ, &mut size) } == 0 {
            (
                usize::from(size.ws_row).max(4),
                usize::from(size.ws_col).max(4),
            )
        } else {
            (24, 80)
        }
    }

    async fn read(&self) -> io::Result<Vec<u8>> {
        loop {
            let mut ready = self.input.readable().await?;
            match ready.try_io(|fd| {
                let mut bytes = vec![0u8; 4096];
                let count =
                    unsafe { libc::read(fd.as_raw_fd(), bytes.as_mut_ptr().cast(), bytes.len()) };
                if count < 0 {
                    return Err(io::Error::last_os_error());
                }
                bytes.truncate(count as usize);
                Ok(bytes)
            }) {
                Ok(Err(error)) if error.kind() == io::ErrorKind::Interrupted => continue,
                Ok(result) => return result,
                Err(_) => continue,
            }
        }
    }
}

impl Drop for Terminal {
    fn drop(&mut self) {
        unsafe { libc::tcsetattr(self.input.as_raw_fd(), libc::TCSANOW, &self.original) };
        let mut output = io::stdout();
        let _ = output.write_all(b"\x1b[0m\x1b[?25h\x1b[?2004l\x1b[?1049l");
        let _ = output.flush();
    }
}

enum Key {
    Text(String),
    Submit,
    Backspace,
    Clear,
    Cancel,
    Exit,
    Scroll(bool),
}

#[derive(Default)]
struct Input {
    utf8: Vec<u8>,
    escape: Vec<u8>,
    paste: bool,
}

impl Input {
    fn feed(&mut self, byte: u8) -> Option<Key> {
        if !self.escape.is_empty() {
            self.escape.push(byte);
            if self.escape.len() == 2 && matches!(byte, b'[' | b'O') {
                return None;
            }
            if self.escape.len() >= 3 && !(0x40..=0x7e).contains(&byte) && self.escape.len() < 32 {
                return None;
            }
            let sequence = mem::take(&mut self.escape);
            return match sequence.as_slice() {
                b"\x1b[200~" => {
                    self.paste = true;
                    None
                }
                b"\x1b[201~" => {
                    self.paste = false;
                    None
                }
                b"\x1b[5~" if !self.paste => Some(Key::Scroll(true)),
                b"\x1b[6~" if !self.paste => Some(Key::Scroll(false)),
                _ => None,
            };
        }
        if byte == 27 {
            self.escape.push(byte);
            self.utf8.clear();
            return None;
        }
        if self.paste && matches!(byte, b'\r' | b'\n' | b'\t') {
            return Some(Key::Text(
                if byte == b'\t' { "    " } else { "\n" }.to_owned(),
            ));
        }
        if byte < 32 || byte == 127 {
            self.utf8.clear();
            if self.paste {
                return None;
            }
            return match byte {
                b'\r' | b'\n' => Some(Key::Submit),
                8 | 127 => Some(Key::Backspace),
                3 => Some(Key::Cancel),
                4 | 17 => Some(Key::Exit),
                21 => Some(Key::Clear),
                16 => Some(Key::Scroll(true)),
                14 => Some(Key::Scroll(false)),
                _ => None,
            };
        }
        self.utf8.push(byte);
        match std::str::from_utf8(&self.utf8) {
            Ok(text) => {
                let text = text.to_owned();
                self.utf8.clear();
                Some(Key::Text(text))
            }
            Err(error) if error.error_len().is_none() => None,
            Err(_) => {
                self.utf8.clear();
                None
            }
        }
    }
}

// Strip control characters before writing model/provider text to a terminal.
fn wrap(text: &str, width: usize) -> Vec<String> {
    let mut rows = vec![String::new()];
    let mut used = 0;
    for grapheme in text.graphemes(true) {
        if grapheme == "\n" || grapheme == "\r\n" {
            rows.push(String::new());
            used = 0;
            continue;
        }
        let clean: String = grapheme.chars().filter(|c| !c.is_control()).collect();
        let size = UnicodeWidthStr::width(clean.as_str());
        if used + size > width {
            rows.push(String::new());
            used = 0;
        }
        rows.last_mut().unwrap().push_str(&clean);
        used += size;
    }
    rows
}

fn render(
    terminal: &Terminal,
    model: &str,
    transcript: &[(String, String)],
    input: &str,
    notice: &str,
    scroll: usize,
    elapsed: Option<Duration>,
) -> io::Result<()> {
    let (height, columns) = terminal.size();
    // Reserve the last column to avoid implicit wrapping at a full-width line.
    let width = columns.saturating_sub(1).max(1);
    let prompt = wrap(&format!("> {input}"), width);
    let prompt_count = prompt.len().min(3).min(height.saturating_sub(3).max(1));
    let body_height = height.saturating_sub(prompt_count + 2);
    let mut body = Vec::new();
    for (role, content) in transcript {
        body.extend(wrap(&format!("{role}: {content}"), width));
        body.push(String::new());
    }
    let offset = scroll.min(body.len().saturating_sub(body_height));
    let end = body.len().saturating_sub(offset);
    let start = end.saturating_sub(body_height);
    let title = format!(
        "agent | {model}{}",
        if offset > 0 { " | history" } else { "" }
    );
    let mut output = format!(
        "\x1b[?25l\x1b[H\x1b[2K\x1b[1m{}\x1b[0m",
        wrap(&title, width)[0]
    );
    for index in 0..body_height {
        output.push_str("\r\n\x1b[2K");
        if let Some(line) = body.get(start + index).filter(|_| start + index < end) {
            output.push_str(line);
        }
    }
    let status = match elapsed {
        Some(elapsed) => format!("Waiting {}s | {notice}", elapsed.as_secs()),
        None => notice.to_owned(),
    };
    output.push_str(&format!(
        "\r\n\x1b[2K\x1b[2m{}\x1b[0m",
        wrap(&status, width)[0]
    ));
    for line in prompt.iter().skip(prompt.len() - prompt_count) {
        output.push_str("\r\n\x1b[2K");
        output.push_str(line);
    }
    output.push_str("\x1b[J\x1b[?25h");
    let mut stdout = io::stdout().lock();
    stdout.write_all(output.as_bytes())?;
    stdout.flush()
}
