# tawcroot: emulate CAP_DAC_OVERRIDE for the fake root

Upstream report: https://github.com/wmww/tawc/issues/12 — `whoami` says
`root`, `mkdir /test` says `Permission denied`. Release build, so
nothing ever ran as real root.

## Problem (verified 2026-09-09, physical target, arch install)

tawcroot fakes uid 0 (`getuid`/`geteuid` → 0, stat decorated
root-owned) but the kernel sees the app uid on every real syscall and
`untrusted_app` never holds CAP_DAC_OVERRIDE. A directory whose mode
denies the *owner* write blocks the guest's "root" too:

```
chmod 555 /   →  mkdir: cannot create directory '/probe_b': Permission denied
chmod 700 /   →  mkdir succeeds
```

How the bit gets lost:

- Not ownership. `chown` needs CAP_CHOWN, which we never have, so
  tawcroot's chown is cosmetic (`syscalls_fs.c` fchownat handler) and
  every inode in a production rootfs is owned by the app uid. Mode is
  the only lever.
- Not the installer. `ProotArchiveExtractor.kt:124` skips the
  archive's root entry, so the rootfs dir keeps `mkdirs()`'s 0700
  under Android's 0077 umask. On device: ALARM `/` is 0700, Debian
  sid `/` is 0755 (`base-files`' `./` entry applied by dpkg inside
  the guest). Both writable.
- The guest itself. `chmod` needs only ownership, not DAC override,
  so guest root can `chmod 555 /` and it sticks; only the next write
  fails. `tar -x` of an archive with a restrictive `./`, a package
  shipping an odd `./` mode, a script copying modes from another
  tree — all do it with no privilege involved.

Ruled out: a read-only bind returns EROFS (`path.c`), not EACCES, and
`mkdirat` is a straight passthrough (`syscalls_fs.c:1136`,
`DECLARE_AT_PASS`). `fchmodat`/`fchmod` only swallow EPERM/EACCES from
the chmod itself; they never widen anything.

**This is a regression from the proot method, not a distro property.**
proot `-0` emulates CAP_DAC_OVERRIDE eagerly: fake_id0's `HOST_PATH`
hook calls `override_permissions`
(`deps/proot/src/extension/fake_id0/fake_id0.c:372`), which chmods
every path component to u+rw (+u+x for directories) before the
syscall and restores the old mode via talloc destructors at syscall
exit. Our proot build does not define `USERLAND`, so that path was
active. `mkdir /test` worked under proot and broke when tawcroot
became the default.

The same class already shows up elsewhere: the ALARM bootstrap's
0500 `/etc/ca-certificates/extracted/cadir` (why the extractor defers
directory modes) and `RootfsCleaner.kt:120`'s `chmod -R u+rwX`.

Not covered by the report: `access(W_OK)`. `[ -w / ]` on a 0555 root
answers no under tawcroot, and scripts act on that. Real root gets
yes.

## Design: lazy DAC override at dispatch

Do what proot does, but on the error path only. proot stats every
component of every path syscall; that per-syscall overhead is exactly
what tawcroot exists to avoid. On the happy path nothing changes.

1. **One rescue wrapper at the dispatch call site**, not a retry per
   handler. unlinkat, renameat2, linkat and openat are multi-step
   (link store, O_CREAT retries), so "retry the raw syscall" does not
   fit them, and an EACCES can come from a store operation on a route
   the handler never exposed. Instead the translator records each
   translated route (`fd` + `path` + resolution mode) into a small
   per-call rescue context owned by the wrapper (fixed size, no
   malloc; a handler that overflows it is simply not rescued). The
   wrapper replaces the bare `fn(&args, uc)` in `handler.c`: when the
   handler returns EACCES and the virtual euid is 0
   (`tawcroot_identity_euid() == 0`), widen, re-invoke the handler
   once, restore in reverse order regardless of the result. Expose it
   as a function so hosted tests can call it. New path handlers are
   covered for free.
2. **What gets widened.** Walk each recorded route: the base fd, then
   each prefix of `path`. Widen only inodes the app owns
   (`st_uid == real uid`):
   - directories lacking `u+rwx` → add them;
   - the leaf regular file lacking `u+rw` → add them, but only for
     syscalls that need leaf access (per-syscall flag: open, truncate,
     access, exec, chdir, xattr). unlink, mkdir, mknod, symlink, link
     and rename need the parent only; widening their leaf is wasted
     work and the by-path restore then misses because the name is gone
     or moved.
   - **Never chmod through a symlink.** Leaves are symlinks under the
     NOFOLLOW ops, and every emulated hardlink name is a
     `tawcroot:link:<token>` symlink. `fchmodat` follows symlinks and
     would resolve against the host root. Stat with
     `AT_SYMLINK_NOFOLLOW` and skip anything that is not a directory
     or regular file.
   - Route base fds are O_PATH, where `fchmod` is EBADF; use
     `fchmodat(fd, ".")`.
   - Record old modes in a fixed-size array bounded by the path
     component cap; refuse (no rescue) beyond it.
3. **Never add x to files.** That is CAP_DAC_OVERRIDE's exact rule:
   root cannot exec a file with no exec bit, and `access(X_OK)` on
   such a file is EACCES for root too. The exec handler's own "no x
   bit → EACCES" check (`exec_handler.c`) stays.
4. **Retry only if the walk found an app-owned component.** A real
   SELinux denial, or a bind the app does not own (`/dev`, `/sdcard`),
   returns the original EACCES with no second handler run. Do *not*
   require that something was actually widened: a concurrent rescuer
   may have widened the same directory already, and skipping the
   retry then turns the race into a spurious EACCES. EACCES is a cold
   path; one extra handler run is cheap.
5. **Restore, do not widen permanently.** The guest keeps seeing the
   mode it set: `pacman -Qkk`, sudo's 0440 sudoers check, ssh
   StrictModes all stay clean. A crash between widen and restore
   leaves the wider mode; harmless.
6. **Gate on virtual euid 0.** A guest that dropped privileges gets
   the real EACCES, matching the existing fchmodat/fchownat gating.
7. **Coverage** follows from the wrapper: every handler that goes
   through the translator, including the AF_UNIX `bind`/`connect`
   paths in `syscalls_socket.c`, path xattrs, execve and execveat.
   `faccessat` needs no special casing: the re-run happens while
   widened, so R_OK/W_OK answer yes, and X_OK stays honest because
   files never gain x. execve matters more than it looks: the loader
   must open the binary O_RDONLY, so a `--x--x--x` binary (a 4111
   sudo) fails under tawcroot today though real root can exec it.

Re-running a handler that hit EACCES partway: check while implementing
that the link-store mutations (`linkstore.c` intent journal) are safe
to re-enter after a mid-operation EACCES, i.e. the failed attempt
rolled back or left a replayable intent. If one is not, exclude that
handler from the re-run and rescue inside it instead.

Known limits, write them into the notes:

- The symlink walker propagates EACCES from `readlinkat`
  (`path_resolve.c:177`), so a directory with no owner *search* bit
  fails inside translation, before a complete route exists. If
  recording the partial route makes the wrapper rescue it for free,
  good; otherwise leave it. Directories with x but no w (the `/` and cadir cases) are the
  only ones seen in practice.
- Renaming a *directory* across parents needs `w` on the directory
  itself (`..` update). Rename does not widen leaves, so a u-w
  directory still fails to move. Not seen in practice; fixing it means
  restoring at the destination route.
- Races, same as proot's: two processes rescuing the same directory
  can still give one a spurious EACCES (the first restores before the
  second's re-run), a restore can clobber a concurrent guest chmod of
  the same inode, and other threads briefly see the wide mode. All
  acceptable; the rootfs is one security principal
  (notes/tawcroot/overview.md).
- Root's X_OK/exec on a file whose only x bits are group/other stays
  EACCES (owner bits decide for the app uid).

Alternative considered and rejected: clamp modes at chmod/mkdir/open
time so the tree never holds a restrictive owner mode. Changes what
the guest sees, does not fix modes already on disk, does not cover
the faithful 0500 cadir, and diverges from proot's semantics.

## Tests

- Hosted tests (`tawcroot/tests/hosted/test_fs_handlers.c`) run as
  the dev user, so DAC is real: create a 0555 dir under the rootfs,
  `mkdirat` inside it succeeds, its mode is still 0555 afterwards,
  `faccessat(W_OK)` returns 0, and a 0444 leaf opens O_WRONLY. Go
  through the rescue wrapper, not the bare handlers. Symlink safety:
  `unlinkat` of a symlink in a 0555 dir whose target is a 0400 file
  outside the dir succeeds and leaves the target's mode untouched;
  same for an emulated-hardlink name. Unlinking a 0444 file in a 0555
  dir leaves the dir 0555. Add a
  dropped-identity variant that still gets EACCES, and a not-owned
  case (a dir owned by another uid is not creatable on the host, so
  cover it with a bind to a non-app path where chmod fails and
  assert the original EACCES with no retry).
- `rootfs_smoke.c`: `chmod 555 /; mkdir /x; ls -ld /` shows 555, and
  a `chmod 111` copy of a binary execs. Skip
  under real uid 0 (rooted emulator), following the pattern in
  notes/tawcroot/testing.md "Device-environment sensitivities".
- Integration: a tawcroot session on the standing target running
  `chmod 555 / && mkdir /probe && rmdir /probe && chmod 700 /`.

## Docs and cleanup

- **Rewrite** `notes/tawcroot/overview.md` "not a full
  userland-namespace replacement": drop the "ALARM ships `/` as
  0555" claim (added in a1a63fe with no evidence; on-device ALARM `/`
  is 0700) and the "don't go looking for a missing handler" sentence.
  State that DAC override is emulated lazily and name the
  no-search-bit limit.
- **Add** the contract to the chmod/chown list in
  `notes/tawcroot/path-translation.md` (§"Translation rules", next to
  the chmod entry), and fold the existing `mknodat`/`fchmodat`
  swallow comments into the same story.
- **Keep** `ProotArchiveExtractor`'s mode deferral. It runs in the
  JVM where DAC is real, and it is the same delayed-directory-
  permissions logic GNU tar uses. Its doc comment still says "for the
  proot install method"; it is used by tawcroot installs too, fix the
  framing while there.
- **Keep** `RootfsCleaner`'s `chmod -R u+rwX`. The wipe runs as the
  app uid through toybox find, outside tawcroot, and every ALARM
  install faithfully contains a 0500 cadir.
- Reply on upstream #12 once released: fixed in tawcroot; the
  interim workaround is `chmod 755 /` inside the guest.
