# close_range: stop re-issuing NR 436 from inside the SIGSYS handler

Fixes https://github.com/wmww/tawc/issues/14. **Status: plan, not started.**

## Problem

`handle_close_range` (`tawcroot/src/syscalls_fd.c`) re-issues raw
`close_range` from inside the SIGSYS handler. Where Android's zygote
seccomp policy predates NR 436 (reported: Android 11; expected: every
API ≤ 33, since bionic only gained `close_range` at API 34 — unverified)
the outer filter RET_TRAPs it. SIGSYS is masked in the handler
(`sa_mask = ~0`), so the kernel force-kills the process: no log, no exit
code. seccomp runs before the kernel's ENOSYS check, so kernel age doesn't
help. glibc's closefrom before exec hits this on every gpg/gpg-agent/
dirmngr spawn → pacman "GPGME error: Invalid crypto engine".

Not seen so far because every test device is API ≥ 34 (OnePlus 9: API 34,
emulator: API 36), where the policy allows 436.

Test gap: `androidfilter/wrap.c` traps 437/439/435 but not 436, although
`test_androidfilter.c`'s header claims it does; notes/tawcroot/testing.md
says the omission is deliberate because "Android's filter doesn't appear
to trap it" — true only on new Android.

## Fix: emulate, never emit NR 436

No probe, no fork, no cached mode (the issue's patch 0003 forks a probe
child from the handler per exec'd process and falls back to a
0..RLIMIT_NOFILE loop; rejected). Instead always emulate in the handler
with syscalls that are in every Android policy:

1. `first > last` or `flags & ~(CLOSE_RANGE_UNSHARE|CLOSE_RANGE_CLOEXEC)`
   → `-EINVAL`.
2. `CLOSE_RANGE_UNSHARE` → raw `unshare(CLONE_FILES)` first, propagate
   its error.
3. Raw `openat("/proc/self/fd", O_RDONLY|O_DIRECTORY|O_CLOEXEC)`, raw
   `getdents64` into a small buffer (mind `stack_budget.h`'s frame cap;
   reuse the dirent walking helpers behind the existing dirent filter if
   they fit). For each numeric entry in `[first, last]` that is not the
   dir fd itself and not `tawcroot_fd_is_reserved`: raw `close`, or raw
   `fcntl(F_SETFD, FD_CLOEXEC)` for `CLOSE_RANGE_CLOEXEC`. Ignore
   per-fd errors like the kernel does.
4. Close mode mutates the directory while iterating: after a pass that
   closed anything, `lseek(dirfd, 0)` and rescan until a pass closes
   nothing (glibc `__closefrom_fallback` shape). CLOEXEC mode needs one
   pass.
5. If `/proc/self/fd` can't be opened (EMFILE/ENFILE, no /proc): fall
   back to a linear loop over `[first, min(last, RLIMIT_NOFILE.cur - 1)]`
   via raw `prlimit64`. Slow but correct, and rare.

Cost scales with open fds, not the fd limit; all raw calls come from
tawcroot's allowlisted IP so none trap. Loses the two-syscall fast path on
API ≥ 34 — acceptable, closefrom happens once per spawn.

New syscall numbers needed in `include/sysnr.h` (both arches): check
`getdents64`, `unshare`, `prlimit64`, `lseek` — add what's missing.

Update the handler comment, and `notes/tawcroot/architecture.md` /
`path-translation.md` / `status.md` wherever they describe the
gap-splitting `close_range`.

## Testing (emulator + host only)

Order matters: land each test first, watch it go red, then fix.

### 1. Host: make the synthesized filter trap 436 (primary regression test)

- `tawcroot/tests/handler/androidfilter/wrap.c`: add `close_range` (436)
  to the default RET_TRAP set on both arches; fix the header comment.
- Expect red before the fix: the testhost is killed at the first
  `close_range` smoke step in `rootfs_smoke.c` (~line 2987), so the
  `androidfilter` module fails from there on. `tawcroot/test.sh --host
  '.*androidfilter.*'`.
- After the fix the same steps pass. With 436 trapped by the outer filter
  this test also proves the handler emits no raw 436 at all, which covers
  the kernel < 5.9 case without needing an old kernel.
- Remove the `close_range_unsupported` / "kernel <5.9" skip branches in
  `rootfs_smoke.c`, and the ENOSYS sensitivity in
  `tests/hosted/test_fd_handlers.c`
  (`hosted_close_range_keeps_reserved_fds_alive`): results no longer
  depend on the kernel.
- Add smoke steps: `CLOSE_RANGE_CLOEXEC` sets the flag on guest fds and
  leaves reserved (non-CLOEXEC shm) fds untouched; a guest fd above the
  reserved cluster is closed; `first > last` and bad flags → `-EINVAL`;
  `close_range` with many (>1 getdents buffer) open fds closes all of
  them (exercises the rescan loop).
- Fix the claim in notes/tawcroot/testing.md ("close_range because … 
  Android's filter doesn't appear to trap it").

### 2. Emulator, current API 36 AVD: prod-env closefrom guest

- Add `static_fork_closefrom_exec_argv1` (fixture already exists for both
  arches, used by `test_prod_fork.c`) as a case in
  `tests/integration/tests/tawcroot_prodenv.rs`: production
  `libtawcroot.so` under the real zygote filter via the broker.
- `scripts/run-integration-tests.sh tawcroot_prodenv::`. Passes before and
  after on API 36 (policy allows 436) — this is the no-regression check on
  the standing target, and becomes the reproducer in step 3.

### 3. Emulator, old-API AVD: reproduce against the real policy

Needs a one-time image download — ask the user first (network + disk):

    sdkmanager 'system-images;android-30;google_apis;x86_64'
    echo no | avdmanager create avd -n tawc-api30 \
        -k 'system-images;android-30;google_apis;x86_64' -d 'pixel_5'

Start with `scripts/emulator.sh stop` then
`TAWC_AVD=tawc-api30 scripts/emulator.sh start rootless` (never launch
the emulator directly). Only installed images today are android-34 and
android-36. API 30 matches the report; `minSdk` is 29 so the app installs.
NR 436 is the same on x86_64 and aarch64 and the policy is generated from
the same bionic lists, so x86_64 should trap too — step (a) checks that
assumption.

a. Pre-fix build: run the step-2 prodenv case. Expect the guest to vanish
   (killed by SIGSYS, not a tawcroot exit code). If it passes instead, API
   30 x86_64 doesn't trap 436: try `android-29`, and if that also passes
   the real-policy check falls back to asking the issue reporter to test
   a build; steps 1–2 still stand.
b. Post-fix build: same case passes.
c. End to end (best effort; graphics is irrelevant): install Arch with
   `--arg mirrorProxy=http://127.0.0.1:8080/proxy/` (ask the user to start
   the cache proxy if refused), then
   `scripts/rootfs-run.sh 'pacman -S --noconfirm <small pkg>'` and confirm
   the signature check passes.
d. While there, `adb logcat | grep avc` during a `getaddrinfo` (e.g.
   `curl`) for the `disable_ipv6` denial from the issue's patch 0002. If
   it reproduces, file `issues/` for it — separate change, not part of
   this plan.

Afterwards stop the AVD and restart the standard one
(`scripts/emulator.sh start`). Document the optional old-API AVD in
notes/emulator.md (and notes/building.md if it counts as a dev
dependency); decide then whether it is worth keeping as a recurring
target.

### Optional, not required

When the physical phone (API 34, kernel 5.4) is free:
`TAWC_TARGET=physical tawcroot/test.sh --device '.*close_range.*'` should
now pass, confirming `issues/tawcroot-hosted-close-range-test-assumes-kernel-5-9.md`.

## Wrap-up

- Delete `issues/tawcroot-hosted-close-range-test-assumes-kernel-5-9.md`
  (the emulation makes it moot; step 1 covers it without the phone).
- Reply on #14: patch 0001 already landed (`fd3364e`), 0003 replaced by
  emulation, 0002 tracked separately; ask the reporter to confirm on the
  Lenovo. Only post when the user asks.
- Fold the durable bits (nested-masked-SIGSYS rule: handlers must never
  emit a syscall newer than the oldest supported Android policy) into
  notes/tawcroot/ and delete this plan.
