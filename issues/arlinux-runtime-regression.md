# ARLinux runtime regression

Redmi 29854870 and x300 10AFA31610002QH, stock Debian trixie libc,
Android app UID/seccomp.

- System V semaphore syscalls are unavailable on Android. semget(IPC_PRIVATE)
  fails; compatibility must live in the runtime, not a replacement libc.
- System V shared memory is also missing; the runtime must support both raw
  syscalls and stock libc callers without LD_PRELOAD.
- Namespace startup compatibility passes the clone/PID/map/capability/exec
  stage, but CLONE_FS shared filesystem-view regression still fails.
  Repeated creation now releases the old reserved map descriptors; 80
  replacements pass on both devices. Map semantics and concurrency still
  need review. This model does not provide kernel namespace isolation.
- Full acceptance remains pending. Both devices run OpenCode with accessible
  input content, Chromium WebGL without extra sandbox/GPU arguments, terminal,
  tmux, Git/GCC, HTTPS, FeatherPad, LibreOffice, Blender and WPS workflows.
  X11/GLX, Wayland/EGL and native AHB scene checks pass with Turnip on Redmi
  and libhybris/Mali on x300. These successes do not waive the IPC failures.
- Virtual-root identity regression reaches the security.capability write
  and exits 13: the old contract expects fake success. Ordinary-user identity
  and package backup pass. Resolve the capability policy explicitly rather
  than dropping this assertion to hide a changed contract.
- x300 OpenCode's GPU subprocess has also logged a bionic FORTIFY vsprintf
  overflow (1029 bytes into 1023), then exited with SIGABRT. AT-SPI startup
  alone does not validate its rendering. Chromium WebGL and Blender hardware
  checks pass, but this separate failure needs a backtrace and root-cause fix.
- Hosted IBus X11 focus timed out once in the combined x300 application run;
  an isolated repeat passed. Keep this visible until cold-start focus is
  consistently verified. The Android host now isolates guest XDG directories
  from compositor process environment, and the common session activates IBus.

Latest device evidence (2026-09-25): `build/tawc-x300-full` completes the full
suite with seven runtime failures: namespace-contract, libc-ownership,
identity-virtual-root, sem-undo, shm-api-consistency, shm-lifetime and
sem-api-consistency. All application workflows, both hosted input transport
tests, GLX, Wayland EGL and AHB scene tests pass in that run. This does not
erase the earlier intermittent focus timeout or validate OpenCode's GPU.
Cold starts on both devices now activate HostedInput before OpenCode without
test-driven activation. x300's GPU crash repeats after cold start (1031 bytes
into 1023); Crashpad contains no module table. A frame-pointer trace reaches
glibc abort from unmapped-in-the-dump driver/bridge frames and OpenCode.
Collect matching GPU-process mappings before attributing the overflow to a
specific library. Do not disable FORTIFY.

The explicit /dev/shm directory bind now takes precedence over the private
memfd name table. ARLinux's named semaphore/shared-memory regression passes.

Normal-user identity, AT-SPI session bus authentication, exec, fchmodat2 and
close_range sparse-FD/EMFILE/CLOEXEC contracts pass on Redmi. epoll_pwait2 is
absent on its older kernel; the test explicitly checks epoll_pwait instead.

OpenCode's later SIGSYS death was traced to write() inside its SIGCHLD
handler with SIGSYS masked by sa_mask. Strip the reserved trap signal from
installed handler masks, not only from rt_sigprocmask. The new signal-handler
device regression and exec/spawn regression pass without diagnostic tracing.

All 2224 host tests pass with GCC 14/GNU ld and a shell as PID-namespace init.
This does not cover the remaining ARM64 device-only namespace/IPC gaps.
