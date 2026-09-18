# tawcroot: deliver SIGSYS on the guest's alternate signal stack

Fixes [issue #6](https://github.com/wmww/tawc/issues/6) ("Micro
segfaults") and, with it, every Go program under tawcroot.

## Problem

`tawcroot_install_handler` (`tawcroot/src/handler.c`) registers SIGSYS
with `SA_SIGINFO | SA_RESTORER` and **no `SA_ONSTACK`**, so the kernel
always builds the signal frame on the trapping thread's current stack
and our handler runs there too. `notes/tawcroot/sigsys-handler.md`
§"Handler stack budget" pins the supported floor at 16 KiB and
dismisses `sigaltstack` as unreliable ("per-thread state the guest owns
and can replace").

Go breaks that floor by design: goroutine stacks start at 2 KiB
(`runtime.fixedStack`) and grow only when *Go* code needs a bigger
frame. The Go runtime issues syscalls straight from the goroutine
stack, so a trapped `openat` from a 2 KiB goroutine makes the kernel
write several KiB *below* the stack's base. Unlike the 16 KiB fixture,
that memory is not unmapped — Go packs small stacks adjacently in
`stackpool` spans — so nothing faults. The neighbouring goroutine
stacks and the stack free-list links inside them are silently
overwritten, and the process dies later, somewhere random inside the
GC.

`micro` is just the first Go program anyone tried. `fzf` dies the same
way, headless, with no terminal involved:

```
seq 1 300000 | fzf --filter=99999
fatal error: fault
fatal error: unknown caller pc
```

3/3 runs. This is a Go-under-tawcroot bug, not a micro bug.

## Evidence

Measured on the OnePlus 9 (`.tawctarget=physical`, Android 14, kernel
5.4.284, `arch` install, 2026-09-18).

**Repro** — `pacman -Sy --noconfirm micro`, then:

```
scripts/rootfs-run.sh 'cd /tmp && script -qec "micro t.txt" /dev/null </dev/null'
```

Dies in under a second, every run. Two captured shapes, both memory
corruption rather than a plain bad pointer:

```
SIGSEGV: segmentation violation
PC=0x76c961b914 m=9 sigcode=1 addr=0x1b9d905d4259d1f5
runtime.stackcache_clear(...)        runtime/stack.go:328
runtime.(*mcache).prepareForSweep(...)
runtime.procresize(0x6)
runtime.startTheWorldWithSema(...)
runtime.gcMarkTermination.func3()
```

```
runtime: g4: frame.sp=0x7712435f40 top=0x7712435fd0
	stack=[0x7712435800-0x7712436000
fatal error: traceback did not unwind completely
```

That second one is the smoking gun: `0x7712435800-0x7712436000` is a
**2048-byte goroutine stack**, and its contents no longer unwind.

Not async preemption (`GODEBUG=asyncpreemptoff=1`), not parallelism
(`GOMAXPROCS=1`), not adaptive stack sizing
(`GODEBUG=adaptivestackstart=0|1`) — all still crash.

**How far below SP a trapped syscall writes.** A freestanding aarch64
probe (in the scratchpad, worth landing as a fixture — see below)
poisons 1 MiB, clones a child with its stack top 512 KiB in, issues one
syscall, then scans upward for the first clobbered qword:

| child's syscall | bytes written below the child's SP |
| --- | --- |
| none | 0 |
| `sched_yield` (untrapped) | 0 |
| `getpid` (trapped, shallow handler) | 4736–4799 |
| `openat` (trapped, full path resolution) | 6144–6207 |

So a trapped path-bearing syscall costs **~6.2 KiB** below SP on this
device: roughly 4.5 KiB of kernel `rt_sigframe` plus ~1.4 KiB of
handler chain. This phone has no SVE (`/proc/cpuinfo` Features), so on
SVE-capable hardware the kernel half grows.

6.2 KiB needed vs. a 2 KiB goroutine stack — a 3× overrun straight into
whatever Go put next door.

**Prototype fix.** Adding `SA_ONSTACK` to `sa_flags` (one line) and
rebuilding:

- `micro t.txt`: 5/5 runs stayed alive 15 s until killed, zero
  `SIGSEGV`, zero `fatal error`. Reverting the patch and reinstalling:
  3/3 crash again.
- `seq 1 300000 | fzf --filter=99999`: 3/3 clean exits patched, 3/3
  `fatal error` unpatched.
- `tawcroot/test.sh --host`: all 2212 pass.
- `tawcroot/test.sh --device`: 1677 pass, 1 fail —
  `hosted_close_range_keeps_reserved_fds_alive`, the pre-existing
  kernel-5.4 failure tracked in
  [issues/tawcroot-hosted-close-range-test-assumes-kernel-5-9.md](../issues/tawcroot-hosted-close-range-test-assumes-kernel-5-9.md).

It works because the premise in the note is half right. We can't
*rely* on an altstack existing — but `SA_ONSTACK` doesn't ask us to:
the kernel uses the altstack when one is installed and silently falls
back to the current stack when it isn't. Go installs a 32 KiB
`sigaltstack` per M in `runtime.minit`, which is where the frame lands
once the flag is set. Guests that never call `sigaltstack` keep exactly
today's behaviour and today's 16 KiB floor.

## Fix

### 1. Set `SA_ONSTACK` (the actual fix)

`tawcroot/src/handler.c`:

```c
sa.sa_flags = SA_SIGINFO | SA_RESTORER | SA_ONSTACK;
```

plus an `SA_ONSTACK` fallback define (0x08000000) next to the existing
`SA_RESTORER` one, and a comment explaining that this is what lets
small-stack runtimes work at all.

Check the two places the disposition is (re)installed — supervisor init
and anything the exec path redoes after `execve` (`execve` clears the
guest's altstack, so the flag is inert until the new image installs
one). Also confirm the guest-visible SIGSYS shadow in
`signal_shadow.c` / `handle_rt_sigaction` still reports the guest's own
`sa_flags` rather than ours, so a guest reading back its SIGSYS
disposition doesn't see a flag it never set.

### 2. Enforce a floor on guest altstacks (hardening)

With step 1 alone, a guest that installs an altstack *smaller* than our
~6.2 KiB need is worse off than before: it moves the frame off an 8 MB
thread stack onto, say, a musl `SIGSTKSZ` 8 KiB buffer. 8 KiB still
fits here, but the margin is ~1.8 KiB and the kernel half grows with
SVE — this shouldn't be left to luck.

Trap `sigaltstack` (aarch64 132 / x86_64 131) and virtualize it the
same way `rt_sigaction(SIGSYS)` already is:

- Keep a per-tid shadow of what the guest asked for; `sigaltstack(NULL,
  &old)` returns the guest's own values.
- If the guest's stack is at least `TAWCROOT_SIGALT_MIN` (pick from the
  measured worst case with headroom — 16 KiB), install it for real and
  we're done.
- If it's smaller, install a tawcroot-owned per-thread stack instead,
  so the guest's undersized buffer never receives our frame. It must
  come from fixed storage, not an allocator — the handler is
  allocation-free and libc-free (`notes/tawcroot/status.md`
  "Maintenance contract"), so this is a BSS slab indexed by tid slot,
  sized like the `signal_shadow.c` blocked-set table, with the same
  overflow-crashes-loudly behaviour.
- `SS_DISABLE` clears both.

Open question to settle during implementation: whether the guest's
small altstack should still be honoured for *its own* signals. It
can't be — disposition is per-signal but the stack is per-thread, so
substituting ours changes where the guest's own handlers run. That is
probably fine (a bigger stack never breaks a handler that fit in a
smaller one) but it is a real fidelity divergence and belongs in
`notes/tawcroot/status.md` §"Accepted syscall-fidelity divergences".

Step 2 is separable. Land step 1 first — it is the fix for #6.

### 3. Tests

- **Port the probe into a fixture.** `tests/integration/programs/
  static_sigaltstack_open_argv1_{aarch64,x86_64}.S`: install an
  altstack, do a trapped `openat`, assert the poison *below* the
  thread stack top is untouched. That is the regression test for
  step 1 — it fails today and passes with `SA_ONSTACK`.
- A second fixture with a deliberately undersized altstack (4 KiB)
  pins step 2: the frame must land on tawcroot's substitute, with the
  guest's 4 KiB buffer left clean.
- Keep `static_small_stack_open_argv1` as-is; it covers the
  no-altstack fallback, which must not regress.
- Device-only end-to-end: a Go binary is the honest test, but adding a
  Go toolchain to the build is not worth it. The
  `scripts/run-integration-tests.sh` layer could shell out to `micro
  --version`-style smoke only if a Go program is already installed —
  probably skip, and rely on the fixtures.

### 4. Docs

- `notes/tawcroot/sigsys-handler.md` §"Handler stack budget": replace
  the "sigaltstack cannot rescue this" paragraph. The new story is
  two-tier — altstack when the guest has one (that is how Go works),
  guest stack with the 16 KiB floor when it doesn't — and record the
  measured 6.2 KiB figure and how it was measured.
- `tawcroot/include/stack_budget.h`: same correction, shorter.
- `notes/tawcroot/status.md`: add the sigaltstack substitution to
  accepted divergences (step 2), and drop a line under "Confirmed
  environment" for the measured frame cost.

## Notes for whoever picks this up

- Everything above was measured with the app built from `main` at
  `b3fff97`; the `SA_ONSTACK` prototype was applied, verified, and
  reverted — the tree has no leftover patch.
- The probe sources (`stackprobe*.S`, NDK-built with
  `aarch64-linux-android29-clang -static -nostdlib`) were scratch and
  are gone. Rebuilding them from the description above is ~15 minutes;
  the table in this plan is the result to reproduce.
- proot and chroot install methods are unaffected — no SIGSYS, no
  in-guest signal frame. If someone reports this bug and is on a debug
  build, ask which method they used.
