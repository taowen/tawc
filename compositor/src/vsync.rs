//! Display vsync for the frame clock.
//!
//! A process-lifetime `tawc-vsync` thread owns an `ALooper` and that thread's
//! `AChoreographer`. The compositor arms one frame callback at a time
//! ([`Vsync::request`]); it fires at the next vsync and arrives as a
//! [`VsyncEvent`] on a calloop channel. Nothing armed means no
//! wakeups. See notes/rendering.md ("Frame clock").

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};

use log::error;
use ndk_sys::ALooper;
use smithay::reexports::calloop::channel::{self, Channel, Sender};

/// A vsync timestamp, `CLOCK_MONOTONIC` nanoseconds.
pub struct VsyncEvent {
    pub time_ns: i64,
}

struct Shared {
    /// The compositor wants a frame callback. Cleared when it fires.
    wanted: AtomicBool,
    /// The running compositor's end of the channel, if any.
    sender: Mutex<Option<Sender<VsyncEvent>>>,
}

struct LooperPtr(*mut ALooper);
// SAFETY: ALooper_wake is thread-safe, and the looper lives as long as
// the process (its thread never exits).
unsafe impl Send for LooperPtr {}

/// One vsync thread per process. It never exits: the NDK keeps each
/// thread's AChoreographer in a destructor-less `thread_local`, so a
/// thread per compositor run would leak its display-event socket and
/// looper fds every cycle.
static THREAD: Mutex<Option<(Arc<Shared>, LooperPtr)>> = Mutex::new(None);

/// A compositor run's handle on the vsync thread. Dropping it detaches
/// the channel.
pub struct Vsync {
    shared: Arc<Shared>,
    looper: *mut ALooper,
}

impl Vsync {
    pub fn spawn() -> std::io::Result<(Self, Channel<VsyncEvent>)> {
        let mut thread = THREAD.lock().unwrap();
        if thread.is_none() {
            let shared = Arc::new(Shared {
                wanted: AtomicBool::new(false),
                sender: Mutex::new(None),
            });
            let (looper_tx, looper_rx) = mpsc::channel();
            let thread_shared = shared.clone();
            std::thread::Builder::new()
                .name("tawc-vsync".into())
                .spawn(move || run(thread_shared, looper_tx))?;
            let looper = looper_rx.recv().map_err(|_| {
                std::io::Error::other("vsync thread exited before preparing its looper")
            })?;
            *thread = Some((shared, looper));
        }
        let (shared, looper) = thread.as_ref().unwrap();
        let (sender, channel) = channel::channel();
        *shared.sender.lock().unwrap() = Some(sender);
        Ok((Self { shared: shared.clone(), looper: looper.0 }, channel))
    }

    /// Arm one frame callback for the next vsync. Cheap when already armed.
    pub fn request(&self) {
        if !self.shared.wanted.swap(true, Ordering::AcqRel) {
            // SAFETY: the looper lives for the process.
            unsafe { ndk_sys::ALooper_wake(self.looper) };
        }
    }
}

impl Drop for Vsync {
    fn drop(&mut self) {
        *self.shared.sender.lock().unwrap() = None;
        self.shared.wanted.store(false, Ordering::Release);
    }
}

/// Lives on the vsync thread's stack; callbacks get a pointer to it.
struct ThreadState {
    shared: Arc<Shared>,
    posted: std::cell::Cell<bool>,
}

fn run(shared: Arc<Shared>, looper_tx: mpsc::Sender<LooperPtr>) {
    // SAFETY: plain NDK calls on this thread.
    let looper = unsafe { ndk_sys::ALooper_prepare(0) };
    let choreographer = unsafe { ndk_sys::AChoreographer_getInstance() };
    let _ = looper_tx.send(LooperPtr(looper));
    if choreographer.is_null() {
        error!("vsync: no AChoreographer; frames will not render");
        return;
    }

    let state = ThreadState { shared, posted: std::cell::Cell::new(false) };
    let data = &state as *const ThreadState as *mut c_void;
    loop {
        if !state.posted.get() && state.shared.wanted.load(Ordering::Acquire) {
            state.posted.set(true);
            // SAFETY: callbacks run inside pollOnce on this thread, and
            // `state` lives as long as the thread.
            unsafe { ndk_sys::AChoreographer_postFrameCallback64(choreographer, Some(on_frame), data) };
        }
        unsafe {
            ndk_sys::ALooper_pollOnce(-1, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut())
        };
    }
}

unsafe extern "C" fn on_frame(frame_time_nanos: i64, data: *mut c_void) {
    // SAFETY: `data` is the vsync thread's live ThreadState.
    let state = unsafe { &*(data as *const ThreadState) };
    state.posted.set(false);
    state.shared.wanted.store(false, Ordering::Release);
    if let Some(sender) = state.shared.sender.lock().unwrap().as_ref() {
        let _ = sender.send(VsyncEvent { time_ns: frame_time_nanos });
    }
}

/// Compositor-side vsync bookkeeping, for presentation feedback and
/// `query-state`.
#[derive(Default)]
pub struct FrameClock {
    /// Vsync ticks received. Stays put while idle.
    pub ticks: u64,
    /// Estimated vsync count (MSC), advanced across skipped vsyncs.
    pub msc: u64,
    pub last_vsync_ns: i64,
    /// Shortest gap between ticks over the last [`PERIOD_WINDOW`] ticks,
    /// which is the vsync period once anything animates; 0 until then.
    pub measured_period_ns: i64,
    /// Longest vsync-to-tick-done latency over the last [`PERIOD_WINDOW`]
    /// ticks. Near or above the period means the compositor itself drops
    /// frames.
    pub tick_latency_max_ns: i64,
    window_min_ns: i64,
    window_latency_ns: i64,
    window_ticks: u32,
}

const PERIOD_WINDOW: u32 = 32;

impl FrameClock {
    /// Record a tick. `period` is the advertised refresh period, used to
    /// estimate how many vsyncs passed since the last tick.
    pub fn tick(&mut self, time_ns: i64, period: std::time::Duration) {
        let period_ns = (period.as_nanos() as i64).max(1);
        let delta = time_ns - self.last_vsync_ns;
        if self.ticks > 0 && delta > 0 {
            self.msc += ((delta + period_ns / 2) / period_ns).max(1) as u64;
            if self.window_min_ns == 0 || delta < self.window_min_ns {
                self.window_min_ns = delta;
            }
            self.window_ticks += 1;
            if self.window_ticks == PERIOD_WINDOW {
                self.measured_period_ns = self.window_min_ns;
                self.tick_latency_max_ns = self.window_latency_ns;
                self.window_min_ns = 0;
                self.window_latency_ns = 0;
                self.window_ticks = 0;
            }
        } else {
            self.msc += 1;
        }
        self.ticks += 1;
        self.last_vsync_ns = time_ns;
    }

    /// Record when the current tick's work finished (`CLOCK_MONOTONIC` ns).
    pub fn tick_done(&mut self, now_ns: i64) {
        self.window_latency_ns = self.window_latency_ns.max(now_ns - self.last_vsync_ns);
    }
}
