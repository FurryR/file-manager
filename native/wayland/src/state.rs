//! Wayland protocol state for the nested Android compositor.

use smithay::{
    backend::renderer::gles::GlesRenderer,
    desktop::{Space, Window},
    input::{pointer::PointerHandle, Seat, SeatHandler, SeatState},
    output::{Mode, Output, PhysicalProperties, Subpixel},
    reexports::{
        calloop::channel::Sender,
        wayland_server::{
            backend::{ClientData, ClientId, DisconnectReason},
            protocol::{wl_buffer, wl_output, wl_seat, wl_surface::WlSurface},
            Client, Display, DisplayHandle,
        },
    },
    utils::{Logical, Serial, Size, Transform},
    wayland::{
        buffer::BufferHandler,
        compositor::{CompositorClientState, CompositorHandler, CompositorState},
        fractional_scale::{self, FractionalScaleHandler, FractionalScaleManagerState},
        output::OutputHandler,
        selection::{
            data_device::{
                ClientDndGrabHandler, DataDeviceHandler, DataDeviceState, ServerDndGrabHandler,
            },
            SelectionHandler,
        },
        shm::{ShmHandler, ShmState},
        shell::xdg::{
            PopupSurface, PositionerState, ToplevelSurface, XdgShellHandler, XdgShellState,
        },
        viewporter::ViewporterState,
    },
    wayland::seat::WaylandFocus,
};

use crate::input::Command;
use crate::scale::OutputScale;
use crate::text_input::Instance as TextInputInstance;

/// Per-client data the Wayland backend requires. Only the compositor's own
/// client state is tracked; the nested desktop has no per-client policy.
#[derive(Default)]
pub struct ClientState {
    pub compositor_state: CompositorClientState,
}

impl ClientData for ClientState {
    fn initialized(&self, _client_id: ClientId) {}
    fn disconnected(&self, _client_id: ClientId, _reason: DisconnectReason) {}
}

pub struct State {
    pub display_handle: DisplayHandle,
    pub compositor_state: CompositorState,
    pub xdg_shell_state: XdgShellState,
    pub shm_state: ShmState,
    pub data_device_state: DataDeviceState,
    pub seat_state: SeatState<State>,
    pub fractional_scale_manager_state: FractionalScaleManagerState,
    pub seat: Seat<State>,
    pub space: Space<Window>,
    pub output: Output,
    pub renderer: GlesRenderer,
    pub egl_surface: Option<smithay::backend::egl::EGLSurface>,
    pub scale: OutputScale,
    pub physical_size: (i32, i32),
    pub serial: Serial,
    pub text_inputs: Vec<TextInputInstance>,
    pub text_input_focus: Option<WlSurface>,
    pub text_input_serial: u32,
    pub text_input_notified: Option<bool>,
    /// Pristine keymap string; Unicode batches are derived from it.
    pub base_keymap: Option<String>,
    pub pointer: Option<PointerHandle<State>>,
    pub windows: Vec<(ToplevelSurface, Window)>,
    pub needs_redraw: bool,
    pub running: bool,
    pub command_sender: Sender<Command>,
}

impl State {
    pub fn new(
        display: &mut Display<Self>,
        renderer: GlesRenderer,
        command_sender: Sender<Command>,
        scale: OutputScale,
        physical_size: (i32, i32),
    ) -> Self {
        let dh = display.handle();

        // v6 lets Smithay send `wl_surface.preferred_buffer_scale` as the
        // integer fallback while fractional-aware clients read the real value
        // from wp_fractional_scale_v1.
        let compositor_state = CompositorState::new_v6::<Self>(&dh);
        let xdg_shell_state = XdgShellState::new::<Self>(&dh);
        let shm_state = ShmState::new::<Self>(&dh, []);
        // foot refuses to start without wl_data_device_manager ("no clipboard
        // available"), and every toolkit expects it for copy/paste.
        let data_device_state = DataDeviceState::new::<Self>(&dh);
        let fractional_scale_manager_state = FractionalScaleManagerState::new::<Self>(&dh);
        ViewporterState::new::<Self>(&dh);
        dh.create_global::<
            Self,
            smithay::reexports::wayland_protocols::wp::text_input::zv3::server::zwp_text_input_manager_v3::ZwpTextInputManagerV3,
            (),
        >(1, ());

        let mut seat_state = SeatState::new();
        let mut seat = seat_state.new_wl_seat(&dh, "file-manager");
        seat.add_keyboard(Default::default(), 200, 25)
            .expect("cannot add keyboard to seat");
        seat.add_touch();
        let pointer = seat.add_pointer();

        let output = Output::new(
            "wayland-0".to_owned(),
            PhysicalProperties {
                size: (0, 0).into(),
                subpixel: Subpixel::Unknown,
                make: "file-manager".into(),
                model: "Android".into(),
            },
        );

        let mut state = Self {
            display_handle: dh,
            compositor_state,
            xdg_shell_state,
            shm_state,
            data_device_state,
            seat_state,
            fractional_scale_manager_state,
            seat,
            space: Space::default(),
            output,
            renderer,
            egl_surface: None,
            scale,
            physical_size: (1, 1),
            serial: Serial::from(0),
            text_inputs: Vec::new(),
            text_input_focus: None,
            text_input_serial: 0,
            text_input_notified: None,
            base_keymap: None,
            pointer: Some(pointer),
            windows: Vec::new(),
            needs_redraw: true,
            running: true,
            command_sender,
        };
        state.output.create_global::<Self>(&state.display_handle);
        state.space.map_output(&state.output, (0, 0));
        state.set_output_size(physical_size);
        state
    }

    /// Next serial for input events and focus changes.
    pub fn next_serial(&mut self) -> Serial {
        let serial = self.serial;
        self.serial = Serial::from(u32::from(serial).wrapping_add(1));
        serial
    }

    /// Apply a new physical buffer size and scale to the single output.
    pub fn set_output_size(&mut self, physical: (i32, i32)) {
        let mut size = physical;
        if size.0 <= 0 || size.1 <= 0 {
            size = (1, 1);
        }
        self.physical_size = size;
        let mode = Mode {
            size: size.into(),
            refresh: 60_000,
        };
        let previous = self.output.current_mode();
        self.output.change_current_state(
            Some(mode),
            Some(Transform::Normal),
            Some(self.scale.smithay()),
            Some((0, 0).into()),
        );
        self.output.set_preferred(mode);
        if let Some(previous) = previous.filter(|previous| *previous != mode) {
            self.output.delete_mode(previous);
        }
        self.needs_redraw = true;
        self.relayout();
    }

    /// Apply a new device scale; every mapped surface learns about it.
    pub fn set_scale(&mut self, scale: OutputScale) {
        self.scale = scale;
        self.set_output_size(self.physical_size);
        let fractional = self.scale.fractional();
        for window in self.space.elements() {
            if let Some(surface) = window.wl_surface() {
                let surface = surface.clone();
                smithay::wayland::compositor::with_states(&surface, |states| {
                    fractional_scale::with_fractional_scale(states, |state| {
                        state.set_preferred_scale(fractional);
                    });
                });
            }
        }
    }

    /// Configure every mapped toplevel to fill the output in logical units.
    pub fn relayout(&mut self) {
        let logical = self.scale.logical_size(self.physical_size);
        let size: Size<i32, Logical> = (logical.0, logical.1).into();
        for (toplevel, _) in &self.windows {
            configure_toplevel(toplevel, size);
        }
        self.space.refresh();
        self.needs_redraw = true;
    }

    fn focus_topmost(&mut self) {
        let surface = self
            .space
            .elements()
            .next_back()
            .and_then(|window| window.wl_surface().map(|surface| surface.into_owned()));
        let serial = self.next_serial();
        if let Some(keyboard) = self.seat.get_keyboard() {
            keyboard.set_focus(self, surface, serial);
        }
        self.refresh_text_input_focus();
    }

    /// Follow the keyboard focus so `zwp_text_input_v3` instances see
    /// `enter`/`leave` and the IME knows a text field exists.
    pub fn refresh_text_input_focus(&mut self) {
        let focus = self
            .seat
            .get_keyboard()
            .and_then(|keyboard| keyboard.current_focus());
        self.set_text_input_focus(focus);
    }

    pub fn surface_for_toplevel(&self, toplevel: &ToplevelSurface) -> Option<WlSurface> {
        self.windows
            .iter()
            .find(|(candidate, _)| candidate == toplevel)
            .and_then(|(_, window)| window.wl_surface().map(|surface| surface.into_owned()))
    }
}

fn configure_toplevel(toplevel: &ToplevelSurface, size: Size<i32, Logical>) {
    toplevel.with_pending_state(|state| {
        state.states.set(smithay::reexports::wayland_protocols::xdg::shell::server::xdg_toplevel::State::Activated);
        state.size = Some(size);
    });
    toplevel.send_configure();
}

impl CompositorHandler for State {
    fn compositor_state(&mut self) -> &mut CompositorState {
        &mut self.compositor_state
    }

    fn client_compositor_state<'a>(&self, client: &'a Client) -> &'a CompositorClientState {
        &client
            .get_data::<ClientState>()
            .expect("client connected without ClientState")
            .compositor_state
    }

    fn commit(&mut self, surface: &WlSurface) {
        let mapped = self
            .windows
            .iter()
            .any(|(_, window)| window.wl_surface().map(|s| &*s == surface).unwrap_or(false));
        let buffer = smithay::wayland::compositor::with_states(surface, |states| {
            let mut guard = states
                .cached_state
                .get::<smithay::wayland::compositor::SurfaceAttributes>();
            let attrs = guard.current();
            (attrs.buffer.is_some(), attrs.buffer_scale)
        });
        log::debug!("commit (toplevel={mapped}) buffer={buffer:?}");
        // Apply the committed buffer first: `Window::on_commit` caches the
        // geometry from surface state, so it must run after the buffer handler
        // or the window stays 0x0 and never produces render elements.
        smithay::backend::renderer::utils::on_commit_buffer_handler::<Self>(surface);
        if let Some(window) = self
            .windows
            .iter()
            .find(|(_, window)| window.wl_surface().map(|s| &*s == surface).unwrap_or(false))
            .map(|(_, window)| window.clone())
        {
            window.on_commit();
        }
        self.needs_redraw = true;
        self.space.refresh();
    }
}

impl BufferHandler for State {
    fn buffer_destroyed(&mut self, _buffer: &wl_buffer::WlBuffer) {}
}

impl ShmHandler for State {
    fn shm_state(&self) -> &ShmState {
        &self.shm_state
    }
}

impl SeatHandler for State {
    type KeyboardFocus = WlSurface;
    type PointerFocus = WlSurface;
    type TouchFocus = WlSurface;

    fn seat_state(&mut self) -> &mut SeatState<Self> {
        &mut self.seat_state
    }

    fn focus_changed(&mut self, _seat: &Seat<Self>, _focused: Option<&WlSurface>) {
        self.needs_redraw = true;
    }
}

impl SelectionHandler for State {
    type SelectionUserData = ();
}

impl ClientDndGrabHandler for State {}
impl ServerDndGrabHandler for State {}

impl DataDeviceHandler for State {
    fn data_device_state(&self) -> &DataDeviceState {
        &self.data_device_state
    }
}

impl OutputHandler for State {
    fn output_bound(&mut self, _output: Output, _wl_output: wl_output::WlOutput) {}
}

impl FractionalScaleHandler for State {
    fn new_fractional_scale(&mut self, surface: WlSurface) {
        let scale = self.scale.fractional();
        smithay::wayland::compositor::with_states(&surface, |states| {
            fractional_scale::with_fractional_scale(states, |state| {
                state.set_preferred_scale(scale);
            });
        });
    }
}

impl XdgShellHandler for State {
    fn xdg_shell_state(&mut self) -> &mut XdgShellState {
        &mut self.xdg_shell_state
    }

    fn new_toplevel(&mut self, surface: ToplevelSurface) {
        log::info!(
            "new_toplevel size={:?} scale={}",
            self.physical_size,
            self.scale.fractional()
        );
        let window = Window::new_wayland_window(surface.clone());
        surface.with_pending_state(|state| {
            state.states.set(smithay::reexports::wayland_protocols::xdg::shell::server::xdg_toplevel::State::Activated);
        });
        let logical = self.scale.logical_size(self.physical_size);
        configure_toplevel(&surface, (logical.0, logical.1).into());
        self.space
            .map_element(window.clone(), (0, 0), true);
        self.windows.push((surface, window));
        self.focus_topmost();
        self.needs_redraw = true;
    }

    fn new_popup(&mut self, surface: PopupSurface, _positioner: PositionerState) {
        if let Err(error) = surface.send_configure() {
            log::warn!("popup configure rejected: {error:?}");
        }
    }

    fn grab(&mut self, _surface: PopupSurface, _seat: wl_seat::WlSeat, _serial: Serial) {}

    fn reposition_request(
        &mut self,
        surface: PopupSurface,
        _positioner: PositionerState,
        token: u32,
    ) {
        if let Err(error) = surface.send_configure() {
            log::warn!("popup reposition rejected: {error:?}");
        }
        surface.send_repositioned(token);
    }

    fn fullscreen_request(&mut self, surface: ToplevelSurface, _output: Option<wl_output::WlOutput>) {
        surface.with_pending_state(|state| {
            state.states.set(smithay::reexports::wayland_protocols::xdg::shell::server::xdg_toplevel::State::Fullscreen);
        });
        let logical = self.scale.logical_size(self.physical_size);
        configure_toplevel(&surface, (logical.0, logical.1).into());
    }

    fn unfullscreen_request(&mut self, surface: ToplevelSurface) {
        surface.with_pending_state(|state| {
            state
                .states
                .unset(smithay::reexports::wayland_protocols::xdg::shell::server::xdg_toplevel::State::Fullscreen);
        });
        let logical = self.scale.logical_size(self.physical_size);
        configure_toplevel(&surface, (logical.0, logical.1).into());
    }

    fn toplevel_destroyed(&mut self, surface: ToplevelSurface) {
        if let Some(index) = self
            .windows
            .iter()
            .position(|(candidate, _)| candidate == &surface)
        {
            let (_, window) = self.windows.remove(index);
            self.space.unmap_elem(&window);
        }
        self.focus_topmost();
        self.needs_redraw = true;
    }
}

smithay::delegate_compositor!(State);
smithay::delegate_shm!(State);
smithay::delegate_data_device!(State);
smithay::delegate_seat!(State);
smithay::delegate_output!(State);
smithay::delegate_xdg_shell!(State);
smithay::delegate_fractional_scale!(State);
smithay::delegate_viewporter!(State);

