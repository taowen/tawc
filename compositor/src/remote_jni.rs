//! JNI glue for remote access (the `tawc_remote` crate; see
//! notes/remote-access.md). Inert until `nativeRemoteStart`: no thread,
//! socket or runtime exists before it, and `nativeRemoteStop` joins them.
//!
//! One agent per process. Events go to `NativeBridge.onRemoteEvent` from
//! the agent's thread; the Kotlin side must not call back into these
//! functions synchronously from there (`nativeRemoteStop` joins that
//! thread).

use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;

use tawc_remote::{Agent, Config};

static AGENT: Mutex<Option<Agent>> = Mutex::new(None);
/// Final status of the last agent, once stopped.
static LAST: Mutex<Option<String>> = Mutex::new(None);

const STOPPED: &str = r#"{"state":"stopped","error":""}"#;

fn emit(json: String) {
    crate::with_native_bridge("onRemoteEvent", |env, class| {
        let s = env.new_string(json)?;
        env.call_static_method(class, "onRemoteEvent", "(Ljava/lang/String;)V", &[JValue::Object(&s)])?;
        Ok(())
    });
}

/// Start an agent from the app's JSON request. Null when started, else
/// why not (shown to the user).
#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_compositor_NativeBridge_nativeRemoteStart(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    crate::init_native_logging();
    crate::cache_jni_globals(&mut env);
    let result = match env.get_string(&request) {
        Ok(s) => start(s.into()),
        Err(_) => Err("bad request".to_string()),
    };
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => env.new_string(e).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

fn start(request: String) -> Result<(), String> {
    let mut slot = AGENT.lock().unwrap();
    if slot.as_ref().is_some_and(|a| a.is_running()) {
        return Err("already running".into());
    }
    // A previous agent that ended on its own (idle close, failure): reap it.
    if let Some(mut old) = slot.take() {
        old.stop();
    }
    let events = Arc::new(|e: tawc_remote::event::Event| {
        if let Ok(json) = serde_json::to_string(&e) {
            emit(json);
        }
    });
    let cfg = Config::from_json(&request, events).map_err(|e| {
        log::error!("remote: bad start request: {e}");
        "bad request".to_string()
    })?;
    let agent = Agent::start(cfg).map_err(|e| e.to_string())?;
    *slot = Some(agent);
    *LAST.lock().unwrap() = None;
    Ok(())
}

/// Stop and join. Every login is hung up. Idempotent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_compositor_NativeBridge_nativeRemoteStop(_env: JNIEnv, _class: JClass) {
    // Not under the lock while joining: status queries must not block
    // on a stop.
    let agent = AGENT.lock().unwrap().take();
    if let Some(mut a) = agent {
        a.stop();
        *LAST.lock().unwrap() = Some(a.status_json());
    }
}

/// Status JSON (`tawc_remote::Status`); `{"state":"stopped"}` when
/// nothing ever ran.
#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_compositor_NativeBridge_nativeRemoteStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = match AGENT.lock().unwrap().as_ref() {
        Some(a) => a.status_json(),
        None => LAST.lock().unwrap().clone().unwrap_or_else(|| STOPPED.to_string()),
    };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
