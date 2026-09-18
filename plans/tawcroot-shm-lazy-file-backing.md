# tawcroot `/dev/shm`: migrate a segment to a real file on narrower-mode reopen

Makes Chromium (and Electron) start under tawcroot without app flags, and
removes Firefox's `MOZ_SHM_NO_SEALS` special case, by giving guests a
genuine `O_RDONLY` fd for the few shm segments that ask for one, while
everything else stays memfd-backed.

Read first: `tawcroot/src/shm.c` (`reopen_for_guest`), `include/shm.h`,
and the `/dev/shm` + `MOZ_SHM_NO_SEALS` sections of
[notes/firefox.md](../notes/firefox.md).

## Problem (reproduced 2026-09-18, OnePlus 9, Arch Linux ARM)

`chromium --no-sandbox` exits with every graphics backend:

    GPU process exited unexpectedly: exit_code=5     (x6)
    FATAL: GPU process isn't usable. Goodbye.

`exit_code=5` is a raw wait status: SIGTRAP, a `CHECK` in the child
(`init: Untracked pid N received signal 5` in logcat). Every child type
dies (network service too under `--no-zygote`) before logging is up.
Not GL: `--disable-gpu` and unsetting the hybris `LD_LIBRARY_PATH`
change nothing.

Chromium creates shared memory as `/dev/shm/.org.chromium.Chromium.XXXXXX`
(`O_RDWR|O_CREAT|O_EXCL`), reopens the same path `O_RDONLY`, then
unlinks. Read-only regions (field trial, metrics, …) hand the `O_RDONLY`
fd to children. `reopen_for_guest` implements the second open via
`/proc/self/fd/<memfd>`; Android SELinux denies it (`avc: denied { open }
... memfd:.org.chromium.Chromium.* ... appdomain_tmpfs`), so it falls
back to `F_DUPFD` of the RDWR description. The crash being Chromium's
handle-permission check (`F_GETFL` must say read-only) is inferred from
the SIGTRAP plus the mode test below, not from a backtrace.

Minimal repro in the rootfs:

    a = open("/dev/shm/x", O_RDWR|O_CREAT|O_EXCL); b = open("/dev/shm/x", O_RDONLY)
    fcntl(b, F_GETFL) & O_ACCMODE  ->  O_RDWR, and write(b) succeeds

Workaround today: `chromium --no-sandbox --disable-dev-shm-usage` (shm
goes to real files in `/tmp`). Children survive; compositor reports one
rendered SHM toplevel.

## Why this is hard

Access mode belongs to the kernel's open file description, fixed at
`open()`. `dup`, `fork` and SCM_RIGHTS all share the description, and
`F_SETFL` can't change access mode, so a narrower second description
needs an `open()` by path. An Android app has no writable tmpfs, and
SELinux denies `open` on its own memfds (fd-only use by design);
`map_files` / `open_by_handle_at` need privileges, `pidfd_getfd` is a
dup. So a memfd yields exactly one description, RDWR. Seals and the
ashmem prot mask act on the whole object and don't change `F_GETFL`.

Every option gives up one part of the requirement:

| Option | What it gives up |
|---|---|
| Real files | Memory backing. Everything else works, at the cost of writeback to flash. |
| Dup fallback (today) | Read-only. The fd is writable, and Chromium's children (we assume) check. |
| Fail the reopen | The call itself. More honest, but apps that need the reopen still break (likely Firefox's freezable regions too). |
| Lie in `F_GETFL` | Kernel truth. The "read-only" fd is the same description as the writable one, so tawcroot would have to tag fd numbers and follow them through `dup`, `fork`, `exec` and socket passing; Chromium's check runs in another process after an SCM_RIGHTS hop. Also traps hot `fcntl`. |
| App flag | Generality. Makes Chromium stop asking, one app at a time. |

Real files for everything is rejected: bulk, constantly rewritten IPC
memory would be written back to flash.

## Design: migrate on trigger

Pay the real-file cost only for segments that actually get a
narrower-mode reopen. In `reopen_for_guest`'s caller, when the entry is
memfd-backed, the requested access mode is narrower than the internal
fd's, and the `/proc/self/fd` open failed:

1. Create a real file for the name in an app-private shm dir (under
   `/data/data/me.phie.tawc/`, not inside the guest-visible tree); open
   `O_RDWR`.
2. Copy contents if the memfd is non-empty (expected empty, see "Verify
   first").
3. `dup3` the real file over the recorded guest fd and over the internal
   reserved fd, so holders don't see the swap. Preserve each fd's
   CLOEXEC state.
4. Serve this and later opens of the name as ordinary `openat` on the
   file with the requested mode.
5. `shm_unlink` unlinks the file. Sweep the dir at session start for
   crash leftovers.

To support step 3, record the guest fd number (and pid) returned at
create time in `tawcroot_shm_entry`.

Safety checks at trigger time (each memfd has a unique inode):

- created in this process (pid matches) and the recorded guest fd still
  `fstat`s to the memfd inode;
- no other fd in `/proc/self/fd` has that inode;
- no mapping in `/proc/self/maps` has that inode.

If any check fails, keep today's behaviour (dup). Never worse than now.
Accepted blind spot: a child forked between create and reopen keeps the
orphaned memfd; neither browser forks in that window.

The `exec_state` ferry must carry the backing kind (and host path, or
just the fd) so a re-exec'd incarnation rebuilds the entry correctly.

Rejected variant: a per-process sticky switch to real files after the
first trigger. Simpler, but moves all of Chromium's shm to flash.

## Expected result

From memory of Chromium's `PlatformSharedMemoryRegion::Create`, only
`kWritable` (convertible-to-read-only) regions reopen; `kUnsafe` regions
(bitmaps, discardable memory, raster buffers) never do and stay on
memfds. So only small, rarely written regions land on flash.

## Second goal: drop `MOZ_SHM_NO_SEALS`

Set on every spawn by `RootfsEnv.kt:172`; it is app-specific code in a
universal path and should go. Migration alone does **not** remove the
need for it. Per notes/firefox.md the chain is: the parent's
`HaveMemfd()` probe (read-only reopen of its own `memfd_create` fd via
`/proc/self/fd/N`) is denied by the same SELinux rule → parent falls
back to `shm_open` (our emulation, unsealed) → children skip the probe,
assume memfd+seals, and `IsSafeToMap` rejects handles lacking
`F_SEAL_SHRINK`. A migrated segment is a real file, which can't carry
seals at all, so it would still be rejected.

Candidate directions, to be chosen after reading the current
`ipc/glue/SharedMemoryPlatform_posix.cpp` (the above is from our notes,
not re-verified against source):

- **Make the probe pass.** Apply the same migrate-on-trigger to a
  denied narrower-mode `openat("/proc/self/fd/N")` whose target is a
  guest-created memfd (tawcroot already rewrites `/proc` paths). The
  parent then takes its memfd path everywhere, and freezable regions
  migrate at their read-only reopen. Requires answering seals for the
  migrated file: trap only `fcntl` cmds `F_ADD_SEALS` / `F_GET_SEALS`
  (the seccomp filter can match on the cmd arg, so plain `fcntl` stays
  untrapped) and emulate them for file-backed segments. Needs a way to
  recognise such a file from an fd alone in another process (st_dev +
  a mode-bit or xattr tag set at creation), since the fd arrives over
  SCM_RIGHTS. Enforcement of grow/shrink seals would be advisory only.
- **Seal what we hand out.** Keep the parent on `shm_open`, but have
  the emulation make its segments satisfy `IsSafeToMap` (real
  `F_SEAL_SHRINK` on memfd-backed ones once sized; emulated
  `F_GET_SEALS` on file-backed ones). Smaller, but depends on when
  Firefox sizes the segment and whether it later shrinks it.

Whichever lands: remove the `put("MOZ_SHM_NO_SEALS", "1")`, and the
`libhybris::test_firefox_renders_via_ahb` test plus a manual
multi-tab check (the failure mode was "Gah. Your tab just crashed" on
Firefox >= 156) must pass without it. If neither direction works out,
keep the env var, say why in notes/firefox.md, and still land the
Chromium fix.

## Verify first

- Ordering: create → `O_RDONLY` reopen → unlink → `ftruncate` in both
  browsers. Temporarily log segment size and the safety-check outcome at
  trigger time; if segments are routinely non-empty or mapped, revisit.
- Which Chromium region modes reopen, and their sizes (same logging).
- No app opens a segment by name from a different, non-inheriting
  process before unlink (the table is per-process + inherited, so that
  already only works via inheritance).

## Tests

- Hosted tawcroot tests: swap happy path (guest fd and internal fd now
  the file; `O_RDONLY` fd reports `O_RDONLY` and rejects writes; data
  written via the RW fd is visible via the RO fd), each safety-check
  fallback, unlink + sweep, survival across the exec ferry. The hosted
  harness needs a way to force the `/proc/self/fd` open to fail, since
  the host allows it.
- Device: the Python repro above flips to `O_RDONLY`; `chromium
  --no-sandbox` without `--disable-dev-shm-usage` keeps its children and
  renders a toplevel; Firefox integration test passes with
  `MOZ_SHM_NO_SEALS` removed.

## Done

Update notes/firefox.md (`/dev/shm` section) and the tawcroot notes,
then delete this plan.

## Out of scope

With shm fixed, Chromium's GPU process still fails GL init on libhybris
(`ANGLE Display::initialize error 12289: Failed to get system egl
display`) and falls back to software compositing over SHM (magenta).
Not investigated; file an issue when this plan lands if it still
reproduces.
