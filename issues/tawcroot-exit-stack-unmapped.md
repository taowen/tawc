# Reclaim per-thread shadow state without trapping exit

An ARM64 Codex CLI worker built with musl unmaps its own thread stack before
calling raw `exit(2)`. A seccomp `RET_TRAP` on that syscall tries to deliver
SIGSYS with no usable stack and the process dies with SIGSEGV. Do not trap
`exit(2)`; the Redmi Codex model request runs after removing the dispatch.

The old exit handler also reclaimed the SIGSYS blocked-mask shadow slot and
substitute signal-altstack slot. Those may now survive thread death. A reused
TID could read a stale blocked bit, and enough short-lived threads with small
altstacks could exhaust the fixed slab. Find a reclamation scheme that does not
need a handler on a dying thread's stack, then test TID reuse and many thread
teardowns on device. The current altstack exhaustion path falls back to the
guest's stack; the blocked-mask table has a hard capacity.
