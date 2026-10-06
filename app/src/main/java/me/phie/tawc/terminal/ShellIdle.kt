package me.phie.tawc.terminal

import com.termux.terminal.TerminalEmulator
import java.io.File
import java.io.IOException

/**
 * When an in-use shell may go back to pending (notes/terminal.md
 * "Pending vs in use"): the input that promoted it was erased, and
 * nothing else runs in its session.
 */
internal object ShellIdle {

    /** Where the cursor sat when a pending shell got its first input,
     *  on a screen of [rows] x [columns]. */
    data class Anchor(val row: Int, val col: Int, val rows: Int, val columns: Int)

    fun anchorOf(emulator: TerminalEmulator): Anchor =
        Anchor(emulator.cursorRow, emulator.cursorCol, emulator.mRows, emulator.mColumns)

    /**
     * The screen is back to the pending shell's prompt: the cursor at
     * [promoted] (where it got its first input) with nothing at or after
     * it — input typed, then erased. A resize, output above or the
     * alternate screen doesn't count.
     */
    fun screenIsFresh(emulator: TerminalEmulator, promoted: Anchor): Boolean {
        if (emulator.isAlternateBufferActive || !promoted.isAt(emulator)) return false
        val row = emulator.cursorRow
        val col = emulator.cursorCol
        return emulator.screen.getSelectedText(col, row, emulator.mColumns - 1, emulator.mRows - 1).isBlank()
    }

    private fun Anchor.isAt(e: TerminalEmulator): Boolean =
        rows == e.mRows && columns == e.mColumns && row == e.cursorRow && col == e.cursorCol

    /**
     * Whether [shellPid] is the only process in its session (the shell
     * leads the pty's session). A foreground program, a background job
     * or a `nohup` child all share it; `setsid`'d ones don't, and
     * survive the shell anyway.
     */
    fun aloneInSession(shellPid: Int): Boolean {
        if (shellPid <= 0) return false
        val names = File("/proc").list() ?: return false
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == shellPid) continue
            val stat = try {
                File("/proc/$name/stat").readText()
            } catch (_: IOException) {
                continue // exited mid-scan
            }
            if (sessionOf(stat) == shellPid) return false
        }
        return true
    }

    /** Session id from a `/proc/<pid>/stat` line. */
    fun sessionOf(stat: String): Int? = statField(stat, 3)

    /** Controlling tty (`tty_nr`, 0 for none) from a `/proc/<pid>/stat` line. */
    fun ttyOf(stat: String): Int? = statField(stat, 4)

    /** The [index]th field after the `(comm)`, which may itself hold
     *  spaces and parentheses. */
    private fun statField(stat: String, index: Int): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        return fields.getOrNull(index)?.toIntOrNull()
    }
}
