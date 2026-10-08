package me.phie.tawc.install

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Distros that must not start guest processes right now — today only
 * while [DistroExporter] snapshots one. Process-wide and in-memory: if
 * the app dies mid-export the mark goes with it, which is correct
 * because export never changes the distro.
 *
 * Enforced in one spot, [TawcrootMethod]'s per-spawn prepare step,
 * which every tawcroot spawn surface (broker RUNINSIDE, terminal tabs,
 * launcher, remote access, Run command, install steps) goes through.
 * Export is tawcroot-only, so no other method needs the check.
 */
object DistroBusy {
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val bypass = ThreadLocal<Boolean>()

    /** Mark [id] busy. False if it already was. */
    fun mark(id: String): Boolean = busy.add(id)

    fun clear(id: String) {
        busy.remove(id)
    }

    fun isBusy(id: String): Boolean = id in busy

    /** Throw if [id] is busy, unless this thread is inside [bypassing]. */
    fun check(id: String) {
        if (id in busy && bypass.get() != true) {
            throw IOException("distro '$id' is being exported; try again when the export finishes")
        }
    }

    /** Run [block] with the busy check disabled on this thread — the
     *  exporter's own link-store recovery spawn. */
    fun <T> bypassing(block: () -> T): T {
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.remove()
        }
    }
}
