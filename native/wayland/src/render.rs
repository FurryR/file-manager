//! Frame drawing for the nested output.

use std::ffi::c_void;
use std::time::Duration;

use smithay::backend::renderer::{
    gles::GlesRenderer,
    utils::draw_render_elements,
    Bind, Color32F, Frame, Renderer,
};
use smithay::utils::{Physical, Rectangle, Scale, Size, Transform};

use crate::state::State;

/// Attach a new `ANativeWindow` (viewer surface) to the output.
///
/// The EGL surface must be rebuilt whenever the Android surface changes: a
/// `SurfaceView` hands out a fresh `ANativeWindow` on every recreate, and an
/// `EGLSurface` cannot migrate between windows.
pub fn attach_window(state: &mut State, window: usize, size: (i32, i32)) {
    #[cfg(target_os = "android")]
    {
        state.egl_surface = None;
        match crate::egl::surface_for_window(&state.renderer, window as *mut c_void) {
            Ok(surface) => {
                state.egl_surface = Some(surface);
                state.set_output_size(size);
                log::info!("EGL surface attached ({}x{})", size.0, size.1);
            }
            Err(error) => log::error!("cannot create EGL window surface: {error:?}"),
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (state, window, size);
    }
}

/// Draw one frame and present it. No-op until a viewer surface exists.
///
/// Android's EGL window surface has its Y axis flipped relative to Wayland's
/// top-left origin, so the frame is rendered with
/// [`Transform::Flipped180`]. Flipping here rather than in the output's own
/// transform keeps logical coordinates (and therefore pointer input) in
/// Wayland space; only the GL projection changes.
pub fn render(state: &mut State, time: Duration) -> Result<(), Box<dyn std::error::Error>> {
    let Some(egl_surface) = state.egl_surface.as_mut() else {
        return Ok(());
    };
    state.space.refresh();
    let size: Size<i32, Physical> = state.physical_size.into();
    let elements = state
        .space
        .render_elements_for_output(&mut state.renderer, &state.output, 1.0)?;
    let damage = Rectangle::from_size(size);

    let mut framebuffer = state.renderer.bind(egl_surface)?;
    let mut frame = state
        .renderer
        .render(&mut framebuffer, size, Transform::Flipped180)?;
    frame.clear(Color32F::new(0.055, 0.055, 0.075, 1.0), &[damage])?;
    draw_render_elements::<GlesRenderer, _, _>(
        &mut frame,
        Scale::from(state.scale.fractional()),
        &elements,
        &[damage],
    )?;
    let _ = frame.finish()?;
    drop(framebuffer);
    egl_surface.swap_buffers(None)?;

    let output = state.output.clone();
    state.space.elements().for_each(|window| {
        window.send_frame(&output, time, None, |_, _| Some(output.clone()));
    });
    Ok(())
}
