//! Android EGL setup: a raw GLES context plus per-surface EGL windows.

use std::ffi::c_void;
use std::sync::Arc;

use smithay::backend::egl::display::EGLDisplayHandle;
use smithay::backend::egl::ffi as egl_ffi;
use smithay::backend::egl::native::EGLNativeSurface;
use smithay::backend::egl::{EGLContext, EGLSurface, EGLError};

/// EGL display, config and context for the app process.
///
/// Chosen once; every viewer surface reuses the config through
/// [`surface_for_window`].
pub struct RawContext {
    pub display: *const c_void,
    pub config: egl_ffi::egl::types::EGLConfig,
    pub context: *const c_void,
}

/// Build the raw EGL objects and hand them to Smithay's `GlesRenderer`.
pub fn create_renderer() -> Result<smithay::backend::renderer::gles::GlesRenderer, String> {
    let raw = create_raw_context().map_err(|error| error.to_string())?;
    let egl_context = unsafe {
        EGLContext::from_raw(raw.display, raw.config, raw.context)
    }
    .map_err(|error| format!("EGLContext::from_raw: {error:?}"))?;
    let renderer = unsafe {
        smithay::backend::renderer::gles::GlesRenderer::new(egl_context)
    }
    .map_err(|error| format!("GlesRenderer::new: {error:?}"))?;
    // Keep the config for later window surfaces; the display handle is shared
    // with the renderer's own EGLContext.
    CONFIG.store(raw.config as *mut c_void, std::sync::atomic::Ordering::Relaxed);
    renderer
        .egl_context()
        .unbind()
        .map_err(|error| format!("EGL unbind: {error:?}"))?;
    Ok(renderer)
}

/// EGL config of the renderer context, needed by `EGLSurface::new`.
static CONFIG: std::sync::atomic::AtomicPtr<c_void> =
    std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

/// Create an `EGLSurface` backed by an `ANativeWindow`.
pub fn surface_for_window(
    renderer: &smithay::backend::renderer::gles::GlesRenderer,
    window: *mut c_void,
) -> Result<EGLSurface, EGLError> {
    let display: smithay::backend::egl::EGLDisplay = renderer.egl_context().display().clone();
    let pixel_format = renderer
        .egl_context()
        .pixel_format()
        .ok_or(EGLError::BadNativeWindow)?;
    let config = CONFIG.load(std::sync::atomic::Ordering::Relaxed) as egl_ffi::egl::types::EGLConfig;
    let native = AndroidNativeSurface::new(window);
    unsafe { EGLSurface::new(&display, pixel_format, config, native) }
}

/// Load `libEGL.so` and populate Smithay's EGL function table.
///
/// Smithay's own loader (`ffi::make_sure_egl_is_loaded`) opens `libEGL.so.1`,
/// which does not exist on Android — that path panics with
/// `dlopen failed: library "libEGL.so.1" not found`. Every EGL entry point
/// reads the same global table, so loading the platform soname here is enough
/// for `EGLSurface`, `EGLContext` and `GlesRenderer`.
fn load_egl() -> Result<(), String> {
    static LIB: std::sync::OnceLock<libloading::Library> = std::sync::OnceLock::new();
    static GUARD: std::sync::Mutex<()> = std::sync::Mutex::new(());

    if LIB.get().is_some() {
        return Ok(());
    }
    let _guard = GUARD.lock().map_err(|_| "EGL loader poisoned".to_owned())?;
    if LIB.get().is_some() {
        return Ok(());
    }

    let library = unsafe { libloading::Library::new("libEGL.so") }
        .map_err(|error| format!("dlopen libEGL.so: {error}"))?;
    unsafe {
        let address = |name: &'static str| -> *const c_void {
            let Ok(symbol) = std::ffi::CString::new(name) else {
                return std::ptr::null();
            };
            match library.get::<*mut c_void>(symbol.as_bytes()) {
                Ok(symbol) => *symbol as *const _,
                Err(_) => std::ptr::null(),
            }
        };
        smithay::backend::egl::ffi::egl::load_with(&address);
    }
    let _ = LIB.set(library);
    Ok(())
}

fn create_raw_context() -> Result<RawContext, Box<dyn std::error::Error>> {
    unsafe { create_raw_context_inner() }
}

unsafe fn create_raw_context_inner() -> Result<RawContext, Box<dyn std::error::Error>> {
    load_egl()?;

    let display = egl_ffi::egl::GetDisplay(egl_ffi::egl::DEFAULT_DISPLAY);
    if display == egl_ffi::egl::NO_DISPLAY {
        return Err("eglGetDisplay failed".into());
    }
    let mut major = 0i32;
    let mut minor = 0i32;
    if egl_ffi::egl::Initialize(display, &mut major, &mut minor) == egl_ffi::egl::FALSE {
        return Err("eglInitialize failed".into());
    }
    if egl_ffi::egl::BindAPI(egl_ffi::egl::OPENGL_ES_API) == egl_ffi::egl::FALSE {
        return Err("eglBindAPI(OPENGL_ES_API) failed".into());
    }

    // PBUFFER_BIT keeps `make_current` working on drivers without
    // EGL_KHR_surfaceless_context.
    let config_attribs: &[i32] = &[
        egl_ffi::egl::SURFACE_TYPE as i32,
        (egl_ffi::egl::WINDOW_BIT | egl_ffi::egl::PBUFFER_BIT) as i32,
        egl_ffi::egl::RENDERABLE_TYPE as i32,
        egl_ffi::egl::OPENGL_ES2_BIT as i32,
        egl_ffi::egl::RED_SIZE as i32,
        8,
        egl_ffi::egl::GREEN_SIZE as i32,
        8,
        egl_ffi::egl::BLUE_SIZE as i32,
        8,
        egl_ffi::egl::ALPHA_SIZE as i32,
        8,
        egl_ffi::egl::NONE as i32,
    ];
    let mut config: egl_ffi::egl::types::EGLConfig = std::ptr::null();
    let mut count = 0i32;
    if egl_ffi::egl::ChooseConfig(display, config_attribs.as_ptr(), &mut config, 1, &mut count)
        == egl_ffi::egl::FALSE
        || count == 0
    {
        return Err("eglChooseConfig failed".into());
    }

    let context_attribs: &[i32] = &[
        egl_ffi::egl::CONTEXT_CLIENT_VERSION as i32,
        2,
        egl_ffi::egl::NONE as i32,
    ];
    let context = egl_ffi::egl::CreateContext(
        display,
        config,
        egl_ffi::egl::NO_CONTEXT,
        context_attribs.as_ptr(),
    );
    if context == egl_ffi::egl::NO_CONTEXT {
        return Err("eglCreateContext failed".into());
    }
    // Best effort: some drivers only accept a real surface.
    egl_ffi::egl::MakeCurrent(
        display,
        egl_ffi::egl::NO_SURFACE,
        egl_ffi::egl::NO_SURFACE,
        context,
    );

    Ok(RawContext {
        display,
        config,
        context,
    })
}

/// Thin wrapper so Smithay can create/destroy the window surface.
struct AndroidNativeSurface {
    window: *mut c_void,
}

// SAFETY: `ANativeWindow` is reference counted with thread-safe operations and
// the pointer is only moved from the JNI thread to the compositor thread.
unsafe impl Send for AndroidNativeSurface {}

impl AndroidNativeSurface {
    fn new(window: *mut c_void) -> Self {
        Self { window }
    }
}

unsafe impl EGLNativeSurface for AndroidNativeSurface {
    unsafe fn create(
        &self,
        display: &Arc<EGLDisplayHandle>,
        config_id: egl_ffi::egl::types::EGLConfig,
    ) -> Result<*const c_void, EGLError> {
        let attribs: &[i32] = &[egl_ffi::egl::NONE as i32];
        let surface = egl_ffi::egl::CreateWindowSurface(
            display.handle,
            config_id,
            self.window as egl_ffi::egl::types::EGLNativeWindowType,
            attribs.as_ptr(),
        );
        if surface == egl_ffi::egl::NO_SURFACE {
            Err(EGLError::BadNativeWindow)
        } else {
            Ok(surface)
        }
    }

    fn identifier(&self) -> Option<String> {
        Some("Android/ANativeWindow".to_owned())
    }
}
