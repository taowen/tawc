package me.phie.tawc

import android.content.Context
import android.util.Log
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.InstallationStore

/**
 * Drives the native ando broker listener set (notes/ando.md). ando is a
 * per-distro capability: there is one broker listener per ando-enabled
 * install, each on that install's own socket. [refresh] recomputes the
 * enabled set from disk (honoring the test-mode override) and hands it
 * to [NativeBridge.nativeSyncAndoBrokers], which starts missing
 * listeners and stops removed ones.
 *
 * Called from:
 *  - [TawcApplication]'s startup thread (initial reconcile);
 *  - [me.phie.tawc.install.Installer] after the initial metadata write
 *    (so ando is live during install/first boot) and after uninstall;
 *  - the ando toggle commit paths (install form, distro settings, and
 *    the `set-ando` test action).
 *
 * Touches [NativeBridge] (which lazily `System.loadLibrary`s the large
 * compositor `.so`) and does disk IO, so call it off the main thread.
 */
object AndoBrokers {
    private const val TAG = "tawc"

    /**
     * `@Synchronized`: the enabled set is computed from disk and then
     * handed to the native sync as one step. Without the lock two
     * concurrent callers (startup thread, Installer, a toggle commit)
     * could interleave compute-then-sync so an older snapshot lands
     * last and resurrects a listener the newer caller just stopped —
     * the native `sync` mutex only serializes the calls, not their
     * ordering.
     */
    @Synchronized
    fun refresh(context: Context) {
        val store = InstallationStore(context)
        val ids = ArrayList<String>()
        val paths = ArrayList<String>()
        for (inst in store.list()) {
            // Read enablement off the already-parsed record (plus the
            // test override) rather than re-loading metadata per id.
            if (!store.andoEnabled(inst)) {
                // The native sync only unlinks sockets it starts or
                // stops, so a node left by an unclean app shutdown
                // would sit forever once the distro is disabled — and
                // a still-bound guest session connecting to it would
                // get ECONNREFUSED ("is the TAWC app alive?") instead
                // of ENOENT (the ando-disabled instructions).
                store.andoSocket(inst.id).delete()
                continue
            }
            // Ensure the socket dir exists so the native bind can create
            // the node; the bind builders create it per-spawn too.
            store.andoDir(inst.id).mkdirs()
            ids.add(inst.id)
            paths.add(store.andoSocket(inst.id).absolutePath)
        }
        try {
            NativeBridge.nativeSyncAndoBrokers(ids.toTypedArray(), paths.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "ando broker sync failed", t)
        }
    }
}
