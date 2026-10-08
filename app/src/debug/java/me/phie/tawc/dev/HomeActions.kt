package me.phie.tawc.dev

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.phie.tawc.MainActivity
import me.phie.tawc.OpenDistro
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.terminal.TerminalSessions
import me.phie.tawc.terminal.TerminalTabBar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Home screen state for tests, without screenshots
 * (notes/exec-broker.md):
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `home-tab` | `tab` ∈ apps\|new\|<n>, optional `installId` | show that install's (or the open distro's) home on the apps tab, a new terminal tab, or terminal tab n of a live [MainActivity] |
 * | `terminal-state` | optional `installId` (default: the open distro) | prints `tabs:<n> selected:<apps\|i\|none>` |
 */
internal object HomeActions {
    fun registerAll() {
        ActionRegistry.register("home-tab", HomeTabAction)
        ActionRegistry.register("terminal-state", TerminalStateAction)
    }

    private fun liveMain(): MainActivity? =
        DevActivityTracker.liveActivities()
            .filterIsInstance<MainActivity>()
            .lastOrNull { !it.isFinishing && !it.isDestroyed }

    /**
     * Put a live [MainActivity] on [tab] ([TerminalTabBar.APPS], an
     * index or [MainActivity.DEV_NEW_TAB]). Main thread. Without one,
     * only the open distro changes; returns whether the tab exists
     * (the apps tab always does).
     */
    fun showTab(tab: Int, installId: String?): Boolean {
        val main = liveMain()
        if (main == null) {
            if (installId != null) OpenDistro.set(installId)
            return tab == TerminalTabBar.APPS
        }
        return main.showTabForDev(tab, installId)
    }

    private object HomeTabAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val raw = args["tab"] ?: ""
            val tab = when (raw) {
                "apps" -> TerminalTabBar.APPS
                "new" -> MainActivity.DEV_NEW_TAB
                else -> raw.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                    ctx.err("home-tab: --arg tab=apps|new|<n> (got '$raw')")
                    return 2
                }
            }
            val installId = args["installId"]?.takeIf { it.isNotBlank() }
            if (installId != null && !Installation.isValidId(installId)) {
                ctx.err("home-tab: invalid installId '$installId'")
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
            var ok = false
            Handler(Looper.getMainLooper()).post {
                try { ok = showTab(tab, installId) } finally { latch.countDown() }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                ctx.err("home-tab: main loop did not run within 5s")
                return 1
            }
            if (!ok) {
                ctx.err("home-tab: no tab '$raw'")
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
                ctx.out("tabs:0 selected:none")
                return 0
            }
            val latch = CountDownLatch(1)
            var selected: Int? = null
            Handler(Looper.getMainLooper()).post {
                try { selected = liveMain()?.selectedTabForDev(id) } finally { latch.countDown() }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                ctx.err("terminal-state: main loop did not run within 5s")
                return 1
            }
            val sel = when (val s = selected) {
                null -> "none"
                TerminalTabBar.APPS -> "apps"
                else -> s.toString()
            }
            ctx.out("tabs:${TerminalSessions.list(id).size} selected:$sel")
            return 0
        }
    }
}
