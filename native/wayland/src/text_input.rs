//! `zwp_text_input_v3` server side, driven by the Android IME.
//!
//! Smithay's `wayland::text_input` module expects a Wayland *input-method*
//! client to drive it, but the keyboard here is the Android IME living in
//! Kotlin. This implements the protocol directly: instances are tracked per
//! client, the keyboard focus surface receives `enter`/`leave`, and the IME's
//! commit/preedit/delete calls are forwarded to every enabled instance of the
//! focused client in protocol order.

use smithay::{
    backend::input::KeyState,
    input::keyboard::{xkb, FilterResult, Keycode, KeyboardHandle},
    reexports::{
        wayland_protocols::wp::text_input::zv3::server::{
            zwp_text_input_manager_v3::{self, ZwpTextInputManagerV3},
            zwp_text_input_v3::{self, ZwpTextInputV3},
        },
        wayland_server::{
            protocol::wl_surface::WlSurface, Dispatch, DisplayHandle, GlobalDispatch, Resource,
        },
    },
    utils::{Logical, Rectangle},
};

use crate::state::State;

/// evdev keycodes used to carry generated Unicode keysyms (`F13`..`F22`): no
/// Android hardware keyboard produces them, so hijacking them is invisible.
const UNICODE_KEY_BASE: u32 = 183;
const UNICODE_KEYS: usize = 10;
/// evdev codes for the keys that a commit may smuggle: Return and Tab.
const KEY_RETURN: u32 = 28;
const KEY_TAB: u32 = 15;

/// One client text-input object.
pub struct Instance {
    pub object: ZwpTextInputV3,
    pub enabled: bool,
    pub surrounding: String,
    pub cursor_rect: Option<Rectangle<i32, Logical>>,
}

impl State {
    pub fn add_text_input(&mut self, object: ZwpTextInputV3) {
        if self
            .text_inputs
            .iter()
            .any(|instance| instance.object == object)
        {
            return;
        }
        log::debug!("text-input: new instance");
        self.text_inputs.push(Instance {
            object,
            enabled: false,
            surrounding: String::new(),
            cursor_rect: None,
        });
    }

    pub fn remove_text_input(&mut self, object: &ZwpTextInputV3) {
        self.text_inputs.retain(|instance| &instance.object != object);
    }

    /// Move the text-input focus. Sends `leave` to the old surface and `enter`
    /// to every enabled instance of the new one.
    pub fn set_text_input_focus(&mut self, surface: Option<WlSurface>) {
        if self.text_input_focus == surface {
            self.update_text_input_notification();
            return;
        }
        let previous = self.text_input_focus.clone();
        self.text_input_focus = surface.clone();
        let serial = self.next_text_input_serial();
        if let Some(previous) = previous {
            for instance in self.text_inputs.iter().filter(|i| i.enabled) {
                instance.object.leave(&previous);
                instance.object.done(serial);
            }
        }
        let mut activated = false;
        if let Some(surface) = surface {
            for instance in self.text_inputs.iter().filter(|i| i.enabled) {
                instance.object.enter(&surface);
                instance.object.done(serial);
                activated = true;
            }
        }
        let _ = activated;
        self.update_text_input_notification();
    }

    /// Notify the platform IME when the focused client starts or stops
    /// accepting text input. Idempotent: only a change reaches Kotlin.
    pub fn update_text_input_notification(&mut self) {
        let activated = !self.focused_instances().is_empty();
        if self.text_input_notified != Some(activated) {
            self.text_input_notified = Some(activated);
            log::info!("text-input focus enabled={activated}");
            crate::android::notify_text_input(activated);
        }
    }

    /// Give a newly enabled instance the current focus.
    pub fn text_input_enter_enabled(&mut self, object: &ZwpTextInputV3) {
        let Some(surface) = self.text_input_focus.clone() else {
            log::debug!("text-input: enter skipped, no focus");
            return;
        };
        if !object.id().same_client_as(&surface.id()) {
            log::debug!("text-input: enter skipped, different client");
            return;
        }
        log::debug!("text-input: enter sent");
        let serial = self.next_text_input_serial();
        object.enter(&surface);
        object.done(serial);
    }

    fn next_text_input_serial(&mut self) -> u32 {
        self.text_input_serial = self.text_input_serial.wrapping_add(1);
        self.text_input_serial
    }

    /// Enabled instances whose client owns the focused surface.
    fn focused_instances(&self) -> Vec<&Instance> {
        let Some(surface) = self.text_input_focus.as_ref().filter(|s| s.is_alive()) else {
            return Vec::new();
        };
        let surface_id = surface.id();
        self.text_inputs
            .iter()
            .filter(|instance| {
                instance.enabled && instance.object.id().same_client_as(&surface_id)
            })
            .collect()
    }

    pub fn text_input_enabled(&self) -> bool {
        !self.focused_instances().is_empty()
    }

    /// Final text from the IME.
    ///
    /// Clients without `zwp_text_input_v3` (terminals, foot) cannot receive
    /// text, so it is delivered as ordinary key events instead.
    pub fn text_input_commit(&mut self, text: &str) {
        if self.focused_instances().is_empty() {
            self.send_text_as_keys(text);
            return;
        }
        let serial = self.next_text_input_serial();
        for instance in self.focused_instances() {
            instance.object.commit_string(Some(text.to_owned()));
            instance.object.done(serial);
        }
    }

    /// Deliver text to a client that has no text-input protocol by typing it.
    ///
    /// The keymap sent to clients is ours, so a throwaway keymap maps each
    /// character to its Unicode keysym (`UXXXX`); pressing those keycodes makes any
    /// `wl_keyboard` client — terminal included — receive real UTF-8 text,
    /// CJK and emoji included.
    pub fn send_text_as_keys(&mut self, text: &str) {
        let Some(keyboard) = self.seat.get_keyboard() else {
            return;
        };
        self.capture_base_keymap(&keyboard);
        let mut batch: Vec<char> = Vec::new();
        for ch in text.chars() {
            match ch {
                '\n' | '\r' => {
                    self.flush_unicode_keys(&keyboard, &mut batch);
                    self.tap_key(&keyboard, KEY_RETURN);
                }
                '\t' => {
                    self.flush_unicode_keys(&keyboard, &mut batch);
                    self.tap_key(&keyboard, KEY_TAB);
                }
                ch if ch.is_control() => {}
                ch => {
                    batch.push(ch);
                    if batch.len() == UNICODE_KEYS {
                        self.flush_unicode_keys(&keyboard, &mut batch);
                    }
                }
            }
        }
        self.flush_unicode_keys(&keyboard, &mut batch);
    }

    /// Remember the pristine keymap once; every Unicode batch is derived from
    /// it so batches never stack up on each other.
    fn capture_base_keymap(&mut self, keyboard: &KeyboardHandle<State>) {
        if self.base_keymap.is_some() {
            return;
        }
        let base = keyboard.with_xkb_state(self, |context| {
            let xkb_state = context.xkb().lock().unwrap();
            // Safety: the string is copied while the keymap is still alive.
            unsafe { xkb_state.keymap() }.get_as_string(xkb::KEYMAP_FORMAT_TEXT_V1)
        });
        self.base_keymap = Some(base);
    }

    fn flush_unicode_keys(&mut self, keyboard: &KeyboardHandle<State>, batch: &mut Vec<char>) {
        if batch.is_empty() {
            return;
        }
        let Some(keymap) = self
            .base_keymap
            .as_deref()
            .and_then(|base| unicode_keymap(base, batch))
        else {
            log::warn!("cannot build a Unicode keymap");
            batch.clear();
            return;
        };
        if let Err(error) = keyboard.set_keymap_from_string(self, keymap) {
            log::warn!("Unicode keymap rejected: {error:?}");
            batch.clear();
            return;
        }
        for index in 0..batch.len() {
            self.tap_key(keyboard, UNICODE_KEY_BASE + index as u32);
        }
        batch.clear();
    }

    fn tap_key(&mut self, keyboard: &KeyboardHandle<State>, evdev: u32) {
        // XKB keycodes are evdev codes offset by 8.
        let keycode = Keycode::new(evdev + 8);
        for state in [KeyState::Pressed, KeyState::Released] {
            let serial = self.next_serial();
            let _ = keyboard.input::<(), _>(self, keycode, state, serial, 0, |_, _, _| {
                FilterResult::Forward
            });
        }
    }

    /// Composing text; an empty string clears the preedit.
    pub fn text_input_preedit(&mut self, text: &str, cursor_begin: i32, cursor_end: i32) {
        let serial = self.next_text_input_serial();
        for instance in self.focused_instances() {
            if text.is_empty() {
                instance.object.preedit_string(None, 0, 0);
            } else {
                instance
                    .object
                    .preedit_string(Some(text.to_owned()), cursor_begin, cursor_end);
            }
            instance.object.done(serial);
        }
    }

    /// Backspace/delete from the IME.
    pub fn text_input_delete(&mut self, before: u32, after: u32) {
        let serial = self.next_text_input_serial();
        for instance in self.focused_instances() {
            instance.object.delete_surrounding_text(before, after);
            instance.object.done(serial);
        }
    }
}

impl GlobalDispatch<ZwpTextInputManagerV3, ()> for State {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &smithay::reexports::wayland_server::Client,
        resource: smithay::reexports::wayland_server::New<ZwpTextInputManagerV3>,
        _global_data: &(),
        data_init: &mut smithay::reexports::wayland_server::DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<ZwpTextInputManagerV3, ()> for State {
    fn request(
        state: &mut Self,
        _client: &smithay::reexports::wayland_server::Client,
        _resource: &ZwpTextInputManagerV3,
        request: zwp_text_input_manager_v3::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut smithay::reexports::wayland_server::DataInit<'_, Self>,
    ) {
        match request {
            zwp_text_input_manager_v3::Request::GetTextInput { id, .. } => {
                log::debug!("text-input: get_text_input");
                let object = data_init.init(id, ());
                state.add_text_input(object);
            }
            zwp_text_input_manager_v3::Request::Destroy => {}
            _ => {}
        }
    }
}

impl Dispatch<ZwpTextInputV3, ()> for State {
    fn request(
        state: &mut Self,
        _client: &smithay::reexports::wayland_server::Client,
        resource: &ZwpTextInputV3,
        request: zwp_text_input_v3::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut smithay::reexports::wayland_server::DataInit<'_, Self>,
    ) {
        match request {
            zwp_text_input_v3::Request::Enable => {
                log::debug!("text-input: enable");
                if let Some(instance) = state
                    .text_inputs
                    .iter_mut()
                    .find(|instance| &instance.object == resource)
                {
                    instance.enabled = true;
                }
                // The surface focus may already be set, in which case the
                // focus routine has nothing to send; the new instance still
                // needs its `enter`.
                state.text_input_enter_enabled(resource);
                state.update_text_input_notification();
            }
            zwp_text_input_v3::Request::Disable => {
                log::debug!("text-input: disable");
                if let Some(instance) = state
                    .text_inputs
                    .iter_mut()
                    .find(|instance| &instance.object == resource)
                {
                    instance.enabled = false;
                }
                state.update_text_input_notification();
            }
            zwp_text_input_v3::Request::SetSurroundingText { text, .. } => {
                if let Some(instance) = state
                    .text_inputs
                    .iter_mut()
                    .find(|instance| &instance.object == resource)
                {
                    instance.surrounding = text;
                }
            }
            _ => {}
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: smithay::reexports::wayland_server::backend::ClientId,
        resource: &ZwpTextInputV3,
        _data: &(),
    ) {
        state.remove_text_input(resource);
        state.refresh_text_input_focus();
    }
}

/// Add `replace key <FKnn> { [ UXXXX ] }` entries to the symbols section of
/// an XKB keymap string, one per character.
fn unicode_keymap(base: &str, chars: &[char]) -> Option<String> {
    let symbols = base.find("xkb_symbols")?;
    let open = symbols + base[symbols..].find('{')?;
    let mut depth = 0usize;
    let mut close = None;
    for (offset, ch) in base[open..].char_indices() {
        match ch {
            '{' => depth += 1,
            '}' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    close = Some(open + offset);
                    break;
                }
            }
            _ => {}
        }
    }
    let close = close?;
    let mut extra = String::new();
    for (index, ch) in chars.iter().enumerate() {
        extra.push_str(&format!(
            " replace key <FK{}> {{ [ U{:04X} ] }};",
            index + 13,
            u32::from(*ch)
        ));
    }
    let mut keymap = String::with_capacity(base.len() + extra.len());
    keymap.push_str(&base[..close]);
    keymap.push_str(&extra);
    keymap.push_str(&base[close..]);
    Some(keymap)
}
