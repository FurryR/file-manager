//! Input forwarding from Android (JNI thread) to the compositor event loop.

use smithay::{
    backend::input::{Axis, AxisSource, ButtonState, KeyState, TouchSlot},
    input::{
        keyboard::FilterResult,
        pointer::{AxisFrame, ButtonEvent, MotionEvent as PointerMotionEvent},
        touch::{DownEvent, MotionEvent as TouchMotionEvent, UpEvent},
    },
    utils::{Logical, Point},
    wayland::seat::WaylandFocus,
};

use crate::keymap;
use crate::scale::OutputScale;
use crate::state::State;

/// Everything the JNI layer needs to hand to the compositor thread.
///
/// Raw Android values: `action` is `MotionEvent.getActionMasked()`, `time` is
/// `MotionEvent.getEventTime()` in milliseconds, coordinates are physical
/// pixels of the viewer surface.
pub enum Command {
    Touch {
        action: i32,
        id: i32,
        x: f32,
        y: f32,
        time: u32,
    },
    PointerMotion {
        x: f32,
        y: f32,
        time: u32,
    },
    PointerButton {
        button: u32,
        pressed: bool,
        time: u32,
    },
    PointerScroll {
        dx: f64,
        dy: f64,
        time: u32,
    },
    Key {
        keycode: u32,
        pressed: bool,
        time: u32,
    },
    TextCommit {
        text: String,
    },
    TextPreedit {
        text: String,
        cursor_begin: i32,
        cursor_end: i32,
    },
    TextDelete {
        before: u32,
        after: u32,
    },
    Resize {
        width: i32,
        height: i32,
    },
    SetScale {
        scale: f32,
    },
    AttachSurface {
        window: usize,
        width: i32,
        height: i32,
    },
    DetachSurface,
    Stop,
}

// Android MotionEvent action constants.
const ACTION_DOWN: i32 = 0;
const ACTION_UP: i32 = 1;
const ACTION_MOVE: i32 = 2;
const ACTION_CANCEL: i32 = 3;
const ACTION_POINTER_DOWN: i32 = 5;
const ACTION_POINTER_UP: i32 = 6;

pub fn apply(state: &mut State, command: Command) {
    match command {
        Command::Touch {
            action,
            id,
            x,
            y,
            time,
        } => touch(state, action, id, x, y, time),
        Command::PointerMotion { x, y, time } => pointer_motion(state, x, y, time),
        Command::PointerButton {
            button,
            pressed,
            time,
        } => pointer_button(state, button, pressed, time),
        Command::PointerScroll { dx, dy, time } => pointer_scroll(state, dx, dy, time),
        Command::Key {
            keycode,
            pressed,
            time,
        } => key(state, keycode, pressed, time),
        Command::TextCommit { text } => state.text_input_commit(&text),
        Command::TextPreedit {
            text,
            cursor_begin,
            cursor_end,
        } => state.text_input_preedit(&text, cursor_begin, cursor_end),
        Command::TextDelete { before, after } => state.text_input_delete(before, after),
        Command::Resize { width, height } => state.set_output_size((width, height)),
        Command::SetScale { scale } => {
            state.set_scale(OutputScale::sanitized(scale as f64));
        }
        Command::AttachSurface {
            window,
            width,
            height,
        } => {
            crate::render::attach_window(state, window, (width, height));
        }
        Command::DetachSurface => {
            state.egl_surface = None;
        }
        Command::Stop => state.running = false,
    }
}

fn logical(state: &State, x: f32, y: f32) -> Point<f64, Logical> {
    Point::from((
        state.scale.to_logical(x as f64),
        state.scale.to_logical(y as f64),
    ))
}

fn topmost_surface(state: &State) -> Option<smithay::reexports::wayland_server::protocol::wl_surface::WlSurface> {
    state
        .space
        .elements()
        .next_back()
        .and_then(|window| window.wl_surface().map(|surface| surface.into_owned()))
}

fn touch(state: &mut State, action: i32, id: i32, x: f32, y: f32, time: u32) {
    log::debug!("touch action={action} id={id} at {x}x{y} t={time}");
    let Some(touch) = state.seat.get_touch() else {
        log::warn!("touch ignored: seat has no touch handle");
        return;
    };
    let slot = TouchSlot::from(Some(id as u32));
    let location = logical(state, x, y);
    match action {
        ACTION_DOWN | ACTION_POINTER_DOWN => {
            let serial = state.next_serial();
            let focus = topmost_surface(state).map(|surface| (surface, Point::from((0.0, 0.0))));
            touch.down(
                state,
                focus,
                &DownEvent {
                    slot,
                    location,
                    serial,
                    time,
                },
            );
        }
        ACTION_MOVE => {
            touch.motion(
                state,
                None,
                &TouchMotionEvent {
                    slot,
                    location,
                    time,
                },
            );
            touch.frame(state);
        }
        ACTION_UP | ACTION_POINTER_UP => {
            let serial = state.next_serial();
            touch.up(
                state,
                &UpEvent {
                    slot,
                    serial,
                    time,
                },
            );
        }
        ACTION_CANCEL => touch.cancel(state),
        _ => return,
    }
    touch.frame(state);
}

fn pointer_focus(
    state: &State,
    location: Point<f64, Logical>,
) -> Option<(
    smithay::reexports::wayland_server::protocol::wl_surface::WlSurface,
    Point<f64, Logical>,
)> {
    let window = state.space.element_under(location)?.0.clone();
    let surface = window.wl_surface()?.into_owned();
    Some((surface, Point::from((0.0, 0.0))))
}

fn pointer_motion(state: &mut State, x: f32, y: f32, time: u32) {
    let Some(pointer) = state.seat.get_pointer() else {
        return;
    };
    let location = logical(state, x, y);
    let serial = state.next_serial();
    let focus = pointer_focus(state, location);
    pointer.motion(
        state,
        focus.clone(),
        &PointerMotionEvent {
            location,
            serial,
            time,
        },
    );
    pointer.frame(state);
}

fn pointer_button(state: &mut State, button: u32, pressed: bool, time: u32) {
    let Some(pointer) = state.seat.get_pointer() else {
        return;
    };
    let serial = state.next_serial();
    pointer.button(
        state,
        &ButtonEvent {
            serial,
            time,
            button,
            state: if pressed {
                ButtonState::Pressed
            } else {
                ButtonState::Released
            },
        },
    );
    pointer.frame(state);
}

fn pointer_scroll(state: &mut State, dx: f64, dy: f64, time: u32) {
    let Some(pointer) = state.seat.get_pointer() else {
        return;
    };
    let mut frame = AxisFrame::new(time).source(AxisSource::Wheel);
    if dy != 0.0 {
        frame = frame.value(Axis::Vertical, dy);
    }
    if dx != 0.0 {
        frame = frame.value(Axis::Horizontal, dx);
    }
    pointer.axis(state, frame);
    pointer.frame(state);
}

fn key(state: &mut State, keycode: u32, pressed: bool, time: u32) {
    let Some(keyboard) = state.seat.get_keyboard() else {
        return;
    };
    let Some(evdev) = keymap::android_to_evdev(keycode) else {
        return;
    };
    let serial = state.next_serial();
    let _ = keyboard.input::<(), _>(
        state,
        // XKB keycodes are evdev codes offset by 8.
        (evdev + 8).into(),
        if pressed {
            KeyState::Pressed
        } else {
            KeyState::Released
        },
        serial,
        time,
        |_, _, _| FilterResult::Forward,
    );
}
