# Thread exit: removed mask shadow, remaining altstack lifetime risk

An ARM64 Codex CLI worker built with musl unmaps its own thread stack before
calling raw `exit(2)`. A seccomp `RET_TRAP` on that syscall tries to deliver
SIGSYS with no usable stack and the process dies with SIGSEGV. Do not trap
`exit(2)`; the Redmi Codex model request runs after removing the dispatch.

The old exit handler also reclaimed the SIGSYS blocked-mask shadow slot and
substitute signal-altstack slot. Removing that handler left stale state on
thread death. The mask table is now removed; the remaining altstack lifetime
problem must be solved without a handler on a dying thread's stack.

## Confirmed ARLinux failure (2026-09-27)

On Redmi 29854870, Omarchy Thunar exits with SIGTRAP while moving the
pointer between Thunar and OpenCode. Attaching GDB catches a `gly-rayon`
worker in `tawc_sigshadow_blocked_set`, called by `handle_rt_sigprocmask`.
At the trap, all 256 slots are occupied and marked blocked; GDB lists
13 live threads, and 255 slot TIDs are absent from that live-thread list.
This is stale thread state, not 256 concurrently blocked workers.

Reclaiming slots when a thread unblocks passes the 9,000-TID churn unit
test but does not fix the device failure: threads also exit while blocked.
Increasing the table only postpones exhaustion. A dead-TID sweep needs
careful handling of TID reuse and concurrent slot claims; publishing a
tombstone before clearing its payload lets a sweeper overwrite a new owner.
The fix removes the blocked-mask table entirely. SIGSYS is explicitly
reserved: attempts to block it are ignored and queries report it unblocked.
Other signals keep the kernel's per-thread mask semantics. No exit hook,
dead-TID sweep, or capacity increase is needed. The syscall smoke now creates
512 real threads in batches, each exiting after requesting SIGSYS blocked.

The substitute-altstack slab is separate and remains unresolved. It uses
the kernel's stack address rather than a TID, but thread exit can still leak
a slot. Do not treat that risk as fixed by removing the mask table.

Validation: 407 focused host checks pass, including 512 cloned-thread
lifecycles through the real syscall trap, mask aliasing and EFAULT rollback.
On Redmi, 1,000 Python thread lifecycles pass inside the Omarchy instance;
Thunar remains the same process after 160 cross-window pointer movements
and a 120-second monitor. The debug APK contains the new runtime.

Use a file or `/dev/null` for detached GUI process output during regression.
The initial monitor left Thunar writing to a pipe after its reader exited;
GDB independently reproduced SIGPIPE from that test harness. With persistent
output, another 200 cross-window movements and folder entry did not reproduce
the original SIGTRAP. Do not mistake harness-induced SIGPIPE for slot overflow.
