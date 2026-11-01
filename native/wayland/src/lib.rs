//! Nested Wayland compositor for the app's Android viewer.
//!
//! This crate replaces the prebuilt wlroots stack with a Smithay compositor
//! that owns its Android output surface, its input mapping and — the reason it
//! exists — the output scale, which the old stack hardcoded to 1. The viewer
//! (`WaylandActivity`/`WaylandView`) only attaches a `Surface` and forwards
//! input; every container client is an ordinary Wayland client of the single
//! `wayland-0` socket.
//!
//! Android integration lives behind `cfg(target_os = "android")` (EGL on
//! `ANativeWindow` and the JNI entry points). The protocol/rendering half
//! builds on the host so `cargo check` keeps the Smithay usage honest.

pub mod input;
pub mod keymap;
pub mod render;
pub mod scale;
pub mod state;
pub mod text_input;

#[cfg(target_os = "android")]
pub mod egl;

use std::path::PathBuf;
use std::time::Duration;

use smithay::backend::renderer::gles::GlesRenderer;
use smithay::reexports::calloop::{channel, EventLoop};
use smithay::reexports::wayland_server::Display;
use smithay::wayland::socket::ListeningSocketSource;

use crate::input::Command;
use crate::scale::OutputScale;
use crate::state::{ClientState, State};

/// Everything the compositor needs from the platform side.
pub struct Config {
    /// Directory that holds `wayland-0` and doubles as `XDG_RUNTIME_DIR`.
    pub runtime_dir: PathBuf,
    /// Extracted xkeyboard-config tree (`XKB_CONFIG_ROOT`).
    pub xkb_root: PathBuf,
    /// Initial physical surface size in pixels.
    pub size: (i32, i32),
    /// Device pixel ratio from the Android display density.
    pub scale: f64,
}

/// Bind the socket, build the protocol state and run until stopped.
///
/// `ready` receives the bind result before the event loop starts: the caller
/// uses it to distinguish a live compositor from a stale socket file left by a
/// crashed process, which a `connect()` would otherwise report as ECONNREFUSED.
pub fn run(
    renderer: GlesRenderer,
    config: Config,
    sender: channel::Sender<Command>,
    receiver: channel::Channel<Command>,
    ready: std::sync::mpsc::Sender<Result<(), String>>,
) -> Result<(), Box<dyn std::error::Error>> {
    // Smithay derives both the keymap tempfile location and the socket path
    // from these; on Android there is no /tmp and no HOME worth trusting.
    std::env::set_var("XDG_RUNTIME_DIR", &config.runtime_dir);
    std::env::set_var("XKB_CONFIG_ROOT", &config.xkb_root);

    // xkb_context_new returns NULL when it cannot open any include path, and
    // the crash that follows is a bare SIGSEGV. Reject the obvious precondition
    // first so the failure is a log line instead.
    let rules = config.xkb_root.join("rules/evdev");
    if !rules.is_file() {
        let message = format!("xkb data missing at {}", rules.display());
        log::error!("{message}");
        let _ = ready.send(Err(message.clone()));
        return Err(message.into());
    }

    let mut event_loop: EventLoop<State> = match EventLoop::try_new() {
        Ok(loop_) => loop_,
        Err(error) => {
            let _ = ready.send(Err(format!("event loop: {error}")));
            return Err(error.into());
        }
    };
    let mut display: Display<State> = match Display::new() {
        Ok(display) => display,
        Err(error) => {
            let _ = ready.send(Err(format!("wayland display: {error}")));
            return Err(error.into());
        }
    };
    // Smithay panics on a malformed keymap; convert that into a reported
    // startup failure instead of a silent thread death.
    let mut state = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        State::new(
            &mut display,
            renderer,
            sender,
            OutputScale::sanitized(config.scale),
            config.size,
        )
    })) {
        Ok(state) => state,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|value| (*value).to_owned())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_owned());
            let message = format!("protocol state panicked: {detail}");
            let _ = ready.send(Err(message.clone()));
            return Err(message.into());
        }
    };

    let path = config.runtime_dir.join("wayland-0");
    // A socket file from a previous (or crashed) compositor stays behind; the
    // bind then fails with EADDRINUSE while clients still see a path and get
    // ECONNREFUSED. Removing it is safe: this process is the only owner of the
    // viewer socket.
    match std::fs::remove_file(&path) {
        Ok(()) => log::info!("removed stale {}", path.display()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => log::warn!("cannot remove stale {}: {error}", path.display()),
    }
    let socket = match ListeningSocketSource::with_name("wayland-0") {
        Ok(socket) => socket,
        Err(error) => {
            let _ = ready.send(Err(format!("bind {}: {error}", path.display())));
            return Err(error.into());
        }
    };
    // Containers run under a different UID in the chroot backend; 0666 on the
    // socket (and 0755 on its directory) is what lets them connect.
    let _ = std::fs::set_permissions(&path, std::os::unix::fs::PermissionsExt::from_mode(0o666));
    let _ = std::fs::set_permissions(
        &config.runtime_dir,
        std::os::unix::fs::PermissionsExt::from_mode(0o755),
    );

    log::info!("socket bound at {}", path.display());
    event_loop.handle().insert_source(socket, |stream, _, state| {
        match state.display_handle.clone().insert_client(
            stream,
            std::sync::Arc::new(ClientState::default()),
        ) {
            Ok(client) => {
                log::info!("client {:?} connected", client.id());
            }
            Err(error) => log::warn!("cannot accept Wayland client: {error:?}"),
        }
    })?;

    event_loop.handle().insert_source(receiver, |event, _, state| {
        if let channel::Event::Msg(command) = event {
            input::apply(state, command);
        }
    })?;

    // 60 Hz tick: draws only when a commit made the frame dirty.
    let timer = smithay::reexports::calloop::timer::Timer::from_duration(Duration::from_millis(16));
    let start = std::time::Instant::now();
    event_loop.handle().insert_source(timer, move |_, _, state| {
        if state.needs_redraw {
            state.needs_redraw = false;
            if let Err(error) = render::render(state, start.elapsed()) {
                log::warn!("frame failed: {error}");
            }
        }
        let mut handle = state.display_handle.clone();
        let _ = handle.flush_clients();
        smithay::reexports::calloop::timer::TimeoutAction::ToDuration(Duration::from_millis(16))
    })?;

    let _ = ready.send(Ok(()));
    log::info!("compositor listening on {}", path.display());
    while state.running {
        event_loop.dispatch(Duration::from_millis(16), &mut state)?;
        // Requests only reach the protocol state through this call: the
        // event loop polls the socket, but nothing reads client messages
        // otherwise (a client blocks in its first roundtrip forever).
        display.dispatch_clients(&mut state)?;
        display.flush_clients()?;
        state.space.refresh();
    }
    let _ = display.flush_clients();
    // Dropping the display closes every client connection and the socket.
    drop(display);
    Ok(())
}

/// Tell the platform IME whether a text field currently has focus.
pub fn notify_text_input(enabled: bool) {
    #[cfg(target_os = "android")]
    android::notify_text_input(enabled);
    #[cfg(not(target_os = "android"))]
    let _ = enabled;
}

/// Wayland display needs to be kept alive for the loop's lifetime.
#[allow(dead_code)]
fn keep_display_alive(_display: &Display<State>) {}

#[cfg(target_os = "android")]
mod android {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Mutex, OnceLock};

    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jfloat, jint, jlong, jobject, jstring};
    use jni::JNIEnv;
    use smithay::reexports::calloop::channel::Sender;

    use crate::input::Command;
    use crate::Config;

    static SENDER: Mutex<Option<Sender<Command>>> = Mutex::new(None);
    static JAVA_VM: std::sync::OnceLock<jni::JavaVM> = std::sync::OnceLock::new();
    /// Class ref and static method cached from `nativeStart`, which runs on a
    /// Java thread. A native thread attached to the VM later has no application
    /// class loader, so `FindClass` there fails with ClassNotFoundException.
    static JAVA_CLASS: Mutex<Option<jni::objects::GlobalRef>> = Mutex::new(None);
    static JAVA_METHOD: Mutex<Option<usize>> = Mutex::new(None);
    /// Cached by `nativeStart` so compositor-thread callbacks can reach Kotlin.
    pub(super) fn notify_text_input(enabled: bool) {
        let Some(vm) = JAVA_VM.get() else { return };
        let class = JAVA_CLASS.lock().unwrap().clone();
        let method = *JAVA_METHOD.lock().unwrap();
        let (Some(class), Some(method)) = (class, method) else {
            return;
        };
        let Ok(mut env) = vm.attach_current_thread() else {
            return;
        };
        let class = unsafe { jni::objects::JClass::from_raw(class.as_obj().as_raw()) };
        let result = unsafe {
            env.call_static_method_unchecked(
                class,
                jni::objects::JStaticMethodID::from_raw(method as jni::sys::jmethodID),
                jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                &[jni::sys::jvalue {
                    z: enabled as u8,
                }],
            )
        };
        if result.is_err() || env.exception_check().unwrap_or(false) {
            // A callback failure must never kill the compositor thread.
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            log::warn!("text-input callback failed");
        }
    }
    static RUNNING: AtomicBool = AtomicBool::new(false);
    /// Reason the last `nativeStart` refused; surfaced to Kotlin verbatim.
    static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
    static THREAD: OnceLock<Mutex<Option<std::thread::JoinHandle<()>>>> = OnceLock::new();

    fn thread_slot() -> &'static Mutex<Option<std::thread::JoinHandle<()>>> {
        THREAD.get_or_init(|| Mutex::new(None))
    }

    fn fail(env: &mut JNIEnv, message: String) -> jstring {
        log::error!("nativeStart: {message}");
        *LAST_ERROR.lock().unwrap() = Some(message.clone());
        match env.new_string(message) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }

    fn send(command: Command) {
        if let Some(sender) = SENDER.lock().unwrap().as_ref() {
            let _ = sender.send(command);
        }
    }

    /// Start the compositor thread. Returns null on success, or a message
    /// explaining why the compositor refused to start.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeStart(
        mut env: JNIEnv,
        _class: JClass,
        runtime_dir: JString,
        xkb_root: JString,
        width: jint,
        height: jint,
        scale: jfloat,
    ) -> jstring {
        init_logging();
        *LAST_ERROR.lock().unwrap() = None;
        if let Ok(vm) = env.get_java_vm() {
            let _ = JAVA_VM.set(vm);
        }
        if let Ok(class) = env.find_class("io/furryr/file/wayland/NativeCompositor") {
            if let Ok(method) = env.get_static_method_id(&class, "onTextInputEnabled", "(Z)V") {
                *JAVA_METHOD.lock().unwrap() = Some(method.into_raw() as usize);
            }
            if let Ok(class) = env.new_global_ref(class) {
                *JAVA_CLASS.lock().unwrap() = Some(class);
            }
        }
        if RUNNING.load(Ordering::SeqCst) {
            return fail(&mut env, "compositor already running".to_owned());
        }
        let runtime_dir: String = env
            .get_string(&runtime_dir)
            .map(|value| value.into())
            .unwrap_or_default();
        let xkb_root: String = env
            .get_string(&xkb_root)
            .map(|value| value.into())
            .unwrap_or_default();
        if runtime_dir.is_empty() || xkb_root.is_empty() {
            return fail(&mut env, "missing runtime dir or xkb root".to_owned());
        }

        let (sender, receiver) = smithay::reexports::calloop::channel::channel();
        *SENDER.lock().unwrap() = Some(sender.clone());
        RUNNING.store(true, Ordering::SeqCst);

        let config = Config {
            runtime_dir: runtime_dir.into(),
            xkb_root: xkb_root.into(),
            size: (width, height),
            scale: scale as f64,
        };
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let handle = std::thread::Builder::new()
            .name("wayland-compositor".into())
            .spawn(move || {
                let result = crate::egl::create_renderer().map_err(|error| -> Box<dyn std::error::Error> {
                    error.into()
                });
                match result {
                    Ok(renderer) => {
                        if let Err(error) =
                            crate::run(renderer, config, sender, receiver, ready_tx)
                        {
                            log::error!("compositor stopped: {error}");
                        }
                    }
                    Err(error) => {
                        log::error!("cannot create EGL renderer: {error}");
                        let _ = ready_tx.send(Err(error.to_string()));
                    }
                }
                RUNNING.store(false, Ordering::SeqCst);
            });
        match handle {
            Ok(handle) => {
                *thread_slot().lock().unwrap() = Some(handle);
            }
            Err(error) => {
                RUNNING.store(false, Ordering::SeqCst);
                return fail(&mut env, format!("cannot spawn compositor thread: {error}"));
            }
        }
        // Only report success once the socket is bound and accepting.
        match ready_rx.recv_timeout(std::time::Duration::from_secs(10)) {
            Ok(Ok(())) => std::ptr::null_mut(),
            Ok(Err(error)) => {
                RUNNING.store(false, Ordering::SeqCst);
                fail(&mut env, error)
            }
            Err(error) => {
                RUNNING.store(false, Ordering::SeqCst);
                fail(&mut env, format!("compositor did not become ready: {error}"))
            }
        }
    }

    /// Stop the compositor and wait for its thread to finish.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeStop(
        _env: JNIEnv,
        _class: JClass,
    ) {
        send(Command::Stop);
        if let Some(handle) = thread_slot().lock().unwrap().take() {
            let _ = handle.join();
        }
        *SENDER.lock().unwrap() = None;
        RUNNING.store(false, Ordering::SeqCst);
    }

    /// Attach the viewer's `Surface` (its `ANativeWindow`).
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeAttachSurface(
        env: JNIEnv,
        _class: JClass,
        surface: jobject,
        width: jint,
        height: jint,
    ) {
        let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_raw(), surface) };
        if window.is_null() {
            log::error!("nativeAttachSurface: null ANativeWindow");
            return;
        }
        let (mut w, mut h) = (width, height);
        if w <= 0 || h <= 0 {
            w = unsafe { ndk_sys::ANativeWindow_getWidth(window) };
            h = unsafe { ndk_sys::ANativeWindow_getHeight(window) };
        }
        send(Command::AttachSurface {
            window: window as usize,
            width: w,
            height: h,
        });
    }

    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeDetachSurface(
        _env: JNIEnv,
        _class: JClass,
    ) {
        send(Command::DetachSurface);
    }

    /// Output size in physical pixels after a resize or rotation.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeResize(
        _env: JNIEnv,
        _class: JClass,
        width: jint,
        height: jint,
    ) {
        send(Command::Resize { width, height });
    }

    /// Device pixel ratio from `DisplayMetrics.densityDpi / 160`.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeSetScale(
        _env: JNIEnv,
        _class: JClass,
        scale: jfloat,
    ) {
        send(Command::SetScale { scale });
    }

    /// Android `MotionEvent` touch payload.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeTouch(
        _env: JNIEnv,
        _class: JClass,
        action: jint,
        id: jint,
        x: jfloat,
        y: jfloat,
        time: jlong,
    ) {
        send(Command::Touch {
            action,
            id,
            x,
            y,
            time: time as u32,
        });
    }

    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativePointerMotion(
        _env: JNIEnv,
        _class: JClass,
        x: jfloat,
        y: jfloat,
        time: jlong,
    ) {
        send(Command::PointerMotion {
            x,
            y,
            time: time as u32,
        });
    }

    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativePointerButton(
        _env: JNIEnv,
        _class: JClass,
        button: jint,
        pressed: jboolean,
        time: jlong,
    ) {
        send(Command::PointerButton {
            button: button as u32,
            pressed: pressed != 0,
            time: time as u32,
        });
    }

    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativePointerScroll(
        _env: JNIEnv,
        _class: JClass,
        dx: jfloat,
        dy: jfloat,
        time: jlong,
    ) {
        send(Command::PointerScroll {
            dx: dx as f64,
            dy: dy as f64,
            time: time as u32,
        });
    }

    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeKey(
        _env: JNIEnv,
        _class: JClass,
        keycode: jint,
        pressed: jboolean,
        time: jlong,
    ) {
        send(Command::Key {
            keycode: keycode as u32,
            pressed: pressed != 0,
            time: time as u32,
        });
    }

    /// Final text from the Android IME.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeTextInputCommit(
        mut env: JNIEnv,
        _class: JClass,
        text: JString,
    ) {
        let text: String = env
            .get_string(&text)
            .map(|value| value.into())
            .unwrap_or_default();
        send(Command::TextCommit { text });
    }

    /// Composing text; an empty string clears the preedit.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeTextInputPreedit(
        mut env: JNIEnv,
        _class: JClass,
        text: JString,
        cursor_begin: jint,
        cursor_end: jint,
    ) {
        let text: String = env
            .get_string(&text)
            .map(|value| value.into())
            .unwrap_or_default();
        send(Command::TextPreedit {
            text,
            cursor_begin,
            cursor_end,
        });
    }

    /// Backspace/delete from the IME, in code points.
    #[no_mangle]
    pub extern "system" fn Java_io_furryr_file_wayland_NativeCompositor_nativeTextInputDelete(
        _env: JNIEnv,
        _class: JClass,
        before: jint,
        after: jint,
    ) {
        send(Command::TextDelete {
            before: before.max(0) as u32,
            after: after.max(0) as u32,
        });
    }

    fn init_logging() {
        static INIT: OnceLock<()> = OnceLock::new();
        INIT.get_or_init(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("WaylandCompositor"),
            );
            std::panic::set_hook(Box::new(|info| {
                log::error!("compositor panic: {info}");
            }));
        });
    }
}
