package me.phie.tawc.dev

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionExit
import me.phie.tawc.session.SessionHolds
import me.phie.tawc.session.SessionWake
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Session-service test surfaces; see notes/session-service.md. */
internal object SessionActions {
    fun registerAll() {
        ActionRegistry.register("session-state", StateAction)
        ActionRegistry.register("session-exit", ExitAction)
        ActionRegistry.register("session-wake", WakeAction)
        ActionRegistry.register("compositor-hold", CompositorHoldAction)
    }

    /**
     * `compositor-hold --arg hold=on|off` — pin the compositor running
     * with no clients (starting it if stopped), for tests that poke an
     * empty compositor. Off lets the idle rule stop it again.
     */
    private object CompositorHoldAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val hold = when (args["hold"]) {
                "on" -> true
                "off" -> false
                else -> {
                    ctx.err("compositor-hold: --arg hold=on|off required")
                    return 2
                }
            }
            NativeBridge.nativeSetCompositorHold(hold)
            if (!hold) return 0
            CompositorService.ensureRunning(ctx.appContext)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (NativeBridge.nativeIsCompositorRunning() && NativeBridge.nativeQueryState() != null) return 0
                Thread.sleep(20)
            }
            ctx.err("compositor-hold: compositor did not start within 10s")
            return 1
        }
    }

    /** `session-state` — one line per held reason. */
    private object StateAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            for (r in SessionHolds.reasons.value) {
                ctx.out(
                    when (r) {
                        is Reason.Terminal -> "terminal ${r.distroId}"
                        is Reason.Command -> "command ${r.label}"
                        is Reason.Compositor -> "compositor ${r.windowCount}"
                        is Reason.Stray -> "stray ${r.count}"
                        is Reason.Remote -> "remote ${r.distroId} ${r.clients}"
                    },
                )
            }
            return 0
        }
    }

    /**
     * `session-wake [--arg wake=on|off]` — set "Keep awake" like the
     * notification action, then print `held`, `released` or `unavailable`
     * (no service up).
     */
    private object WakeAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val want = when (args["wake"]) {
                null -> null
                "on" -> true
                "off" -> false
                else -> {
                    ctx.err("session-wake: --arg wake=on|off")
                    return 2
                }
            }
            val task = FutureTask {
                if (want != null) SessionWake.set(want)
                when {
                    !SessionWake.available.value -> "unavailable"
                    SessionWake.held.value -> "held"
                    else -> "released"
                }
            }
            Handler(Looper.getMainLooper()).post(task)
            ctx.out(task.get(5, TimeUnit.SECONDS))
            return 0
        }
    }

    /** `session-exit` — what the notification's Exit action does. */
    private object ExitAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            Handler(Looper.getMainLooper()).post { SessionExit.killEverything(ctx.appContext) }
            return 0
        }
    }
}
