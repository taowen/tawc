package me.phie.tawc.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.extrakeys.SpecialButton
import com.termux.shared.termux.terminal.io.TerminalExtraKeys
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.TawcrootMethod
import me.phie.tawc.ui.paneTopRowHeightPx
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * The home screen's terminal pane: interactive shells into one
 * installed rootfs, built on termux's vendored
 * terminal-emulator/terminal-view modules (Apache-2.0; see
 * settings.gradle.kts). The termux JNI forks the pty pair and execs
 * tawcroot as the pty child ([TawcrootMethod.ptyShellExec]), so the
 * in-rootfs shell gets a real controlling tty — readline, job control
 * and curses apps work, unlike the pipe-fed RunCommandOp path. No
 * compositor involvement: the Wayland env vars are set but nothing
 * waits for the socket, so the terminal works with the graphics stack
 * cold.
 *
 * Multiple shells show as tabs in a [TerminalTabBar]; one
 * [TerminalView] shows the selected session via `attachSession`
 * (termux-app's own multi-session pattern — background sessions keep a
 * stale pty size until selected). Tab labels follow the session's
 * xterm window title (OSC 0/2; TAWC's shipped bashrc defaults set a
 * cwd-only title — see ShellDefaults); unset and `~` titles show as
 * "Term <n>". Sessions live in [TerminalSessions], so recreation and
 * distro switches reattach.
 *
 * **Pending vs in use** (notes/terminal.md): with no tabs, showing the
 * pane spawns a *pending* shell — no session hold, no notification, no
 * tab strip (the distro label sits there), screen may sleep. The first
 * input that reaches it ([onKeyDown] / [onCodePoint] / a paste)
 * promotes it to an ordinary tab. It goes back to pending if that input
 * is erased again ([maybeDemote]); the last tab closing closes the app,
 * while [closeAll] leaves a fresh pending shell. A pending shell that dies (e.g. a broken `chsh`)
 * stays on screen; a tap or Enter respawns. The tab bar has no `+`
 * while pending.
 *
 * tawcroot-only: chroot spawns via su and proot is dev-only.
 */
internal class TerminalPane(
    private val activity: AppCompatActivity,
    private val distroId: String,
    private val distroLabel: CharSequence,
    private val method: TawcrootMethod,
    private val host: Host,
) : TerminalViewClient, TerminalSessionClient {

    interface Host {
        fun openDrawer()
        fun showMenu(anchor: View)
        /** Pending/in-use flipped (FAB, menu). */
        fun onTerminalStateChanged()
        /** The last in-use shell exited. */
        fun onLastShellExited()
    }

    private val store = InstallationStore(activity)
    private val density = activity.resources.displayMetrics.density
    private val terminalView: TerminalView
    private val extraKeysView: ExtraKeysView
    private val tabBar: TerminalTabBar
    // Volatile: [focusedTty] reads them off the main thread.
    @Volatile private var activeSession: TerminalSession? = null
    private var fontSizePx = fontSizePx()
    @Volatile private var detached = false

    // For [maybeDemote]: where the pending shell got its first input;
    // cleared by Enter, a paste or a tab switch.
    private var promotedAt: ShellIdle.Anchor? = null

    /** Black column: tab bar, terminal, extra keys. */
    val view: LinearLayout

    /** Height of the extra-keys row at the bottom (FAB clearance). */
    val extraKeysHeightPx: Int

    /** No tabs: the pane shows (or failed to spawn) the pending shell. */
    val isPending: Boolean get() = TerminalSessions.list(distroId).isEmpty()

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        tabBar = TerminalTabBar(activity).apply {
            onTabSelected = { selectTab(it) }
            onTabCloseClicked = { closeTab(it) }
            onNewTabClicked = { openNewTab() }
            onDrawerClicked = { host.openDrawer() }
            onMenuClicked = { host.showMenu(it) }
        }
        view.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, activity.paneTopRowHeightPx()))

        terminalView = TerminalView(activity, null).apply {
            setTerminalViewClient(this@TerminalPane)
            setTextSize(fontSizePx)
            // Bundled so glyph widths don't depend on the OEM's
            // "monospace" (a non-mono one gets per-glyph stretched).
            ResourcesCompat.getFont(activity, R.font.hack_regular)?.let { setTypeface(it) }
            setBackgroundColor(Color.BLACK)
            // Key events only reach the view when it can hold focus —
            // termux sets this in XML (activity_termux.xml); the view
            // itself doesn't.
            isFocusableInTouchMode = true
        }
        view.addView(terminalView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Termux's extra-keys row (ESC/arrows/CTRL/...) between the
        // terminal and the IME. Same default layout and per-row height
        // as termux; held CTRL/ALT/SHIFT/FN state is consumed via the
        // read*Key() client callbacks below. Targets the view, not a
        // session, so tab switches need no extra-keys work.
        val extraKeysInfo = ExtraKeysInfo(
            EXTRA_KEYS_CONFIG, EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES,
        )
        val rowHeightPx = EXTRA_KEYS_ROW_HEIGHT_DP * density
        extraKeysView = ExtraKeysView(activity, null).apply {
            setExtraKeysViewClient(TerminalExtraKeys(terminalView))
            setBackgroundColor(Color.BLACK)
        }
        extraKeysHeightPx = (rowHeightPx * extraKeysInfo.matrix.size + 0.5f).toInt()
        view.addView(extraKeysView, LinearLayout.LayoutParams(MATCH_PARENT, extraKeysHeightPx))
        extraKeysView.reload(extraKeysInfo, rowHeightPx)
    }

    /**
     * Show the in-use tabs, or the pending shell (reused from the
     * registry across recreation, else spawned). [command] (a
     * `Terminal=true` launcher entry) opens as a new in-use tab first.
     */
    fun attach(command: CommandTab? = null) {
        current = WeakReference(this)
        if (command != null) {
            // The command is what the user asked for; a pending shell
            // beside it would be an extra tab nobody opened.
            TerminalSessions.killPending(distroId)
            spawnSession(command.exec, command.label)?.let { TerminalSessions.add(distroId, it) }
        }
        val sessions = TerminalSessions.list(distroId)
        if (sessions.isEmpty()) {
            showPending(TerminalSessions.pending(distroId) ?: spawnPending())
            return
        }
        for ((i, s) in sessions.withIndex()) {
            s.updateTerminalSessionClient(this)
            tabBar.addTab(labelFor(s, i))
        }
        showTabs()
        selectTab(if (command != null) sessions.size - 1 else TerminalSessions.selected(distroId))
        terminalView.requestFocus()
    }

    /**
     * Stop driving the sessions. In-use shells keep running under a
     * [DetachedTerminalClient]; the pending one is killed unless
     * [keepPending] (activity recreation reattaches it).
     */
    fun detach(keepPending: Boolean = false) {
        if (detached) return
        detached = true
        if (current?.get() === this) current = null
        view.removeCallbacks(relabel)
        for (s in TerminalSessions.list(distroId)) {
            s.updateTerminalSessionClient(DetachedTerminalClient(distroId))
        }
        if (keepPending) {
            TerminalSessions.pending(distroId)?.updateTerminalSessionClient(DetachedTerminalClient(distroId))
        } else {
            TerminalSessions.killPending(distroId)
        }
        activeSession = null
    }

    fun onResume() {
        // Terminal scale may have changed in settings.
        val size = fontSizePx()
        if (size != fontSizePx) {
            fontSizePx = size
            terminalView.setTextSize(size)
        }
        screenUpdated()
    }

    fun showSoftKeyboard() {
        terminalView.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Post: on first show the view isn't attached to the window yet.
        terminalView.post { imm.showSoftInput(terminalView, 0) }
    }

    /** Hang up every tab and show a fresh pending shell. */
    fun closeAll() {
        val sessions = TerminalSessions.removeAll(distroId)
        if (sessions.isEmpty()) return
        // Dropped from the registry first, so their exits find no tab.
        for (i in sessions.indices.reversed()) tabBar.removeTab(i)
        TerminalSessions.hangUp(sessions)
        promotedAt = null
        showPending(spawnPending())
        host.onTerminalStateChanged()
    }

    /** A `Terminal=true` entry: [exec] in a new in-use tab. */
    fun openCommandTab(command: CommandTab) {
        TerminalSessions.killPending(distroId)
        openNewTab(command.exec, command.label)
    }

    // ---- pending ---------------------------------------------------------

    private fun spawnPending(): TerminalSession? =
        spawnSession()?.also { TerminalSessions.setPending(distroId, it) }

    /** [session] null = the spawn failed (toast shown); a tap retries. */
    private fun showPending(session: TerminalSession?) {
        tabBar.setPendingLabel(distroLabel)
        terminalView.keepScreenOn = false
        activeSession = session
        if (session != null) {
            session.updateTerminalSessionClient(this)
            terminalView.attachSession(session)
            terminalView.onScreenUpdated()
        }
        terminalView.requestFocus()
    }

    private fun showTabs() {
        tabBar.setPendingLabel(null)
        terminalView.keepScreenOn = true
    }

    /** The pending shell is running and got input: make it tab 1. */
    private fun promotePending() {
        val session = activeSession ?: return
        if (!isPending || !session.isRunning || TerminalSessions.pending(distroId) !== session) return
        promotedAt = session.emulator?.let { ShellIdle.anchorOf(it) }
        TerminalSessions.promote(distroId)
        showTabs()
        tabBar.addTab(labelFor(session, 0))
        tabBar.setSelected(0)
        host.onTerminalStateChanged()
    }

    /**
     * Back to pending when the promoted shell's input was erased with
     * nothing entered: the screen is the prompt it had when pending, and
     * nothing else runs in its session.
     */
    private fun maybeDemote(session: TerminalSession) {
        val promoted = promotedAt ?: return
        if (detached || session !== activeSession || !session.isRunning) return
        val emulator = session.emulator ?: return
        if (!ShellIdle.screenIsFresh(emulator, promoted)) return
        if (!ShellIdle.aloneInSession(session.pid)) return
        if (!TerminalSessions.demote(distroId, session)) return
        promotedAt = null
        tabBar.removeTab(0)
        showPending(session)
        host.onTerminalStateChanged()
    }

    /** Dead or failed pending shell: replace it with a fresh one. */
    private fun respawnPending() {
        TerminalSessions.killPending(distroId)
        showPending(spawnPending())
    }

    private fun pendingIsDead(): Boolean = isPending && activeSession?.isRunning != true

    // ---- tabs ------------------------------------------------------------

    /**
     * Spawn a fresh shell session, or toast and return null on failure:
     * ptyShellExec fails closed (IOException) on a bad external bind —
     * revoked all-files access, missing host dir
     * (notes/external-binds.md). The user fixes it under Manage binds /
     * settings.
     *
     * [command] (a launcher entry's Exec line) runs wrapped in the
     * hold-open trailer so its output survives exit until a keypress;
     * [label] names the tab until an OSC title arrives ([labelFor]).
     */
    private fun spawnSession(command: String? = null, label: String? = null): TerminalSession? {
        // GUI programs typed into the shell start the compositor by
        // connecting; its sockets must be listening first.
        CompositorService.ensureActivation(activity)
        val exec = try {
            method.ptyShellExec(
                store.rootfsDir(distroId).absolutePath,
                command = command?.let { "$it$HOLD_OPEN_TRAILER" },
            )
        } catch (e: IOException) {
            Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
            return null
        }
        return TerminalSession(
            exec.argv[0],
            exec.cwd,
            exec.argv.toTypedArray(),
            exec.hostEnv.toTypedArray(),
            TRANSCRIPT_ROWS,
            this,
        ).also { it.mSessionName = label }
    }

    private fun labelFor(session: TerminalSession, index: Int): CharSequence {
        val title = session.title?.takeUnless { it.isBlank() }
        // The shipped bashrc defaults title tabs with the cwd
        // (ShellDefaults), so every fresh tab would read `~` — number
        // those by tab position instead, and use the same numbering
        // while no title is set yet. Command tabs carry the launcher
        // entry's name in mSessionName.
        return if (title == null || title == "~") {
            session.mSessionName?.takeUnless { it.isBlank() }
                ?: activity.getString(R.string.terminal_tab_home, index + 1)
        } else {
            title
        }
    }

    /** Reapply every tab's label (index-derived labels shift on close). */
    private fun relabelTabs() {
        TerminalSessions.list(distroId).forEachIndexed { i, s -> tabBar.setLabel(i, labelFor(s, i)) }
    }

    /** Attach the session at [index] (registry order == bar order). */
    private fun selectTab(index: Int) {
        val session = TerminalSessions.list(distroId).getOrNull(index) ?: return
        if (session !== activeSession) promotedAt = null
        TerminalSessions.setSelected(distroId, index)
        activeSession = session
        tabBar.setSelected(index)
        // attachSession resets emulator/top-row state and updateSize()s,
        // (re)initializing or SIGWINCHing the pty for the current view.
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
    }

    private fun openNewTab(command: String? = null, label: String? = null) {
        // Only command tabs get here while pending (`+` is hidden then),
        // and those already killed the pending shell.
        if (pendingIsDead()) TerminalSessions.killPending(distroId)
        promotePending()
        val session = spawnSession(command, label) ?: return // toast shown; existing tabs stay up
        val wasPending = isPending
        TerminalSessions.add(distroId, session)
        if (wasPending) showTabs()
        val index = TerminalSessions.list(distroId).size - 1
        tabBar.addTab(labelFor(session, index))
        selectTab(index)
        if (wasPending) host.onTerminalStateChanged()
    }

    private fun closeTab(index: Int) {
        val session = TerminalSessions.list(distroId).getOrNull(index) ?: return
        if (session.isRunning && session.pid > 0) {
            // The exit lands back in onSessionFinished, which removes
            // the tab.
            session.finishIfRunning()
        } else {
            // Already-dead shell (race window before onSessionFinished,
            // or a never-started session): drop the tab directly.
            removeFinishedSession(session)
        }
    }

    /** Drop [session]'s tab; pick the neighbor, or report the last exit. */
    private fun removeFinishedSession(session: TerminalSession) {
        val index = TerminalSessions.list(distroId).indexOfFirst { it === session }
        if (index < 0) return
        TerminalSessions.remove(distroId, session)
        tabBar.removeTab(index)
        if (TerminalSessions.list(distroId).isEmpty()) {
            // Last shell gone (user typed `exit`, closed the tab, or the
            // rootfs side died): like closing a desktop terminal window.
            activeSession = null
            host.onLastShellExited()
            return
        }
        relabelTabs()
        if (session === activeSession) {
            selectTab(TerminalSessions.selected(distroId))
        } else {
            // Indices shifted under the unchanged selection; restyle.
            tabBar.setSelected(TerminalSessions.selected(distroId))
        }
    }

    /** sp so it follows the system font size; see [Settings.terminalScale]. */
    private fun fontSizePx(): Int {
        val sp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SIZE_SP, activity.resources.displayMetrics,
        )
        return (sp * Settings.terminalScale).toInt().coerceAtLeast(1)
    }

    // ---- TerminalViewClient --------------------------------------------

    // Pinch-zoom disabled: size comes from the terminal scale setting.
    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent) {
        if (pendingIsDead()) respawnPending()
        showSoftKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    // TYPE_NULL input: makes IMEs send discrete key events instead of
    // composing/autocorrecting — same reason the Run dialog uses
    // VISIBLE_PASSWORD. Composing IMEs misbehave against a terminal.
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    // Together with onCodePoint and the paste callback this gates every
    // TerminalView write path except autofill, so it is where a
    // pending shell becomes in use. System keys (Back, volume) and bare
    // modifiers write nothing.
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            if (pendingIsDead()) {
                respawnPending()
            } else {
                // Enter on a dead tab closes it (only reachable in the
                // race window before onSessionFinished lands).
                removeFinishedSession(session)
            }
            return true
        }
        if (!e.isSystem && !KeyEvent.isModifierKey(keyCode)) {
            snapToBottom()
            promotePending()
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) promotedAt = null
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    // Held/locked virtual modifiers from the extra-keys row (matches
    // termux's TermuxTerminalViewClient.readExtraKeysSpecialButton).
    private fun readSpecialButton(button: SpecialButton): Boolean =
        extraKeysView.readSpecialButton(button, true) == true

    override fun readControlKey(): Boolean = readSpecialButton(SpecialButton.CTRL)

    override fun readAltKey(): Boolean = readSpecialButton(SpecialButton.ALT)

    override fun readShiftKey(): Boolean = readSpecialButton(SpecialButton.SHIFT)

    override fun readFnKey(): Boolean = readSpecialButton(SpecialButton.FN)

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        snapToBottom()
        promotePending()
        if (codePoint == '\r'.code || codePoint == '\n'.code) promotedAt = null
        return false
    }

    override fun onEmulatorSet() {}

    // ---- TerminalSessionClient -----------------------------------------
    // The pane is the sole client for its distro's live sessions; each
    // callback carries the changed session, so display callbacks act
    // only for the selected tab while background sessions keep
    // accumulating transcript via their pty reader threads.

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession !== activeSession) return
        screenUpdated()
        maybeDemote(changedSession)
    }

    /**
     * onScreenUpdated() that holds the viewport on the same lines when
     * scrolled back, instead of termux's unconditional snap to bottom.
     */
    private fun screenUpdated() {
        val emulator = terminalView.mEmulator
        val topRow = terminalView.topRow
        // Pinned to the bottom, or upstream already shifts (selection).
        if (emulator == null || topRow == 0 || terminalView.isSelectingText) {
            terminalView.onScreenUpdated()
            return
        }
        val shift = emulator.scrollCounter // cleared by onScreenUpdated
        terminalView.onScreenUpdated(true)
        // Once the transcript ring drops the held lines, sit at the oldest.
        terminalView.topRow = maxOf(-emulator.screen.activeTranscriptRows, topRow - shift)
        terminalView.invalidate()
    }

    /** Input while scrolled back jumps to the live screen, like xterm. */
    private fun snapToBottom() {
        if (terminalView.topRow == 0) return
        terminalView.topRow = 0
        terminalView.invalidate()
    }

    // A prompt can set the title twice in a row (a distro
    // PROMPT_COMMAND's `root@localhost:~`, then ShellDefaults' `~`),
    // and the two may land in separate output chunks; relabelling on
    // each would flash the long one. Settle first.
    override fun onTitleChanged(changedSession: TerminalSession) {
        view.removeCallbacks(relabel)
        view.postDelayed(relabel, TITLE_SETTLE_MS)
    }

    private val relabel = Runnable { if (!detached) relabelTabs() }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (TerminalSessions.pending(distroId) === finishedSession) {
            // Died before anyone typed (e.g. a broken login shell):
            // closing the app here would make it unopenable. Keep the
            // transcript with termux's exit line; tap/Enter respawns.
            if (finishedSession === activeSession) screenUpdated()
            return
        }
        removeFinishedSession(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
        val text = item.coerceToText(activity).toString()
        if (text.isEmpty()) return
        snapToBottom()
        promotePending()
        // A pasted newline runs something: no longer the untouched screen.
        promotedAt = null
        terminalView.currentSession?.emulator?.paste(text)
    }

    // Backspace on an already-empty line only rings the bell.
    override fun onBell(session: TerminalSession) {
        maybeDemote(session)
    }

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // ---- logging (both client interfaces route logs through us) --------

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "", e)
    }

    /** A launcher entry's Exec line to run in a tab, and its label. */
    data class CommandTab(val exec: String, val label: String?)

    /** [Companion.focusedTty] for this pane. */
    private fun focusedTty(): Int {
        if (detached || !terminalView.hasWindowFocus()) return 0
        val pid = activeSession?.pid?.takeIf { it > 0 } ?: return 0
        return try {
            ShellIdle.ttyOf(File("/proc/$pid/stat").readText()) ?: 0
        } catch (_: IOException) {
            0
        }
    }

    companion object {
        /** The attached pane, for [focusedTty]. */
        @Volatile private var current: WeakReference<TerminalPane>? = null

        /**
         * `tty_nr` of the shell the user is looking at: the attached
         * pane's selected tab, while its window has Android focus. 0 for
         * none. Any thread — the compositor gates `wl-paste` on it
         * (notes/clipboard.md).
         */
        fun focusedTty(): Int = current?.get()?.focusedTty() ?: 0

        /**
         * Appended to every command tab before spawn: the session would
         * exit (and [onSessionFinished] drop the tab) the moment the
         * command finishes, vanishing its output. Holding in `read`
         * keeps the shell alive until a keypress, then the normal
         * tab-removal flow runs — no session-lifecycle changes.
         */
        const val HOLD_OPEN_TRAILER =
            "; __c=$?; printf '\\n[exited %d — press any key]\\n' \"\$__c\"; read -rsn1"

        const val TAG = "tawc-terminal"
        const val TITLE_SETTLE_MS = 150L
        const val TRANSCRIPT_ROWS = 4000
        const val DEFAULT_FONT_SIZE_SP = 13f
        // Termux's default extra-keys config and per-row height
        // (TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS and the
        // 37.5dp terminal_toolbar_view_pager in activity_termux.xml).
        const val EXTRA_KEYS_CONFIG =
            "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], " +
                "['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
        const val EXTRA_KEYS_STYLE = "default"
        const val EXTRA_KEYS_ROW_HEIGHT_DP = 37.5f
    }
}
