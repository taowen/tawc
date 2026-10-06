package me.phie.tawc.dev

import me.phie.tawc.OpenDistro
import me.phie.tawc.Settings
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.remote.RemoteSession
import java.io.IOException

/**
 * Remote access test surfaces (notes/remote-access.md):
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `remote-start` | optional `installId` (default: open distro), `idle` seconds (default 0 = until stopped), `relay` (default sshyeet.com), or `addrs` (`ip:port,…`: local-network mode on those), `keys` (authorized_keys text: key login) | start the agent like the screen's Start |
 * | `remote-status` | — | the agent's status JSON (secret included) |
 * | `remote-stop` | — | stop and wait |
 */
internal object RemoteActions {
    fun registerAll() {
        ActionRegistry.register("remote-start", StartAction)
        ActionRegistry.register("remote-status", StatusAction)
        ActionRegistry.register("remote-stop", StopAction)
    }

    private object StartAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val store = InstallationStore(ctx.appContext)
            val id = args["installId"]?.takeIf { it.isNotBlank() }
                ?: OpenDistro.resolve(store)?.id
                ?: run {
                    ctx.err("remote-start: no install")
                    return 2
                }
            if (!Installation.isValidId(id) || store.load(id)?.state != Installation.State.READY) {
                ctx.err("remote-start: '$id' is not a ready install")
                return 2
            }
            val idle = args["idle"]?.toLongOrNull() ?: 0L
            val transport = args["addrs"]?.let { a -> RemoteSession.Transport.Local(a.split(',').map { it.trim() }) }
                ?: RemoteSession.Transport.Relay(args["relay"] ?: Settings.DEFAULT_REMOTE_RELAY)
            val login = args["keys"]?.let { RemoteSession.Login.Keys(it, "test") } ?: RemoteSession.Login.Secret
            val request = try {
                RemoteSession.buildRequest(ctx.appContext, id, transport, login, idle)
            } catch (e: IOException) {
                ctx.err("remote-start: ${e.message}")
                return 1
            }
            RemoteSession.start(id, request)?.let {
                ctx.err("remote-start: $it")
                return 1
            }
            return 0
        }
    }

    private object StatusAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            ctx.out(me.phie.tawc.compositor.NativeBridge.nativeRemoteStatus())
            return 0
        }
    }

    private object StopAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            RemoteSession.stopNow()
            return 0
        }
    }
}
