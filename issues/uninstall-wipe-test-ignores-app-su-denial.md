# uninstall_wipe tests fail when Magisk denies the app

`test_wipe_gate_and_su_retry` needs the *app* uid to get root (the
RootfsCleaner su retry), but `require_root()` only checks host-side
`su`. On a phone where Magisk's policy for me.phie.tawc is deny
(`magisk --sqlite "SELECT * FROM policies"` → `policy=1`), the su-retry
leg fails with `find: .../rootfs/rootdir: Permission denied`, leaves the
`wipetest` slot behind, and `test_wipe_removes_ando_broker_dir` then
fails its pre-clean.

Fix: have `require_root()` (or the runner's ignore gate) also probe the
app uid's su grant, and skip rather than fail when it's denied.
