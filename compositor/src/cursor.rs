//! Cursor presentation.
//!
//! When a real mouse is attached, Android draws the pointer sprite itself,
//! above the app's surfaces. TAWC must not render a second cursor into the
//! Wayland scene, so a client's cursor request is mapped onto the Activity
//! SurfaceView's `PointerIcon` instead. See notes/input.md ("Cursor").
//!
//! Two protocols feed this: `wp_cursor_shape_v1` (named shapes — the common
//! case for GTK4 and recent Firefox) and legacy `wl_pointer.set_cursor` with
//! a client-drawn surface, which is what Xwayland uses. Named shapes become
//! a `PointerIcon.getSystemIcon` constant; surfaces are read out of their SHM
//! buffer and become a `PointerIcon.create` bitmap. Non-SHM cursor buffers
//! (wlegl/AHB) are not worth importing — those fall back to the arrow.

use smithay::backend::renderer::utils::with_renderer_surface_state;
use smithay::input::pointer::{CursorImageAttributes, CursorImageStatus};
use smithay::reexports::wayland_server::protocol::wl_shm;
use smithay::reexports::wayland_server::protocol::wl_surface::WlSurface;
use smithay::wayland::compositor::with_states;
use smithay::utils::IsAlive;
use smithay::wayland::shm::with_buffer_contents;

use crate::compositor::TawcState;
use crate::host::ActivityId;

/// Cursor bitmap cap. Android pointer sprites are small; anything bigger is
/// a client doing something unusual and not worth copying through JNI.
const MAX_CURSOR_DIM: i32 = 256;

pub(crate) struct State {
    /// Surface a client last handed us as its cursor image. Kept so a later
    /// commit on that surface refreshes the bitmap — clients animate cursors
    /// by committing new buffers to the same surface.
    surface: Option<WlSurface>,
    /// Last named shape (or `None` for hidden) pushed to Android, so repeated
    /// identical requests don't cross JNI. Bitmaps are not deduped: their
    /// content can change without the surface changing.
    last_named: Option<Option<&'static str>>,
}

impl State {
    pub(crate) fn new() -> Self {
        Self {
            surface: None,
            last_named: None,
        }
    }
}

/// What the state query reports for the current cursor: a shape name,
/// `hidden`, `bitmap`, or `none` when no client has asked for one. Android
/// draws the sprite, so this is the only place a test can observe it.
pub(crate) fn debug_shape(data: &TawcState) -> &'static str {
    if data.cursor.surface.as_ref().is_some_and(|s| s.alive()) {
        return "bitmap";
    }
    match data.cursor.last_named {
        Some(Some(name)) => name,
        Some(None) => "hidden",
        None => "none",
    }
}

/// A client set the cursor. Called from `SeatHandler::cursor_image`.
pub(crate) fn set_status(data: &mut TawcState, status: CursorImageStatus) {
    let Some(activity_id) = data.desktop_visible_host_id() else {
        // Nothing on screen to put a cursor on; drop it rather than
        // remembering state that the next visible host would inherit.
        data.cursor.surface = None;
        data.cursor.last_named = None;
        return;
    };

    match status {
        CursorImageStatus::Named(icon) => {
            data.cursor.surface = None;
            push_named(data, &activity_id, Some(icon.name()));
        }
        CursorImageStatus::Hidden => {
            data.cursor.surface = None;
            push_named(data, &activity_id, None);
        }
        CursorImageStatus::Surface(surface) => {
            data.cursor.last_named = None;
            data.cursor.surface = Some(surface.clone());
            push_surface(&activity_id, &surface);
        }
    }
}

/// A surface committed. Refreshes the icon if it is the live cursor surface.
pub(crate) fn after_commit(data: &mut TawcState, committed: &WlSurface) {
    if data.cursor.surface.as_ref() != Some(committed) {
        return;
    }
    let Some(activity_id) = data.desktop_visible_host_id() else {
        return;
    };
    push_surface(&activity_id, committed);
}

/// The cursor surface went away, or the pointer capability did.
pub(crate) fn reset(data: &mut TawcState) {
    data.cursor.surface = None;
    data.cursor.last_named = None;
}

fn push_named(data: &mut TawcState, activity_id: &ActivityId, shape: Option<&'static str>) {
    if data.cursor.last_named == Some(shape) {
        return;
    }
    data.cursor.last_named = Some(shape);
    // "" is the hidden cursor; Kotlin maps it to PointerIcon.TYPE_NULL.
    crate::set_pointer_icon_from_native(activity_id, shape.unwrap_or(""));
}

fn push_surface(activity_id: &ActivityId, surface: &WlSurface) {
    let hotspot = with_states(surface, |states| {
        states
            .data_map
            .get::<std::sync::Mutex<CursorImageAttributes>>()
            .map(|attrs| attrs.lock().unwrap().hotspot)
            .unwrap_or_default()
    });

    match read_shm_argb(surface) {
        Some((pixels, width, height)) => {
            crate::set_pointer_icon_bitmap_from_native(
                activity_id,
                &pixels,
                width,
                height,
                hotspot.x.clamp(0, width - 1),
                hotspot.y.clamp(0, height - 1),
            );
        }
        // Non-SHM (or not yet committed) cursor buffer: keep a usable arrow
        // rather than leaving whatever the last client asked for on screen.
        None => crate::set_pointer_icon_from_native(activity_id, "default"),
    }
}

/// Read the surface's attached SHM buffer as packed Android `ARGB_8888`
/// pixels (which are `Color.argb` ints, i.e. the same byte order as the
/// little-endian `wl_shm` ARGB8888/XRGB8888 formats read as `u32`).
fn read_shm_argb(surface: &WlSurface) -> Option<(Vec<i32>, i32, i32)> {
    let buffer = with_renderer_surface_state(surface, |state| state.buffer().cloned()).flatten()?;

    with_buffer_contents(&buffer, |ptr, len, data| {
        let (width, height, stride) = (data.width, data.height, data.stride);
        if width <= 0 || height <= 0 || width > MAX_CURSOR_DIM || height > MAX_CURSOR_DIM {
            return None;
        }
        let opaque = match data.format {
            wl_shm::Format::Argb8888 => false,
            wl_shm::Format::Xrgb8888 => true,
            _ => return None,
        };
        let needed = data.offset as usize + (height as usize - 1) * stride as usize
            + width as usize * 4;
        if stride < width * 4 || needed > len {
            return None;
        }

        let mut pixels = Vec::with_capacity((width * height) as usize);
        for row in 0..height as usize {
            let row_start = data.offset as usize + row * stride as usize;
            for col in 0..width as usize {
                // Safety: the bounds check above guarantees this stays inside
                // the mapped pool, and the pool is kept alive by the closure.
                let px = unsafe {
                    std::ptr::read_unaligned(ptr.add(row_start + col * 4) as *const u32)
                };
                pixels.push(if opaque { px | 0xff00_0000 } else { px } as i32);
            }
        }
        Some((pixels, width, height))
    })
    .ok()
    .flatten()
}
