package me.phie.tawc.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalSessions] tab-list and hold bookkeeping with real
 * [TerminalSession] objects — the constructor is pure field assignment
 * (no JNI until a view sizes the session), so this runs on the plain
 * JVM (`returnDefaultValues` covers the android.os.Handler field).
 * Unique distro ids per test isolate the process-wide singleton.
 */
class TerminalSessionsTest {

    private object StubClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = 0
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private fun session(): TerminalSession =
        TerminalSession("/bin/sh", "/", arrayOf("/bin/sh"), arrayOf(), 100, StubClient)

    private fun populated(id: String, count: Int): List<TerminalSession> =
        List(count) { session() }.onEach { TerminalSessions.add(id, it) }

    private fun terminalHolds(id: String): Int =
        SessionHolds.reasons.value.count { it == Reason.Terminal(id) }

    @Test
    fun addAppendsInOrderWithHolds() {
        val id = "add-order"
        val s = populated(id, 3)
        assertEquals(s, TerminalSessions.list(id))
        assertEquals(3, terminalHolds(id))
        assertTrue(TerminalSessions.all().containsAll(s))
        TerminalSessions.removeAll(id)
    }

    @Test
    fun removeDropsOnlyThatSessionAndItsHold() {
        val id = "remove-one"
        val s = populated(id, 3)
        TerminalSessions.remove(id, s[1])
        assertEquals(listOf(s[0], s[2]), TerminalSessions.list(id))
        assertEquals(2, terminalHolds(id))
        TerminalSessions.removeAll(id)
    }

    @Test
    fun removeUnknownSessionIsNoop() {
        val id = "remove-unknown"
        val s = populated(id, 2)
        TerminalSessions.remove(id, session())
        assertEquals(s, TerminalSessions.list(id))
        assertEquals(2, terminalHolds(id))
        TerminalSessions.removeAll(id)
    }

    @Test
    fun removeLastSessionEmptiesEntry() {
        val id = "remove-last"
        val s = populated(id, 1)
        TerminalSessions.remove(id, s[0])
        assertTrue(TerminalSessions.list(id).isEmpty())
        assertEquals(0, terminalHolds(id))
    }

    @Test
    fun removeAllReturnsInOrderAndClears() {
        val id = "remove-all"
        val s = populated(id, 3)
        assertEquals(s, TerminalSessions.removeAll(id))
        assertTrue(TerminalSessions.list(id).isEmpty())
        assertEquals(0, terminalHolds(id))
    }

    @Test
    fun detachedClientRemovesItsSession() {
        val id = "detached-remove"
        val s = populated(id, 2)
        DetachedTerminalClient(id).onSessionFinished(s[0])
        assertEquals(listOf(s[1]), TerminalSessions.list(id))
        TerminalSessions.removeAll(id)
    }
}
