# SHM surfaces render black on x86_64 emulator (translator shader bug)

`scripts/run-integration-tests.sh rendering::` fails on
`.tawctarget=emulator`: SHM surfaces draw as opaque black (only the
shader-generated magenta tint band shows). AHB/external surfaces are
unaffected. Replaces the old `render-pattern-emulator-black.md`.

## Root cause (2026-07-06, fully diagnosed)

Emulator driver bug, not a TAWC bug. The guest GL driver ("Android
Emulator OpenGL ES Translator") scans shader source textually without
honouring the preprocessor. Smithay's texture fragment shader (and
TAWC's tint/plain shaders, same template) declare the sampler as:

    #if defined(EXTERNAL)
    uniform samplerExternalOES tex;
    #else
    uniform sampler2D tex;
    #endif

For non-EXTERNAL variants the translator still sees the
`samplerExternalOES tex` text and treats `tex` as an external sampler,
so `texture2D(tex, ...)` samples the unbound external target →
constant (0,0,0,1). No GL error anywhere; upload, FBO readback, and
texture completeness are all fine. Verified by in-process shader-source
bisection: deleting just the dead declaration line fixes sampling;
deleting only the `#extension` line does not.

Beware: a minimal standalone NDK binary running the identical GL call
sequence (same shaders, BGRA upload, cross-surface draw, fences) does
NOT reproduce — only the app process does. Trigger condition unknown;
don't trust standalone probes to clear the translator.

See notes/rendering.md "Emulator shader translator quirk" for the
durable write-up.

## Verified fix (not applied — emulator bug, low priority)

Resolve `#if defined(X)`/`#else`/`#endif` against each variant's define
list before the source reaches the driver, in the smithay fork's
`texture_program` (`src/backend/renderer/gles/shaders/mod.rs`), so
disabled branches are never seen. One helper covers smithay's stock
shader and TAWC's custom shaders (both compile through
`compile_custom_texture_shader` → `texture_program`); semantically a
no-op on correct drivers. Implemented and verified 2026-07-06: all
`rendering::` and `xwayland::` tests pass on the emulator with it
(including `test_xwayland_xclock_renders_via_shm`), then reverted by
choice. Sketch:

    fn strip_disabled_branches(src: &str, defines: &[&str]) -> String {
        let mut out = String::new();
        let mut stack: Vec<bool> = Vec::new(); // enabled? per nested #if
        for line in src.lines() {
            let t = line.trim();
            if let Some(name) = t.strip_prefix("#if defined(")
                .and_then(|r| r.strip_suffix(')')) {
                stack.push(defines.contains(&name));
                continue;
            }
            match t {
                "#else" if !stack.is_empty() => {
                    let top = stack.last_mut().unwrap(); *top = !*top; continue;
                }
                "#endif" if !stack.is_empty() => { stack.pop(); continue; }
                _ => {}
            }
            if stack.iter().all(|e| *e) { out.push_str(line); out.push('\n'); }
        }
        out
    }

applied to both the normal and debug variant sources in
`create_variant` (debug's define list additionally contains
`DEBUG_FLAGS`). Applying it means a smithay fork commit on
`tawc-patches` plus a `deps/deps.list` pin bump.

## SELinux ruled out (resolved 2026-07-06)

A parallel session had diagnosed the same black-SHM symptom as SELinux
enforcing (claiming `su 0 setenforce 0` alone made the pattern render,
matching the old SHM/SELinux prose in notes/emulator.md). A full matrix
on `tawc-rootless`/emulator-5554, tawcroot, cpu backend, current HEAD,
app force-stopped between cells, settled it:

| SELinux    | shader fix | render-pattern |
| ---------- | ---------- | -------------- |
| Enforcing  | no         | black          |
| Permissive | no         | black          |
| Enforcing  | yes        | renders (both `rendering::` pixel tests pass) |

So the translator shader bug is the sole cause here; SELinux state is
irrelevant to the tawcroot SHM path (client and compositor share the
untrusted_app domain — the memfd `type_transition` story in
notes/emulator.md is chroot-only). The parallel session's
"permissive fixed it" observation did not reproduce and its origin is
unknown (possibly a different emulator boot/GPU config or a sampling
error). Useful side-findings from that session — `emulator.sh`'s
`su -c` setenforce syntax fails on AOSP su, and the rootless
google_apis image does have root — live in
`uninstall-wipe-su-flavor-emulator.md`.

## Known-failing tests on emulator until fixed

- `rendering::test_shm_render_pattern_orientation_pixels`
- `rendering::test_shm_xdg_popup_position_pixels`
- `xwayland::test_xwayland_xclock_renders_via_shm` (xclock is SHM)

Unrelated: xwayland tests can also fail wholesale if a stale
`share/xtmp/.X0-lock` survives a force-kill (Xwayland then never gets a
display); delete the lock + `.X11-unix/X0` socket to recover.
