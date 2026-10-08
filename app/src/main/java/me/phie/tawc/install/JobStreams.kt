package me.phie.tawc.install

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands an in-process stream to an [InstallationService] export/import
 * job through its start intent, which can only carry a token. Used by
 * the debug broker actions, which stream archives over the broker
 * socket instead of a SAF document. Whoever [take]s a stream owns it.
 */
internal object JobStreams {
    private val streams = ConcurrentHashMap<String, Closeable>()

    fun put(stream: Closeable): String =
        UUID.randomUUID().toString().also { streams[it] = stream }

    fun take(token: String): Closeable? = streams.remove(token)
}
