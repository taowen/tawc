package me.phie.tawc.terminal

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import me.phie.tawc.session.Hold
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds

/**
 * Process-wide registry of live terminal sessions: per installation id,
 * an ordered tab list plus the selected index. Sessions outlive
 * [TerminalActivity] — recreation (uncaught config changes, system
 * pressure) and re-opening from the home screen reattach to the running
 * shells instead of spawning new ones. (Back merely backgrounds the
 * task, keeping the activity itself alive.) Selection lives here too so
 * recreation restores which tab was showing. Every registered session
 * holds a [Reason.Terminal] in [SessionHolds], which keeps the process a
 * foreground service while any shell is alive (notes/session-service.md).
 *
 * Dumb bookkeeping only (order + selection): tab policy — what to
 * select after a close, when to finish the activity — lives in
 * [TerminalActivity].
 */
internal object TerminalSessions {
    private class Entry {
        val sessions = ArrayList<TerminalSession>()
        var selected = 0
    }

    /** Held here, not by the activity: sessions outlive it. */
    private val holds = java.util.IdentityHashMap<TerminalSession, Hold>()

    private val entries = HashMap<String, Entry>()

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

    /** Every live session, all installs. */
    @Synchronized
    fun all(): List<TerminalSession> = entries.values.flatMap { it.sessions }

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

    @Synchronized
    fun selected(id: String): Int = entries[id]?.selected ?: 0

    @Synchronized
    fun setSelected(id: String, index: Int) {
        val entry = entries[id] ?: return
        entry.selected = index.coerceIn(0, entry.sessions.size - 1)
    }
}

/**
 * Client swapped in by [TerminalActivity.onDestroy] so retained
 * sessions don't keep the destroyed activity (and its view tree)
 * reachable until the next reattach. The pty reader threads keep
 * draining output into the transcript regardless of client. If a
 * shell exits while detached, drop its registry entry here — the
 * activity's own [TerminalActivity.onSessionFinished] is gone.
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
