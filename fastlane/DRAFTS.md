# DRAFT store copy — not for publication

**Everything below was written by an agent and must be rewritten by the
maintainer before TAWC is submitted to F-Droid.** This is store copy in the
maintainer's own voice; the point of this file is to give you something to
react to, not something to ship.

The real files under `fastlane/metadata/android/en-US/` currently hold
`TODO(maintainer)` placeholders. Replace them, then delete this file.

See `plans/f-droid.md` step 2.2.

---

## `title.txt`

Currently set to `TAWC`, matching the app's `app_name` string, so the store
listing and the launcher agree. Change both together if you want something
longer (e.g. "Tess's Android Wayland Compositor").

## `short_description.txt` (max 80 characters)

> Run desktop Linux programs on Android, no root required

(55 characters.)

## `full_description.txt` (max 4000 characters)

> TAWC runs CLI and graphical Linux programs on Android without root.
> Graphical apps get hardware acceleration through the phone's own graphics
> stack.
>
> It installs a stock Linux distribution — Arch Linux ARM or Debian — into
> the app's private storage and runs programs inside it with tawcroot, a
> single-process alternative to PRoot. A Wayland compositor built on Smithay
> renders the graphical apps and wires them into Android: the system
> keyboard, the app switcher, and the home screen.
>
> Features:
>
> * A familiar terminal, using Termux's terminal widget (the Termux app
>   itself is not required)
> * Graphical apps launch from TAWC's menu, and can be pinned to the Android
>   home screen
> * Xwayland is included, so X11 apps run hardware-accelerated too
> * A built-in task manager for viewing and killing Linux processes
> * The `ando` command and storage binds let Linux programs reach Android
>   data when you want them to
>
> Limitations:
>
> * Linux apps are not meaningfully sandboxed beyond Android's own
>   mechanisms; tawcroot's single-process design can be escaped
> * Only the graphics APIs the phone provides (generally GLES and Vulkan);
>   no desktop GL
> * Not yet tested or optimised for games
> * Performance beats the alternatives, but is not native
> * arm64 only, Android 10 or newer

## `changelogs/1.txt` (max 500 characters)

> First release.
