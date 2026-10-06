package me.phie.tawc.dev

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.phie.tawc.HomePane
import me.phie.tawc.MainActivity
import me.phie.tawc.OpenDistro
import me.phie.tawc.Settings
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.terminal.TerminalSessions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Home screen state for tests, without screenshots
 * (notes/exec-broker.md):
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `home-pane` | `pane` ∈ get\|terminal\|apps, optional `installId` | set [Settings.homePane] (and the open distro) and re-render a live [MainActivity]; `get` prints the setting |
 * | `terminal-state` | optional `installId` (default: the open distro) | prints `pending`, `inUse:<n>` or `none` |
 */
internal object HomeActions {
    fun registerAll() {
        ActionRegistry.register("home-pane", HomePaneAction)
        ActionRegistry.register("terminal-state", TerminalStateAction)
    }

    /**
     * Put the home screen on [pane] without the IME. Main thread.
     * Without a live [MainActivity] only the setting changes; the next
     * one to open reads it.
     */
    fun showPane(pane: HomePane, installId: String?) {
        val main = DevActivityTracker.liveActivities()
            .filterIsInstance<MainActivity>()
            .lastOrNull { !it.isFinishing && !it.isDestroyed }
        if (main != null) {
            main.showPaneForDev(pane, installId)
        } else {
            if (installId != null) OpenDistro.set(installId)
            Settings.homePane = pane
        }
    }

    private object HomePaneAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val raw = args["pane"] ?: "get"
            if (raw == "get") {
                ctx.out(Settings.homePane.key)
                return 0
            }
            val pane = HomePane.entries.firstOrNull { it.key == raw } ?: run {
                ctx.err("home-pane: --arg pane=get|terminal|apps (got '$raw')")
                return 2
            }
            val installId = args["installId"]?.takeIf { it.isNotBlank() }
            if (installId != null && !Installation.isValidId(installId)) {
                ctx.err("home-pane: invalid installId '$installId'")
                return 2
            }
            // `am start` returns before onCreate; give a just-started
            // MainActivity a moment to register.
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < deadline &&
                DevActivityTracker.liveActivities().none { it is MainActivity }
            ) {
                Thread.sleep(50)
            }
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { showPane(pane, installId) } finally { latch.countDown() }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                ctx.err("home-pane: main loop did not run within 5s")
                return 1
            }
            return 0
        }
    }

    private object TerminalStateAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val id = args["installId"]?.takeIf { it.isNotBlank() }
                ?: OpenDistro.resolve(InstallationStore(ctx.appContext))?.id
            if (id == null) {
                ctx.out("none")
                return 0
            }
            val inUse = TerminalSessions.list(id).size
            ctx.out(
                when {
                    inUse > 0 -> "inUse:$inUse"
                    TerminalSessions.pending(id)?.let { it.isRunning && it.pid > 0 } == true -> "pending"
                    else -> "none"
                },
            )
            return 0
        }
    }
}
