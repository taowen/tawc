//! Distro export / import end to end (notes/installation.md "Export /
//! import"). One main test on purpose: it installs a real distro
//! (through the dev mirror proxy) and every step builds on the previous
//! one. Real installs break the suite's persistent-state policy
//! (notes/testing.md), so both tests are opt-in: `TAWC_EXPORT_TESTS=1
//! scripts/run-integration-tests.sh distro_export::` (`--cfg
//! tawc_export_tests`); they remove their slots when they pass.
//!
//! Covers: hardlink/symlink/mode round trip through the tawcroot link
//! store, a working package manager after import, carried settings
//! (ando, binds, hidden launcher entries), guests killed and spawns
//! refused during export, the trailer check on a truncated archive
//! (FAILED slot, normal Delete clears it), and delete-after-export
//! both failing (source kept) and succeeding (source gone).

use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::time::{Duration, Instant};

use tawc_integration::adb;
use tawc_integration::exec_broker::{self, Invocation, Request};

const SRC: &str = "exptest-src";
const IMP: &str = "exptest-imp";
const BAD: &str = "exptest-bad";
const BIND_HOST: &str = "/data/local/tmp/tawc-dev/exptest-bind";
const BIND_GUEST: &str = "/mnt/exptest";
const HIDDEN: &str = "exptest-hidden-entry";

fn slot_dir(id: &str) -> String {
    format!("{}/distros/{id}", tawc_integration::app_data_dir())
}

fn text(out: &std::process::Output) -> String {
    format!(
        "{}\n{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    )
}

fn action_inv(name: &str, args: &[(&str, &str)]) -> Invocation {
    Invocation {
        foreground_app: true,
        request: Request::Action {
            name: name.to_string(),
            args: args.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
        },
    }
}

fn action(name: &str, args: &[(&str, &str)]) -> std::process::Output {
    exec_broker::run_capture(action_inv(name, args)).expect("broker action")
}

fn run_in(id: &str, cmd: &str) -> std::process::Output {
    exec_broker::run_capture(Invocation {
        foreground_app: false,
        request: Request::RunInside {
            install_id: id.to_string(),
            cmd: cmd.to_string(),
            op_title: None,
            graphics: None,
        },
    })
    .expect("broker runinside")
}

fn run_ok(id: &str, cmd: &str) -> String {
    let out = run_in(id, cmd);
    assert!(out.status.success(), "`{cmd}` in {id} failed:\n{}", text(&out));
    String::from_utf8_lossy(&out.stdout).into_owned()
}

fn host_sh(script: &str) -> std::process::Output {
    adb::rootfs_host_exec(&["/system/bin/sh", "-c", script]).expect("broker host exec")
}

fn slot_exists(id: &str) -> bool {
    let out = host_sh(&format!("test -d {} && echo EXISTS", slot_dir(id)));
    String::from_utf8_lossy(&out.stdout).contains("EXISTS")
}

fn metadata(id: &str) -> String {
    let out = host_sh(&format!("cat {}/metadata.json", slot_dir(id)));
    String::from_utf8_lossy(&out.stdout).into_owned()
}

fn uninstall(id: &str) {
    if slot_exists(id) {
        let out = action("uninstall", &[("id", id)]);
        assert!(out.status.success(), "uninstall {id} failed:\n{}", text(&out));
    }
    assert!(!slot_exists(id), "{id} still present after uninstall");
}

/// Export `id` to `path`; returns (exit code, stderr). `probe` runs
/// once, from the stderr callback, after quiesce has started.
fn export(
    id: &str,
    extra: &[(&str, &str)],
    path: Option<&std::path::Path>,
    mut probe: impl FnMut(),
) -> (i32, String) {
    let mut args = vec![("id", id)];
    args.extend_from_slice(extra);
    let mut sink: Box<dyn Write> = match path {
        Some(p) => Box::new(BufWriter::new(File::create(p).expect("create export file"))),
        None => Box::new(io::sink()),
    };
    let mut probed = false;
    let mut seen = String::new();
    let (code, stderr) = exec_broker::run_streaming(action_inv("export", &args), None, &mut sink, |chunk| {
        seen.push_str(&String::from_utf8_lossy(chunk));
        if !probed && seen.contains("[export] stopping programs") {
            probed = true;
            probe();
        }
    })
    .expect("export action");
    (code, String::from_utf8_lossy(&stderr).into_owned())
}

fn import(id: &str, label: &str, path: &std::path::Path) -> (i32, String) {
    let input = File::open(path).expect("open export file");
    let mut out = Vec::new();
    let (code, stderr) = exec_broker::run_streaming(
        action_inv("import", &[("id", id), ("label", label)]),
        Some(Box::new(input)),
        &mut out,
        |_| {},
    )
    .expect("import action");
    (
        code,
        format!("{}\n{}", String::from_utf8_lossy(&out), String::from_utf8_lossy(&stderr)),
    )
}

#[test]
#[cfg_attr(not(tawc_export_tests), ignore = "real distro installs: TAWC_EXPORT_TESTS=1")]
fn test_distro_export_import() {
    let tmp = std::env::temp_dir().join(format!("tawc-exptest-{}", std::process::id()));
    std::fs::create_dir_all(&tmp).unwrap();
    let archive = tmp.join("src.tawc.tar.zst");
    let truncated = tmp.join("truncated.tawc.tar.zst");

    for id in [SRC, IMP, BAD] {
        uninstall(id);
    }
    // Bind source: shell-owned scratch, opened up for the app uid.
    adb::shell(&format!(
        "mkdir -p {BIND_HOST} && echo bound > {BIND_HOST}/marker && chmod -R 777 {BIND_HOST}"
    ))
    .expect("adb shell");

    // --- source distro with ando, a bind and a hidden entry ----------
    let binds = format!(r#"[{{"hostPath":"{BIND_HOST}","guestPath":"{BIND_GUEST}"}}]"#);
    let out = action(
        "install",
        &[
            ("id", SRC),
            ("mirrorProxy", "http://127.0.0.1:8080/proxy/"),
            ("ando", "true"),
            ("externalBinds", &binds),
        ],
    );
    assert!(out.status.success(), "install failed:\n{}", text(&out));
    let out = action(
        "set-entry-hidden",
        &[("installId", SRC), ("entryId", HIDDEN), ("hidden", "true")],
    );
    assert!(out.status.success(), "set-entry-hidden failed:\n{}", text(&out));
    run_ok(
        SRC,
        "set -e; cd /root; echo payload > a; ln a b; ln -s a c; \
         mkdir d; echo x > d/f; chmod 0604 d/f; chmod 0750 d; \
         test \"$(stat -c %h b)\" = 2",
    );

    // --- export while a guest runs ----------------------------------
    let mut guest = exec_broker::spawn(Invocation {
        foreground_app: false,
        request: Request::RunInside {
            install_id: SRC.to_string(),
            cmd: "sleep 1000".to_string(),
            op_title: None,
            graphics: None,
        },
    })
    .expect("spawn guest");
    std::thread::sleep(Duration::from_secs(2));
    let mut refused = String::new();
    let (code, log) = export(SRC, &[], Some(&archive), || {
        refused = text(&run_in(SRC, "echo should-not-run"));
    });
    assert_eq!(code, 0, "export failed:\n{log}");
    assert!(
        refused.contains("being exported") && !refused.contains("should-not-run"),
        "spawn during export was not refused:\n{refused}"
    );
    assert!(
        guest.wait_timeout(Duration::from_secs(10)).expect("guest wait").is_some(),
        "running guest survived the export"
    );
    let bytes = std::fs::metadata(&archive).unwrap().len();
    assert!(bytes > 10 << 20, "archive suspiciously small: {bytes} bytes");
    let mut magic = [0u8; 4];
    io::Read::read_exact(&mut File::open(&archive).unwrap(), &mut magic).unwrap();
    assert_eq!(magic, [0x28, 0xb5, 0x2f, 0xfd], "not a zstd stream");

    // --- import as a new id -------------------------------------------
    let (code, log) = import(IMP, "Exptest Imported", &archive);
    assert_eq!(code, 0, "import failed:\n{log}");
    assert_eq!(run_ok(IMP, "cat /root/a"), "payload\n");
    let st = run_ok(IMP, "stat -c '%h %i' /root/a /root/b");
    let lines: Vec<&str> = st.lines().collect();
    assert_eq!(lines.len(), 2, "{st}");
    assert_eq!(lines[0], lines[1], "hardlink pair lost its shared inode: {st}");
    assert!(lines[0].starts_with("2 "), "nlink not 2: {st}");
    assert_eq!(run_ok(IMP, "readlink /root/c").trim(), "a");
    assert_eq!(run_ok(IMP, "stat -c %a /root/d /root/d/f").split_whitespace().collect::<Vec<_>>(), ["750", "604"]);
    // New links in the restored store (fresh inodes, old tokens).
    assert_eq!(run_ok(IMP, "cd /root && ln a e && stat -c %h a").trim(), "3");
    run_ok(IMP, "if command -v pacman >/dev/null; then pacman -Q bash; else dpkg -s bash >/dev/null; fi");
    // Carried settings.
    assert!(run_ok(IMP, &format!("cat {BIND_GUEST}/marker")).contains("bound"));
    assert!(run_ok(IMP, "ando /system/bin/echo ando-ok").contains("ando-ok"));
    let meta = metadata(IMP);
    assert!(meta.contains(HIDDEN), "hidden entry not carried:\n{meta}");
    assert!(meta.contains("\"importedAtMillis\""), "{meta}");
    assert!(meta.contains("\"label\": \"Exptest Imported\""), "{meta}");
    assert!(meta.contains("\"state\": \"READY\""), "{meta}");
    // The source is untouched.
    assert_eq!(run_ok(SRC, "cat /root/a"), "payload\n");

    // --- truncated archive -> FAILED, Delete clears it ---------------
    {
        let data = std::fs::read(&archive).unwrap();
        std::fs::write(&truncated, &data[..data.len() / 2]).unwrap();
    }
    let (code, log) = import(BAD, "Exptest Bad", &truncated);
    assert_ne!(code, 0, "truncated import succeeded:\n{log}");
    assert!(log.to_lowercase().contains("truncat"), "no truncation error:\n{log}");
    assert!(metadata(BAD).contains("\"state\": \"FAILED\""), "{}", metadata(BAD));
    uninstall(BAD);

    // --- delete-after-export ------------------------------------------
    let (code, log) = export(SRC, &[("deleteAfter", "true"), ("failAfterBytes", "1000000")], None, || {});
    assert_ne!(code, 0, "export with an injected write failure succeeded:\n{log}");
    assert!(metadata(SRC).contains("\"state\": \"READY\""), "source changed after failed export");
    std::thread::sleep(Duration::from_secs(3));
    assert!(slot_exists(SRC), "failed export deleted the source");

    let (code, log) = export(SRC, &[("deleteAfter", "true")], None, || {});
    assert_eq!(code, 0, "delete-after export failed:\n{log}");
    let start = Instant::now();
    while slot_exists(SRC) {
        assert!(start.elapsed() < Duration::from_secs(300), "source not deleted after export");
        std::thread::sleep(Duration::from_secs(2));
    }

    uninstall(IMP);
    let _ = adb::shell(&format!("rm -rf {BIND_HOST}"));
    let _ = std::fs::remove_dir_all(&tmp);
}

/// `tawc-exec` against another app id (`TAWC_PACKAGE`), bypassing the
/// suite's broker port, which belongs to the package under test.
fn peer_exec(peer: &str, args: &[&str], stdin: Option<&std::path::Path>) -> std::process::Output {
    let mut cmd = std::process::Command::new(env!("CARGO_BIN_EXE_tawc-exec"));
    cmd.env("TAWC_PACKAGE", peer).env_remove("TAWC_EXEC_BROKER_PORT").args(args);
    if let Some(p) = stdin {
        cmd.stdin(File::open(p).expect("open export file"));
    }
    cmd.output().expect("run tawc-exec")
}

/// Export from the package under test, import into a side-by-side peer
/// build (a different app id, so different host paths), and check the
/// distro works there and carries no path of the source app.
///
/// Needs the peer installed:
/// `TAWC_PACKAGE=me.phie.tawc.exptest scripts/app-build-install.sh --no-launch`,
/// then `TAWC_EXPORT_TESTS=1 TAWC_EXPORT_PEER_PACKAGE=me.phie.tawc.exptest
/// scripts/run-integration-tests.sh distro_export::`.
#[test]
#[cfg_attr(
    not(tawc_export_peer),
    ignore = "needs a peer app id: TAWC_EXPORT_TESTS=1 TAWC_EXPORT_PEER_PACKAGE=<id>"
)]
fn test_distro_export_import_cross_package() {
    let peer = std::env::var("TAWC_EXPORT_PEER_PACKAGE").expect("TAWC_EXPORT_PEER_PACKAGE");
    let src_pkg = tawc_integration::app_package();
    assert_ne!(peer, src_pkg);
    let tmp = std::env::temp_dir().join(format!("tawc-exptest-x-{}", std::process::id()));
    std::fs::create_dir_all(&tmp).unwrap();
    let archive = tmp.join("x.tawc.tar.zst");
    let id = "exptest-x";

    uninstall(id);
    let _ = peer_exec(&peer, &["--foreground-app", "--action", "uninstall", "--arg", &format!("id={id}")], None);
    let out = action("install", &[("id", id), ("mirrorProxy", "http://127.0.0.1:8080/proxy/")]);
    assert!(out.status.success(), "install failed:\n{}", text(&out));
    run_ok(id, "cd /root && echo cross > a && ln a b");
    let (code, log) = export(id, &[], Some(&archive), || {});
    assert_eq!(code, 0, "export failed:\n{log}");
    uninstall(id);

    let out = peer_exec(
        &peer,
        &["--foreground-app", "--action", "import", "--arg", &format!("id={id}")],
        Some(&archive),
    );
    assert!(out.status.success(), "peer import failed:\n{}", text(&out));
    let out = peer_exec(
        &peer,
        &[
            "--in-rootfs", id, "--",
            "cat /root/a && stat -c %h /root/b && \
             if command -v pacman >/dev/null; then pacman -Q bash; else dpkg -s bash >/dev/null; fi",
        ],
        None,
    );
    let o = text(&out);
    assert!(out.status.success() && o.contains("cross") && o.contains("\n2\n"), "peer distro broken:\n{o}");
    // Nothing in the moved distro may point into the source app's data.
    let out = peer_exec(
        &peer,
        &[
            "--", "/system/bin/sh", "-c",
            &format!(
                "grep -rlF -e /data/data/{src_pkg}/ -e /data/user/0/{src_pkg}/ \
                 /data/data/{peer}/distros/{id}; true"
            ),
        ],
        None,
    );
    let hits = String::from_utf8_lossy(&out.stdout).into_owned();
    assert!(hits.trim().is_empty(), "source-app paths in the imported distro:\n{hits}");

    let out = peer_exec(&peer, &["--foreground-app", "--action", "uninstall", "--arg", &format!("id={id}")], None);
    assert!(out.status.success(), "peer uninstall failed:\n{}", text(&out));
    let _ = std::fs::remove_dir_all(&tmp);
}
