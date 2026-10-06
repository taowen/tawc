package me.phie.tawc.terminal

import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import me.phie.tawc.session.Hold
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds

/**
 * Process-wide registry of live terminal sessions: per installation id,
 * an ordered tab list plus the selected index, and at most one
 * *pending* shell. Sessions outlive the home screen's [TerminalPane] —
 * activity recreation and distro switches reattach to the running
 * shells instead of spawning new ones. Selection lives here too so
 * recreation restores which tab was showing.
 *
 * Every tab-list session holds a [Reason.Terminal] in [SessionHolds],
 * which keeps the process a foreground service while any shell is alive
 * (notes/session-service.md). The pending shell — the one the pane
 * auto-spawns and nobody has typed into yet — holds nothing until
 * [promote] moves it into the list; [SessionService]'s stray scan skips
 * its pid ([pendingPids]).
 *
 * Dumb bookkeeping only (order + selection): tab policy — what to
 * select after a close, when to close the app — lives in [TerminalPane].
 */
internal object TerminalSessions {
    private class Entry {
        val sessions = ArrayList<TerminalSession>()
        var selected = 0
    }

    /** Held here, not by the activity: sessions outlive it. */
    private val holds = java.util.IdentityHashMap<TerminalSession, Hold>()

    private val entries = HashMap<String, Entry>()

    private val pending = HashMap<String, TerminalSession>()

    /** Live sessions for [id] in tab order (snapshot copy). */
    @Synchronized
    fun list(id: String): List<TerminalSession> =
        entries[id]?.sessions?.toList() ?: emptyList()

    /** Append [session] as the last tab for [id]. */
    @Synchronized
    fun add(id: String, session: TerminalSession) {
        entries.getOrPut(id) { Entry() }.sessions.add(session)
        holds[session] = SessionHolds.acquire(Reason.Terminal(id))
    }

    /** Every live session, all installs, pending ones included. */
    @Synchronized
    fun all(): List<TerminalSession> = entries.values.flatMap { it.sessions } + pending.values

    /** [id]'s pending shell, if any. */
    @Synchronized
    fun pending(id: String): TerminalSession? = pending[id]

    /** Park [session] as [id]'s pending shell (no hold). Kills a
     *  previous pending shell rather than leaking it. */
    @Synchronized
    fun setPending(id: String, session: TerminalSession) {
        val old = pending.put(id, session)
        if (old != null && old !== session) old.kill()
    }

    /**
     * First input reached [id]'s pending shell: append it as the last
     * tab and take its hold. Returns it, or null when there was none.
     */
    @Synchronized
    fun promote(id: String): TerminalSession? {
        val session = pending.remove(id) ?: return null
        add(id, session)
        return session
    }

    /**
     * The reverse of [promote]: [session], [id]'s only tab, is idle
     * again (ShellIdle) — make it the pending shell and drop its hold.
     * False (no change) when it isn't the only tab.
     */
    @Synchronized
    fun demote(id: String, session: TerminalSession): Boolean {
        val tabs = entries[id]?.sessions ?: return false
        if (tabs.size != 1 || tabs[0] !== session) return false
        remove(id, session)
        setPending(id, session)
        return true
    }

    /** Drop [id]'s pending shell and kill it. */
    @Synchronized
    fun killPending(id: String) {
        pending.remove(id)?.kill()
    }

    /** Forget [session] if it is [id]'s pending shell (it exited). */
    @Synchronized
    fun clearPending(id: String, session: TerminalSession) {
        if (pending[id] === session) pending.remove(id)
    }

    /** Pids of every live pending shell; 0 (not yet started) skipped. */
    @Synchronized
    fun pendingPids(): Set<Int> = pending.values.map { it.pid }.filter { it > 0 }.toSet()

    /**
     * Drop [session] from [id]'s list if present, keeping the selection
     * pointing at the same session when possible and clamped in range
     * otherwise (closing the selected tab lands on its next neighbor,
     * or the previous one if it was last).
     */
    @Synchronized
    fun remove(id: String, session: TerminalSession) {
        val entry = entries[id] ?: return
        val index = entry.sessions.indexOfFirst { it === session }
        if (index < 0) return
        entry.sessions.removeAt(index)
        holds.remove(session)?.release()
        if (entry.sessions.isEmpty()) {
            entries.remove(id)
            return
        }
        if (index < entry.selected) entry.selected--
        entry.selected = entry.selected.coerceIn(0, entry.sessions.size - 1)
    }

    /** Drop every session for [id], returning them (in tab order). */
    @Synchronized
    fun removeAll(id: String): List<TerminalSession> {
        val sessions = entries.remove(id)?.sessions ?: return emptyList()
        for (s in sessions) holds.remove(s)?.release()
        return sessions
    }

    /**
     * Close every terminal window (the home task was swiped away):
     * SIGHUP each shell, as a desktop terminal closing its pty does. The
     * shell passes the hangup on to its jobs, so only `nohup`/`disown`/
     * `setsid` children survive. Shells still up after [HANGUP_GRACE_MS]
     * (trapped HUP) are killed. Tabs close through the normal exit path.
     */
    fun hangUpAll() = hangUp(all())

    /** [hangUpAll] for just [sessions]. */
    fun hangUp(sessions: List<TerminalSession>) {
        for (s in sessions) s.hangUp()
        Handler(Looper.getMainLooper()).postDelayed({ for (s in sessions) s.kill() }, HANGUP_GRACE_MS)
    }

    /**
     * Set just before the app removes its own home task (last shell
     * exited), so [SessionService.onTaskRemoved] doesn't take that for a
     * swipe and hang up other distros' shells. Main thread only.
     */
    var selfRemoving = false

    private const val HANGUP_GRACE_MS = 3000L

    @Synchronized
    fun selected(id: String): Int = entries[id]?.selected ?: 0

    @Synchronized
    fun setSelected(id: String, index: Int) {
        val entry = entries[id] ?: return
        entry.selected = index.coerceIn(0, entry.sessions.size - 1)
    }
}

/**
 * [TerminalSession.finishIfRunning] without its trap: a session no view
 * has sized yet has pid 0 but counts as running, and `kill(0, SIGKILL)`
 * would take down the app's own process group. Such a session has no
 * process to kill.
 */
internal fun TerminalSession.kill() {
    if (pid > 0) finishIfRunning()
}

private fun TerminalSession.hangUp() {
    if (pid <= 0 || !isRunning) return
    try {
        Os.kill(pid, OsConstants.SIGHUP)
    } catch (_: ErrnoException) {
        // Already gone.
    }
}

/**
 * Client swapped in when a [TerminalPane] detaches so retained sessions
 * don't keep the destroyed pane (and its view tree) reachable until the
 * next reattach. The pty reader threads keep draining output into the
 * transcript regardless of client. If a shell exits while detached,
 * drop its registry entry (tab or pending slot) here — the pane's own
 * [TerminalPane.onSessionFinished] is gone.
 */
internal class DetachedTerminalClient(private val id: String) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalSessions.remove(id, finishedSession)
        TerminalSessions.clearPending(id, finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    private companion object {
        const val TAG = "tawc-terminal"
    }
}
