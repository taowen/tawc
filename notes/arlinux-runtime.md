# ARLinux integration

ARLinux builds only tawcroot, using its Android NDK host toolchain. The
compositor, instance UI and session supervisor are independent of TAWC.

The host binds a real app-private directory at `/dev/shm`. This bind takes
precedence over tawcroot's private memfd namespace, so POSIX sem_open's
temporary-file/linkat sequence and independent guest launches share one
namespace. No Android root permission is required.

Bind storage must preserve guest symlink semantics. The production readlink
oracle selects the bind backing each prefix, while the resolver retains guest
absolute paths. `/proc` magic links keep their separate kernel handling.
This fixes apt's temporary symlinks into `/var/cache` and relative execveat
through symlinks into `/usr`. execveat also checks AT_SYMLINK_NOFOLLOW before
committing the replacement process.

Verified on ARLinux's Redmi test device with Debian trixie's original libc:
OpenCode package installation; public/raw exec and concurrent spawn; absolute
and relative symlinks; named POSIX semaphores and shared memory. These are
runtime checks, not a complete desktop compatibility claim. Outstanding
failures are tracked in `issues/arlinux-runtime-regression.md`.

ARLinux starts desktop processes with `--host-user`, matching the Android
app's UID/GID for D-Bus peer authentication. Package setup retains virtual
root. Set-id ELF execution changes only the virtual identity; Android
credentials and SELinux never change. This runtime is not a security sandbox.

The latest clean Redmi run passes 19 checks, including OpenCode's accessible
input content, and explicitly skips the pidfd-dependent fork case on its older
kernel. Separate X11/GLX and Wayland/EGL hardware present/resize checks pass
on Turnip/Zink. No diagnostic tracing or injected sandbox argument is used.

System V IPC remains unsupported. Namespace startup metadata is partially
modeled; shared filesystem-view behavior is unfinished. Do not treat these
results as complete desktop acceptance or kernel namespace isolation.
