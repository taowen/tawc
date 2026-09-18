//! Server bindings for TAWC-internal Wayland protocols:
//!   * `android_wlegl` — used by libhybris's Wayland EGL platform to
//!     ship gralloc buffer handles.
//!   * `tawc_gfxstream` — optional gfxstream-bridge custom Vulkan WSI (chroot
//!     hands the compositor a colorbuffer_id; compositor looks up the
//!     backing AHardwareBuffer in its in-process gfxstream FrameBuffer).

pub mod android_wlegl {
    pub mod server {
        use wayland_server;
        use wayland_server::protocol::*;
        pub mod __interfaces {
            use wayland_server::backend as wayland_backend;
            use wayland_server::protocol::__interfaces::*;
            wayland_scanner::generate_interfaces!("protocols/android_wlegl.xml");
        }
        use self::__interfaces::*;

        wayland_scanner::generate_server_code!("protocols/android_wlegl.xml");
    }
}

#[cfg(feature = "gfxstream")]
pub mod tawc_gfxstream {
    pub mod server {
        use wayland_server;
        use wayland_server::protocol::*;
        pub mod __interfaces {
            use wayland_server::backend as wayland_backend;
            use wayland_server::protocol::__interfaces::*;
            wayland_scanner::generate_interfaces!("protocols/tawc_gfxstream.xml");
        }
        use self::__interfaces::*;

        wayland_scanner::generate_server_code!("protocols/tawc_gfxstream.xml");
    }
}
