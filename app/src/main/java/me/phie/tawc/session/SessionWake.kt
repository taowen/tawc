package me.phie.tawc.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Keep awake": whether [SessionService] holds its CPU/Wi-Fi locks.
 * Manual, off by default, and never outlives the service — a new
 * service lifetime starts released. See notes/session-service.md.
 *
 * Main thread only.
 */
object SessionWake {
    private val state = MutableStateFlow(false)
    private val up = MutableStateFlow(false)
    private var service: Any? = null

    /** Keep-awake requested (the service holds the locks while this is true). */
    val held: StateFlow<Boolean> = state.asStateFlow()

    /** A service is up, so the toggle means something. */
    val available: StateFlow<Boolean> = up.asStateFlow()

    fun set(on: Boolean) {
        state.value = on && up.value
    }

    internal fun serviceStarted(token: Any) {
        service = token
        state.value = false
        up.value = true
    }

    /** Only [token]'s own stop counts — a successor may already be up. */
    internal fun serviceStopped(token: Any) {
        if (service !== token) return
        service = null
        up.value = false
        state.value = false
    }
}
