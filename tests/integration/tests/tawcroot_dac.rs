//! tawcroot's lazy CAP_DAC_OVERRIDE emulation, seen from a real guest
//! session on the standing target (notes/tawcroot/path-translation.md
//! §"DAC override").
//!
//! Upstream #12: `whoami` says root, `mkdir /test` says Permission
//! denied. The kernel sees the app uid and `untrusted_app` never holds
//! CAP_DAC_OVERRIDE, so a mode that denies the *owner* denied the
//! guest's "root" too. The other layers cover the mechanism; this one
//! proves it through the whole production stack — app process, real
//! rootfs, real SELinux domain.

use tawc_integration::adb;

/// Run `cmd` inside the rootfs; return (exit code, stdout, stderr)
/// with stdio trimmed.
fn run(cmd: &str) -> (i32, String, String) {
    let out = adb::rootfs_run(cmd).unwrap_or_else(|e| panic!("rootfs_run {cmd:?}: {e}"));
    (
        out.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&out.stdout).trim().to_string(),
        String::from_utf8_lossy(&out.stderr).trim().to_string(),
    )
}

/// `chmod 555 /` then create and remove a directory in it, then put the
/// mode back. The reported repro, plus the two things the fix must not
/// break: the guest still sees 555 while it is set (we widen and
/// restore, never widen), and `[ -w / ]` answers yes like it does for
/// real root.
///
/// One shell invocation so a failure mid-way still restores the mode —
/// a `/` left at 555 would be a confusing legacy for every later test,
/// even though the rescue itself keeps such a rootfs usable.
#[test]
fn test_dac_override_mkdir_under_unwritable_root() {
    tawc_integration::helpers::test_init();

    let script = concat!(
        "set -e; ",
        "orig=$(stat -c %a /); ",
        "trap 'chmod \"$orig\" /' EXIT; ",
        "echo \"orig=$orig\"; ",
        "chmod 555 /; ",
        "mkdir /tawc-dac-probe; ",
        "echo \"mode=$(stat -c %a /)\"; ",
        "[ -w / ] && echo writable; ",
        "rmdir /tawc-dac-probe; ",
        "echo ok",
    );
    let (code, stdout, stderr) = run(script);
    assert_eq!(code, 0, "DAC-override probe failed\n{stdout}\n{stderr}");
    assert!(
        stdout.contains("mode=555"),
        "the guest must keep seeing the mode it set:\n{stdout}",
    );
    assert!(
        stdout.contains("writable"),
        "`[ -w / ]` must answer yes for root:\n{stdout}",
    );
    assert!(stdout.contains("ok"), "probe did not finish:\n{stdout}\n{stderr}");

    let orig = stdout
        .lines()
        .find_map(|l| l.strip_prefix("orig="))
        .expect("probe did not report the original mode")
        .to_string();

    // The mode really is back, in a fresh session.
    let (code, now, stderr) = run("stat -c %a /");
    assert_eq!(code, 0, "stat / failed\n{now}\n{stderr}");
    assert_eq!(now, orig, "/ was left at the probe's mode");
}

/// A `--x--x--x` binary: real root execs it (the kernel's own override
/// covers the loader's read), and so must the guest. tawcroot has to
/// open the file O_RDONLY as the app uid twice — once in the execve
/// handler, once in the `--exec-child` loader after the re-exec — so
/// this is the case that needs both rescue entry points.
#[test]
fn test_dac_override_execs_exec_only_binary() {
    tawc_integration::helpers::test_init();

    let script = concat!(
        "set -e; ",
        "d=$(mktemp -d); ",
        "trap 'chmod -R u+rwX \"$d\"; rm -rf \"$d\"' EXIT; ",
        // `env` rather than `true`: bash resolves `command -v true` to
        // its own builtin, so there is no file to copy.
        "cp \"$(command -v env)\" \"$d/only-x\"; ",
        "chmod 111 \"$d/only-x\"; ",
        "\"$d/only-x\" >/dev/null; ",
        "echo \"mode=$(stat -c %a \"$d/only-x\")\"; ",
        "echo ok",
    );
    let (code, stdout, stderr) = run(script);
    assert_eq!(code, 0, "exec-only binary failed to run\n{stdout}\n{stderr}");
    assert!(
        stdout.contains("mode=111"),
        "the binary's mode must be restored after the rescue:\n{stdout}",
    );
    assert!(stdout.contains("ok"), "probe did not finish:\n{stdout}\n{stderr}");
}
