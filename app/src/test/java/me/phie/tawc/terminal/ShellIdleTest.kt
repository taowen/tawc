package me.phie.tawc.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ShellIdle] against a real (pure-Java) termux emulator. */
class ShellIdleTest {

    private object NullOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private fun emulator(vararg output: String): TerminalEmulator =
        TerminalEmulator(NullOutput, 40, 10, 8, 16, 100, null).also { e ->
            for (s in output) {
                val bytes = s.toByteArray()
                e.append(bytes, bytes.size)
            }
        }

    private fun TerminalEmulator.feed(s: String) {
        val bytes = s.toByteArray()
        append(bytes, bytes.size)
    }

    @Test
    fun typedThenErasedReturnsToAnchor() {
        val e = emulator("motd\r\n~ # ")
        val anchor = ShellIdle.anchorOf(e)
        e.feed("ls -l")
        assertFalse(ShellIdle.screenIsFresh(e, anchor))
        e.feed("\b \b".repeat(5))
        assertTrue(ShellIdle.screenIsFresh(e, anchor))
    }

    @Test
    fun enterMovesPastTheAnchor() {
        val e = emulator("motd\r\n~ # ")
        val anchor = ShellIdle.anchorOf(e)
        e.feed("\r\n~ # ")
        assertFalse(ShellIdle.screenIsFresh(e, anchor))
    }

    @Test
    fun altScreenIsNever() {
        val e = emulator("~ # ", "\u001b[?1049h")
        assertFalse(ShellIdle.screenIsFresh(e, ShellIdle.anchorOf(e)))
    }

    @Test
    fun sessionFieldParsesPastParenthesesInComm() {
        assertEquals(4321, ShellIdle.sessionOf("1234 (a) b (c)) S 1 1234 4321 34816 1234 0"))
        assertEquals(77, ShellIdle.sessionOf("88 (bash) S 77 88 77 34816 88 4194304"))
        assertNull(ShellIdle.sessionOf("garbage"))
    }
}
