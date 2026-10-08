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
 * an ordered tab list. Sessions outlive the home screen's [DistroHome]
 * — activity recreation and distro switches reattach to the running
 * shells instead of spawning new ones. Which tab is selected is the
 * activity's business, not stored here.
 *
 * Every session holds a [Reason.Terminal] in [SessionHolds], which
 * keeps the process a foreground service while any shell is alive
 * (notes/session-service.md).
 *
 * Dumb bookkeeping only: tab policy — what to select after a close,
 * when to close the app — lives in [DistroHome].
 */
internal object TerminalSessions {
    /** Held here, not by the activity: sessions outlive it. */
    private val holds = java.util.IdentityHashMap<TerminalSession, Hold>()

    private val entries = HashMap<String, ArrayList<TerminalSession>>()

    /** Live sessions for [id] in tab order (snapshot copy). */
    @Synchronized
    fun list(id: String): List<TerminalSession> = entries[id]?.toList() ?: emptyList()

    /** Append [session] as the last tab for [id]. */
    @Synchronized
    fun add(id: String, session: TerminalSession) {
        entries.getOrPut(id) { ArrayList() }.add(session)
        holds[session] = SessionHolds.acquire(Reason.Terminal(id))
    }

    /** Every live session, all installs. */
    @Synchronized
    fun all(): List<TerminalSession> = entries.values.flatten()

    /** Drop [session] from [id]'s list if present. */
    @Synchronized
    fun remove(id: String, session: TerminalSession) {
        val sessions = entries[id] ?: return
        if (!sessions.removeIf { it === session }) return
        holds.remove(session)?.release()
        if (sessions.isEmpty()) entries.remove(id)
    }

    /** Drop every session for [id], returning them (in tab order). */
    @Synchronized
    fun removeAll(id: String): List<TerminalSession> {
        val sessions = entries.remove(id) ?: return emptyList()
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
 * drop its tab here — the pane's own
 * [TerminalPane.onSessionFinished] is gone.
 */
internal class DetachedTerminalClient(private val id: String) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalSessions.remove(id, finishedSession)
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
