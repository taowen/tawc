//! Phase-0 spike / manual live check: serve `/bin/sh` through the relay.
//!
//!     cargo run --example relay-spike [RELAY_URL] [IDLE_SECONDS]
//!
//! Prints the `ssh -J` command; connect with the host's ssh.

use std::sync::Arc;
use std::time::Duration;

fn main() {
    let mut args = std::env::args().skip(1);
    let relay = args.next().unwrap_or_else(|| "https://sshyeet.com".into());
    let secs: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(600);
    let mut agent = tawc_remote::Agent::start(tawc_remote::Config {
        transport: tawc_remote::agent::Transport::Relay(relay),
        login: tawc_remote::agent::Login::Secret,
        host_key: None,
        idle_timeout: Some(Duration::from_secs(secs)),
        agent: format!("tawc/spike {}/{}", std::env::consts::OS, std::env::consts::ARCH),
        version: "spike".into(),
        launcher: Arc::new(tawc_remote::spawn::HostShell { shell: "/bin/sh".into() }),
        events: Arc::new(|e| {
            if e.kind != "status" {
                eprintln!("event {} {} {}", e.kind, e.from, e.msg);
            }
        }),
    })
    .expect("start");
    let mut last = String::new();
    while agent.is_running() {
        let s = agent.status();
        let line = serde_json::to_string(&s).unwrap();
        if line != last {
            println!("{line}");
            last = line;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    agent.stop();
    println!("{}", serde_json::to_string(&agent.status()).unwrap());
}
