# A failed install can be left undeletable from the UI

Reported in https://github.com/wmww/tawc/issues/13: after the Configure
step failed (the `/data/local` heredoc bug, since fixed by `Sh`
setting `TMPDIR`), the distro stayed in `failed` state and
"Delete" reported `rootfs delete failed`. Force-stop, reboot and
reinstalling the app did not help; only clearing app data did, which
takes every other distro with it.

Not the same root cause as the Configure failure: `RootfsCleaner`'s
delete scripts use no heredocs, so giving the host shell a writable
`TMPDIR` will not fix this. The likely suspect is a half-extracted
rootfs the app-uid `find -delete` can't walk, with the
`chmod -R u+rwX` pre-pass not recovering it — but that pass looks like
it should cover mode-0500 dirs, so this needs an actual reproduction
before guessing further.

Not reproduced locally. Reproduce by interrupting a bootstrap extract
partway, then deleting from the UI.
