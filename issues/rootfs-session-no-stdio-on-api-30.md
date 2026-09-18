# `tawc-exec --in-rootfs` runs the guest but relays no stdio on API 30

On the optional `tawc-api30` AVD (Android 11, API 30, kernel 5.4,
x86_64), rootfs sessions produce nothing:

    TAWC_TARGET=emulator scripts/rootfs-run.sh 'echo hello; id'
    # no output, exit 0

    TAWC_TARGET=emulator scripts/tawc-exec.sh --in-rootfs arch -- 'echo hi > /tmp/probe'
    # no output, exit 0, and /tmp/probe is never created in the rootfs

The session does start: logcat shows the app's session thread exec'ing
the guest —

    W tawc-exec-sessi: avc: granted { execute } for name="bash" ...
      scontext=u:r:untrusted_app:... app=me.phie.tawc

— but neither stdout/stderr nor the command's side effects appear, and
the broker reports success. No `avc: denied`, no Java exception, no
`tawc`/`tawc-native` logcat lines at all.

Not a regression and not tawcroot: on the same AVD, driving production
`libtawcroot.so` directly through the broker's ARGV form works fully
(`bash`, `pacman-key --init/--populate`, `pacman -Sy bc` with signature
checking all succeed). The same `rootfs-run.sh` command works on the
standard API 36 `tawc-rootless` AVD. So the fault is in the app-side
rootfs-session path (argv/stdio wiring), not the guest.

Guess at the shape: the session appears to start an interactive/login
shell and never delivers the command string, which would explain both
the missing output and the missing side effect.

Found while reproducing wmww/tawc#14 on an old-API AVD
(notes/emulator.md, "Day-to-day"). Low priority — API 30 is not a
standing test target — but it blocks using `rootfs-run.sh` for any
future old-API investigation.
