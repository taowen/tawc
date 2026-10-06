package me.phie.tawc.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalSessions] list/selection bookkeeping with real
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

    @Test
    fun addAppendsInOrder() {
        val id = "add-order"
        val s = populated(id, 3)
        assertEquals(s, TerminalSessions.list(id))
        assertEquals(0, TerminalSessions.selected(id))
    }

    @Test
    fun removeBeforeSelectionShiftsSelectionDown() {
        val id = "remove-before"
        val s = populated(id, 3)
        TerminalSessions.setSelected(id, 2)
        TerminalSessions.remove(id, s[0])
        assertEquals(listOf(s[1], s[2]), TerminalSessions.list(id))
        // Still the same selected session, at its new index.
        assertEquals(1, TerminalSessions.selected(id))
    }

    @Test
    fun removeAfterSelectionKeepsSelection() {
        val id = "remove-after"
        val s = populated(id, 3)
        TerminalSessions.setSelected(id, 0)
        TerminalSessions.remove(id, s[2])
        assertEquals(0, TerminalSessions.selected(id))
    }

    @Test
    fun removeSelectedMiddleLandsOnNextNeighbor() {
        val id = "remove-selected-middle"
        val s = populated(id, 3)
        TerminalSessions.setSelected(id, 1)
        TerminalSessions.remove(id, s[1])
        assertEquals(1, TerminalSessions.selected(id))
        assertEquals(s[2], TerminalSessions.list(id)[1])
    }

    @Test
    fun removeSelectedLastClampsToPrevious()  {
        val id = "remove-selected-last"
        val s = populated(id, 3)
        TerminalSessions.setSelected(id, 2)
        TerminalSessions.remove(id, s[2])
        assertEquals(1, TerminalSessions.selected(id))
    }

    @Test
    fun removeUnknownSessionIsNoop() {
        val id = "remove-unknown"
        val s = populated(id, 2)
        TerminalSessions.setSelected(id, 1)
        TerminalSessions.remove(id, session())
        assertEquals(s, TerminalSessions.list(id))
        assertEquals(1, TerminalSessions.selected(id))
    }

    @Test
    fun removeLastSessionEmptiesEntry() {
        val id = "remove-last"
        val s = populated(id, 1)
        TerminalSessions.remove(id, s[0])
        assertTrue(TerminalSessions.list(id).isEmpty())
        assertEquals(0, TerminalSessions.selected(id))
    }

    @Test
    fun removeAllReturnsInOrderAndClears() {
        val id = "remove-all"
        val s = populated(id, 3)
        assertEquals(s, TerminalSessions.removeAll(id))
        assertTrue(TerminalSessions.list(id).isEmpty())
    }

    @Test
    fun setSelectedClampsOutOfRange() {
        val id = "select-clamp"
        populated(id, 2)
        TerminalSessions.setSelected(id, 5)
        assertEquals(1, TerminalSessions.selected(id))
        TerminalSessions.setSelected(id, -1)
        assertEquals(0, TerminalSessions.selected(id))
    }

    @Test
    fun detachedClientRemovesItsSession() {
        val id = "detached-remove"
        val s = populated(id, 2)
        DetachedTerminalClient(id).onSessionFinished(s[0])
        assertEquals(listOf(s[1]), TerminalSessions.list(id))
    }

    private fun terminalHolds(id: String): Int =
        SessionHolds.reasons.value.count { it == Reason.Terminal(id) }

    @Test
    fun pendingHoldsNothingAndIsNotATab() {
        val id = "pending-no-hold"
        val s = session()
        TerminalSessions.setPending(id, s)
        assertSame(s, TerminalSessions.pending(id))
        assertTrue(TerminalSessions.list(id).isEmpty())
        assertEquals(0, terminalHolds(id))
        assertTrue(s in TerminalSessions.all())
        TerminalSessions.killPending(id)
        assertNull(TerminalSessions.pending(id))
    }

    @Test
    fun promoteMovesPendingToLastTabWithHold() {
        val id = "pending-promote"
        val tabs = populated(id, 1)
        val s = session()
        TerminalSessions.setPending(id, s)
        assertSame(s, TerminalSessions.promote(id))
        assertNull(TerminalSessions.pending(id))
        assertEquals(tabs + s, TerminalSessions.list(id))
        assertEquals(2, terminalHolds(id))
        assertNull(TerminalSessions.promote(id))
        TerminalSessions.removeAll(id)
        assertEquals(0, terminalHolds(id))
    }

    @Test
    fun detachedClientClearsItsPendingSlotOnly() {
        val id = "pending-detached"
        val s = session()
        TerminalSessions.setPending(id, s)
        DetachedTerminalClient(id).onSessionFinished(session())
        assertSame(s, TerminalSessions.pending(id))
        DetachedTerminalClient(id).onSessionFinished(s)
        assertNull(TerminalSessions.pending(id))
    }

    @Test
    fun pendingPidsSkipsUnstartedSessions() {
        val id = "pending-pids"
        TerminalSessions.setPending(id, session())
        assertTrue(TerminalSessions.pendingPids().isEmpty())
        TerminalSessions.killPending(id)
    }

    @Test
    fun demoteOnlyTheSoleTabBackToPendingWithoutHold() {
        val id = "demote"
        val s = populated(id, 2)
        assertTrue(!TerminalSessions.demote(id, s[0]))
        TerminalSessions.remove(id, s[1])
        assertTrue(TerminalSessions.demote(id, s[0]))
        assertTrue(TerminalSessions.list(id).isEmpty())
        assertSame(s[0], TerminalSessions.pending(id))
        assertEquals(0, terminalHolds(id))
        TerminalSessions.killPending(id)
    }
}
