package me.phie.tawc.remote

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.phie.tawc.BuildConfig
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.TawcrootMethod
import me.phie.tawc.session.Hold
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The native agent (`remote_jni.rs`); swapped out in JVM tests. */
interface RemoteNative {
    /** Null when started, else why not. */
    fun start(request: String): String?
    fun stop()
    fun status(): String
}

private object JniRemoteNative : RemoteNative {
    override fun start(request: String) = NativeBridge.nativeRemoteStart(request)
    override fun stop() = NativeBridge.nativeRemoteStop()
    override fun status() = NativeBridge.nativeRemoteStatus()
}

/** `tawc_remote::Status`, as far as the app uses it. */
data class RemoteStatus(
    val state: String,
    val error: String = "",
    val id: String = "",
    val jump: String = "",
    val notice: String = "",
    val secret: String = "",
    val secretDisabled: Boolean = false,
    val hostKey: String = "",
    val fingerprint: String = "",
    val command: String = "",
    /** Key login: where the keys came from, and how many. */
    val keySource: String = "",
    val keyCount: Int = 0,
    /** Logged-in connections. */
    val clients: Int = 0,
) {
    /** Agent thread alive (whatever the tunnel is doing). */
    val active: Boolean get() = state == "connecting" || state == "ready" || state == "reconnecting"

    companion object {
        val STOPPED = RemoteStatus("stopped")

        fun parse(json: String): RemoteStatus = try {
            val o = JSONObject(json)
            RemoteStatus(
                state = o.optString("state", "stopped"),
                error = o.optString("error"),
                id = o.optString("id"),
                jump = o.optString("jump"),
                notice = o.optString("notice"),
                secret = o.optString("secret"),
                secretDisabled = o.optBoolean("secret_disabled"),
                hostKey = o.optString("host_key"),
                fingerprint = o.optString("fingerprint"),
                command = o.optString("command"),
                keySource = o.optString("key_source"),
                keyCount = o.optInt("key_count"),
                clients = o.optInt("clients"),
            )
        } catch (_: Exception) {
            STOPPED
        }
    }
}

/** Everything the screen shows. [distroId] is the install the last start
 *  was for; it stays set after a stop so the final state stays visible. */
data class RemoteState(
    val distroId: String?,
    val status: RemoteStatus,
) {
    val running: Boolean get() = distroId != null && status.active
}

/**
 * Process-wide remote access state: one agent at a time, bound to one
 * install. Holds a [Reason.Remote] in [SessionHolds] while the agent runs
 * so the process stays a foreground service (Doze would cut the tunnel).
 * The agent does the idle close itself; this mirrors its status and
 * drops the hold once it ends. See notes/remote-access.md.
 *
 * Native calls run on one control thread; the agent's event callback
 * only queues work there, since [RemoteNative.stop] joins the agent's
 * thread.
 */
object RemoteSession {

    internal var native: RemoteNative = JniRemoteNative
    internal var executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tawc-remote-ctl").apply { isDaemon = true }
    }

    private val lock = Any()
    private var hold: Hold? = null
    private val mutableState = MutableStateFlow(RemoteState(null, RemoteStatus.STOPPED))
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    /**
     * Start for [distroId] with a prepared [request] (see
     * [buildRequest]). Blocking; not on the main thread. Null when
     * started, else why not.
     */
    fun start(distroId: String, request: String): String? {
        synchronized(lock) {
            if (mutableState.value.running || hold != null) return "already running"
        }
        native.start(request)?.let { return it }
        val status = RemoteStatus.parse(native.status())
        synchronized(lock) {
            hold = SessionHolds.acquire(Reason.Remote(distroId, 0))
            mutableState.value = RemoteState(distroId, status)
        }
        return null
    }

    /** Stop (async). Every login is hung up. */
    fun stop() = executor.execute { stopNow() }

    /** Stop and wait. Not on the main thread. */
    fun stopNow() {
        native.stop()
        refreshNow(reap = false)
    }

    /** Stop only if running for [distroId] (its uninstall). */
    fun stopFor(distroId: String) {
        if (mutableState.value.distroId == distroId) stop()
    }

    /** From the agent's thread (via [NativeBridge.onRemoteEvent]). Only
     *  status changes matter here; connection events aren't shown. */
    fun onNativeEvent(json: String) {
        val kind = try { JSONObject(json).optString("kind") } catch (_: Exception) { return }
        if (kind == "status") executor.execute { refreshNow() }
    }

    /** Pull the agent's status; follow it with the hold. Control thread. */
    private fun refreshNow(reap: Boolean = true) {
        val status = RemoteStatus.parse(native.status())
        val ended = synchronized(lock) {
            val distro = mutableState.value.distroId
            if (status.active && distro != null) {
                hold?.update(Reason.Remote(distro, status.clients))
            } else {
                hold?.release()
                hold = null
            }
            mutableState.value = mutableState.value.copy(status = status)
            !status.active
        }
        // Ended on its own (TTL, failure): reap the thread.
        if (ended && reap) native.stop()
    }

    /** How clients reach the device: a relay URL, or local-network
     *  `ip:port` listen addresses ([LocalNetwork]). */
    sealed interface Transport {
        data class Relay(val url: String) : Transport
        data class Local(val addrs: List<String>) : Transport
    }

    /** Who may log in: a fresh secret, or authorized_keys text. */
    sealed interface Login {
        data object Secret : Login
        data class Keys(val keys: String, val source: String) : Login
    }

    /** The JSON for `nativeRemoteStart`. Blocking (spawn prep touches the
     *  rootfs); throws IOException for a bad external bind like any spawn.
     *  [idleSeconds] 0 = until stopped. */
    fun buildRequest(
        context: Context,
        installId: String,
        transport: Transport,
        login: Login,
        idleSeconds: Long,
    ): String {
        val store = InstallationStore(context)
        val rootfs = store.rootfsDir(installId).absolutePath
        val env = TawcrootMethod(context).spawnEnvelope(rootfs)
        val t = when (transport) {
            is Transport.Relay -> JSONObject().put("kind", "relay").put("relay", transport.url)
            is Transport.Local -> JSONObject().put("kind", "local").put("addrs", JSONArray(transport.addrs))
        }
        val l = when (login) {
            Login.Secret -> JSONObject().put("kind", "secret")
            is Login.Keys -> JSONObject().put("kind", "keys").put("keys", login.keys).put("source", login.source)
        }
        return JSONObject()
            .put("transport", t)
            .put("login", l)
            .put("idle_timeout", idleSeconds)
            // One host key per device, kept across Starts (and uninstalls
            // with the app), so ssh's known_hosts can check it.
            .put("host_key", hostKeyFile(context).absolutePath)
            .put("agent", "tawc/${BuildConfig.VERSION_NAME} android/${arch()}")
            .put("version", BuildConfig.VERSION_NAME)
            .put(
                "envelope",
                JSONObject()
                    .put("argv", JSONArray(env.argv))
                    .put("shell", env.shell)
                    .put("host_env", JSONArray(env.hostEnv))
                    .put("cwd", env.cwd)
                    .put("rootfs", rootfs),
            )
            .toString()
    }

    fun hostKeyFile(context: Context): java.io.File = java.io.File(context.filesDir, "remote/host_key")

        private fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    /** Unit tests only. */
    internal fun resetForTest() = synchronized(lock) {
        hold?.release()
        hold = null
        mutableState.value = RemoteState(null, RemoteStatus.STOPPED)
    }
}
