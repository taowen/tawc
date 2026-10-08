# test_session_wake_follows_toggle_and_exit is flaky

`lazy_compositor::test_session_wake_follows_toggle_and_exit` failed at
`tests/lazy_compositor.rs:395` in a full-suite run on a Pixel 9 Pro and
in an isolated run on `main` (2026-10-07), but passed in isolation on
another build. Intermittent; cause not investigated.
