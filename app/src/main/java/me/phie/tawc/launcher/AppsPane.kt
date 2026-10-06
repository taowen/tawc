package me.phie.tawc.launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ui.plainIconButton
import me.phie.tawc.ui.tawcButtonSizePx
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.verticalLp

/**
 * The home screen's apps pane: an alphabetical icon grid of installed
 * `.desktop` apps for one distro. The Rust compositor library does the
 * actual scanning and name sort ([LauncherEntry.scan]); Kotlin here
 * just renders + filters + dispatches launches.
 *
 * Layout is Android-launcher-like: a `[≡] <distro> [🔍][⋮]` header,
 * then a grid of icons with single-line names (no descriptions). 🔍
 * (or typing on a hardware keyboard) opens a search field under the
 * header that filters the grid; Enter launches the top match, and ✕ or
 * Back closes it. Tap launches; long-press opens a per-entry action
 * menu (Hide/Unhide, Add to home screen, Edit — assembled in
 * [entryActionsFor]). The home ⋮ gets this pane's items from
 * [addMenuItems] (Show hidden, Add entry…). Pinning, frecency,
 * window-list integration are deferred (see notes/launcher.md
 * "Future UX").
 *
 * Launches are fire-and-forget via [EntryLauncher], whose process-wide
 * scope outlives the pane. The list is rescanned on every show and
 * resume, so packages installed from the terminal show up.
 *
 * Hidden entries ([Installation.hiddenDesktopIds]) are filtered here in
 * Kotlin, not in the Rust scanner — hide state is per-install app
 * metadata, and the scanner is shared with window icon/title resolution
 * which must keep seeing hidden apps (notes/launcher.md).
 */
internal class AppsPane(
    private val activity: AppCompatActivity,
    private var installation: Installation,
    private val host: Host,
) {

    interface Host {
        fun openDrawer()
        fun showMenu(anchor: View)
        /** Start the `.desktop` editor for result; RESULT_OK → [rescan]. */
        fun openEditor(intent: Intent)
        /** Grid scrolled; hide the FAB going down, show it going up. */
        fun onGridScrolled(down: Boolean)
    }

    private val store = InstallationStore(activity)
    private val density = activity.resources.displayMetrics.density
    private val pad = (16 * density).toInt()

    private val searchRow: View
    private val searchField: EditText
    private val grid: RecyclerView
    private val gridLayout: GridLayoutManager
    private val adapter = EntryAdapter()
    private val emptyView: TextView

    /** Full app list (from the last scan). Filtered subset is rebuilt on every keystroke. */
    private var allEntries: List<LauncherEntry> = emptyList()
    private var filteredEntries: List<LauncherEntry> = emptyList()

    /** Render hidden entries (dimmed, in sort position). Transient
     *  per-pane state, deliberately not persisted. */
    private var showHidden = false

    /** UI scope for loading + search filtering. Cancelled on [destroy]. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val iconSizePx = (ICON_SIZE_DP * density).toInt()
    private val iconLoader = IconLoader(uiScope, iconSizePx)

    /** A hardware Enter arrives both as a key event and as the IME
     *  editor action (~10ms apart); without a debounce one press
     *  launches twice. */
    private var lastLaunchMs = 0L

    val view: LinearLayout

    val isSearchOpen: Boolean get() = searchRow.visibility == View.VISIBLE

    init {
        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        view.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, (HEADER_HEIGHT_DP * density).toInt()))

        searchField = EditText(activity).apply {
            hint = activity.getString(R.string.hint_search_apps)
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_GO
            isFocusableInTouchMode = true
            doAfterTextChanged {
                applyFilter()
                grid.scrollToPosition(0)
            }
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isEnter) { launchTop(); true } else false
            }
        }
        searchRow = buildSearchRow()
        view.addView(
            searchRow,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.setMargins(pad, 0, pad, pad / 2)
            },
        )

        emptyView = TextView(activity).apply {
            text = activity.getString(R.string.launcher_loading_apps)
            textSize = 14f
            alpha = 0.7f
            setPadding(pad, pad / 2, pad, 0)
        }
        view.addView(emptyView, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        gridLayout = GridLayoutManager(activity, MIN_COLUMNS)
        grid = RecyclerView(activity).apply {
            layoutManager = gridLayout
            adapter = this@AppsPane.adapter
            // Room past the last row, so it can scroll clear of the FAB.
            setPadding(pad / 2, pad / 4, pad / 2, (BOTTOM_CLEARANCE_DP * density).toInt())
            clipToPadding = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(pad)
            overScrollMode = View.OVER_SCROLL_NEVER
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) host.onGridScrolled(dy > 0)
                }
            })
            // Columns follow the width (rotation, split screen).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val columns = maxOf(MIN_COLUMNS, v.width / (CELL_MIN_WIDTH_DP * density).toInt())
                if (columns != gridLayout.spanCount) v.post { gridLayout.spanCount = columns }
            }
        }
        view.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        rescan()
    }

    /** `[≡] <distro> [🔍][⋮]`, taller than the terminal's tab row. */
    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad / 4, 0, pad / 4, 0)
        }
        val button = activity.tawcButtonSizePx()
        row.addView(
            activity.plainIconButton(R.drawable.ic_menu, activity.getString(R.string.action_open_drawer)) {
                host.openDrawer()
            },
            LinearLayout.LayoutParams(button, button),
        )
        row.addView(TextView(activity).apply {
            text = DistroRegistry.displayLabel(installation)
            textSize = 22f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(pad / 2, 0, pad / 2, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(
            activity.plainIconButton(R.drawable.ic_search, activity.getString(R.string.action_search)) {
                if (isSearchOpen) closeSearch() else openSearch()
            },
            LinearLayout.LayoutParams(button, button),
        )
        // Under the shared glyph size: three solid dots read heavier
        // than the line icons everything else uses.
        lateinit var menuButton: View
        menuButton = activity.plainIconButton(
            R.drawable.ic_more_vert,
            activity.getString(R.string.home_menu_description),
            iconSizeDp = 21,
        ) { host.showMenu(menuButton) }
        row.addView(menuButton, LinearLayout.LayoutParams(button, button))
        return row
    }

    /** Rounded `[🔍 field ✕]` pill under the header; hidden until opened. */
    private fun buildSearchRow(): View {
        val card = activity.tawcCard().apply {
            radius = activity.tawcButtonSizePx() / 2f
            visibility = View.GONE
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 3 / 4, 0, 0, 0)
        }
        val glyph = (20 * density).toInt()
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            alpha = 0.7f
        }, LinearLayout.LayoutParams(glyph, glyph).also { it.marginEnd = pad / 2 })
        row.addView(searchField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val button = activity.tawcButtonSizePx()
        row.addView(
            activity.plainIconButton(R.drawable.ic_close, activity.getString(R.string.action_close_search)) {
                closeSearch()
            },
            LinearLayout.LayoutParams(button, button),
        )
        card.addView(row)
        return card
    }

    fun onResume() {
        // Hide state may have changed elsewhere (another pane instance).
        store.load(installation.id)?.let { installation = it }
        rescan()
    }

    fun destroy() {
        uiScope.cancel()
    }

    /** Show the search field, focused with the IME up; [initial] seeds it. */
    fun openSearch(initial: CharSequence = "") {
        searchRow.visibility = View.VISIBLE
        if (initial.isNotEmpty()) {
            searchField.append(initial)
        }
        searchField.requestFocus()
        // Post: on first show the field isn't laid out yet.
        searchField.post {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Clear and hide the search field. Returns whether it was open (Back). */
    fun closeSearch(): Boolean {
        if (!isSearchOpen) return false
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        searchRow.visibility = View.GONE
        searchField.text.clear()
        return true
    }

    /**
     * A key nothing focused consumed (hardware keyboard): a printable
     * character opens search with it, launcher-style.
     */
    fun onUnhandledKey(event: KeyEvent): Boolean {
        if (isSearchOpen || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val c = event.unicodeChar
        if (c == 0 || Character.isISOControl(c) || Character.isWhitespace(c)) return false
        openSearch(String(Character.toChars(c)))
        return true
    }

    /** This pane's group of the home ⋮ menu. */
    fun addMenuItems(menu: Menu, order: Int) {
        val hidden = hiddenCount()
        if (hidden > 0) {
            menu.add(Menu.NONE, Menu.NONE, order, activity.getString(R.string.launcher_menu_show_hidden, hidden))
                .apply {
                isCheckable = true
                isChecked = showHidden
                setOnMenuItemClickListener {
                    showHidden = !showHidden
                    applyFilter()
                    true
                }
            }
        }
        if (canEditEntries()) {
            menu.add(Menu.NONE, Menu.NONE, order, R.string.launcher_menu_add_entry).setOnMenuItemClickListener {
                openEditor(null)
                true
            }
        }
    }

    fun rescan() {
        val rootfs = store.rootfsDir(installation.id).absolutePath
        uiScope.launch {
            allEntries = withContext(Dispatchers.IO) { LauncherEntry.scan(rootfs) }
            applyFilter()
            if (allEntries.isEmpty()) {
                emptyView.text = activity.getString(R.string.launcher_no_launchable_apps)
            }
        }
    }

    /** Ids the user hid, from the current metadata record. */
    private fun hiddenIds(): Set<String> = installation.hiddenDesktopIds.toSet()

    /** Hidden entries that actually exist in this rootfs (stale ids don't count). */
    private fun hiddenCount(): Int {
        val hidden = hiddenIds()
        return allEntries.count { it.id in hidden }
    }

    /** Re-filter [allEntries] against hide state + the search field
     *  ([LauncherEntry.filter]) and re-render. */
    private fun applyFilter() {
        filteredEntries = LauncherEntry.filter(
            allEntries, hiddenIds(), showHidden, searchField.text.toString(),
        )
        renderList()
    }

    private fun renderList() {
        adapter.submit(filteredEntries, hiddenIds())
        if (filteredEntries.isEmpty() && allEntries.isNotEmpty()) {
            val q = searchField.text.toString().trim()
            emptyView.text = if (q.isEmpty()) {
                // Every entry is hidden (show-hidden off): keep the
                // no-apps message but say why the grid is empty.
                activity.getString(R.string.launcher_no_launchable_apps) + "\n" +
                    activity.getString(R.string.launcher_hidden_count_hint, hiddenCount())
            } else {
                activity.getString(R.string.launcher_no_matches)
            }
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = if (allEntries.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Editor writes are plain app-uid file I/O into the rootfs — works
     * for tawcroot/proot but not chroot's root-owned rootfs
     * (notes/launcher.md "Access model"), so chroot installs get no
     * New/Edit entry points, consistent with the terminal gating.
     */
    private fun canEditEntries(): Boolean = installation.method != Installation.METHOD_CHROOT

    private fun openEditor(entryPath: String?) {
        val i = Intent(activity, DesktopFileEditorActivity::class.java)
            .putExtra(DesktopFileEditorActivity.EXTRA_ID, installation.id)
        if (entryPath != null) i.putExtra(DesktopFileEditorActivity.EXTRA_PATH, entryPath)
        host.openEditor(i)
    }

    /**
     * One long-press menu item. Assembled per entry by [entryActionsFor];
     * conditional items are dropped there (`takeIf`/`listOfNotNull`).
     */
    private data class EntryAction(
        val label: CharSequence,
        val run: () -> Unit,
    )

    private fun entryActionsFor(entry: LauncherEntry): List<EntryAction> {
        val hidden = entry.id in hiddenIds()
        return listOfNotNull(
            if (hidden) {
                EntryAction(activity.getString(R.string.launcher_action_unhide)) { setEntryHidden(entry, false) }
            } else {
                EntryAction(activity.getString(R.string.launcher_action_hide)) { setEntryHidden(entry, true) }
            },
            EntryAction(activity.getString(R.string.launcher_action_add_home)) { pinEntry(entry) },
            // Only entries in the managed dir are editable — everything
            // else is package-owned (see DesktopEntryFile).
            EntryAction(activity.getString(R.string.launcher_action_edit)) { openEditor(entry.path) }
                .takeIf {
                    canEditEntries() &&
                        DesktopEntryFile.isManaged(entry.path, store.rootfsDir(installation.id))
                },
        )
    }

    private fun showEntryMenu(entry: LauncherEntry) {
        val actions = entryActionsFor(entry)
        if (actions.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(entry.name.ifEmpty { entry.id })
            .setItems(actions.map { it.label }.toTypedArray()) { _, which ->
                actions[which].run()
            }
            .show()
    }

    /**
     * Pin [entry] to the home screen ([EntryShortcuts]). Icon decode is
     * I/O, so build the request off the main thread; the system pin
     * sheet takes over from there.
     */
    private fun pinEntry(entry: LauncherEntry) {
        val inst = installation
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                EntryShortcuts.requestPin(activity, inst, entry)
            }
            val toast = when (result) {
                EntryShortcuts.PinResult.REQUESTED -> null
                EntryShortcuts.PinResult.UPDATED -> R.string.shortcut_pin_updated
                EntryShortcuts.PinResult.UNSUPPORTED -> R.string.shortcut_pin_unsupported
            }
            toast?.let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Persist hide/unhide through the locked read-modify-write.
     * [InstallationStore.update] returns the record it wrote, which
     * becomes the new [installation] so the filter sees the fresh set;
     * null (lost race against uninstall) just leaves the list as-is —
     * the whole slot is going away.
     */
    private fun setEntryHidden(entry: LauncherEntry, hidden: Boolean) {
        store.update(installation.id) { it.withEntryHidden(entry.id, hidden) }
            ?.let { installation = it }
        applyFilter()
    }

    private class Cell(val root: LinearLayout, val icon: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(root)

    /** Grid cells: icon over a one-line, end-ellipsized name. */
    private inner class EntryAdapter : RecyclerView.Adapter<Cell>() {
        private var entries: List<LauncherEntry> = emptyList()
        private var hidden: Set<String> = emptySet()

        fun submit(entries: List<LauncherEntry>, hidden: Set<String>) {
            this.entries = entries
            this.hidden = hidden
            notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val cellPad = (8 * density).toInt()
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(cellPad, cellPad, cellPad, cellPad)
                isClickable = true
                isFocusable = true
                isLongClickable = true
                val ripple = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            root.addView(icon, LinearLayout.LayoutParams(iconSizePx, iconSizePx).also { it.bottomMargin = cellPad * 3 / 4 })
            val label = TextView(activity).apply {
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return Cell(root, icon, label)
        }

        override fun onBindViewHolder(cell: Cell, position: Int) {
            val entry = entries[position]
            val name = entry.name.ifEmpty { entry.id }
            cell.label.text = name
            cell.root.contentDescription = name
            cell.root.alpha = if (entry.id in hidden) 0.5f else 1f
            cell.root.setOnClickListener { launchEntry(entry) }
            cell.root.setOnLongClickListener { showEntryMenu(entry); true }
            iconLoader.load(
                entry.iconPath,
                cell.icon,
                if (entry.terminal) R.drawable.ic_terminal_fallback else R.drawable.ic_app_fallback,
            )
        }
    }

    private fun launchTop() {
        val top = filteredEntries.firstOrNull() ?: return
        launchEntry(top)
    }

    /**
     * Fire-and-forget launch via [EntryLauncher]; failures surface from
     * there ([LaunchErrorActivity]). Search is closed and the IME
     * dropped: the app's window (or the terminal) comes forward, and
     * this pane is what the user returns to.
     */
    private fun launchEntry(entry: LauncherEntry) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchMs < LAUNCH_DEBOUNCE_MS) return
        lastLaunchMs = now
        EntryLauncher.launch(activity.applicationContext, installation, entry)
        closeSearch()
    }

    private companion object {
        /** Square icon edge in dp, about a phone launcher's. */
        const val ICON_SIZE_DP = 52f

        /** Header height: a Material top app bar, roomier than the
         *  terminal's 48dp tab row. */
        const val HEADER_HEIGHT_DP = 64

        /** Columns: as many [CELL_MIN_WIDTH_DP] cells as fit, at least [MIN_COLUMNS]. */
        const val CELL_MIN_WIDTH_DP = 88
        const val MIN_COLUMNS = 3

        /** Grid bottom padding: FAB (56) + its margins (16 + 16). */
        const val BOTTOM_CLEARANCE_DP = 88

        const val LAUNCH_DEBOUNCE_MS = 500L
    }
}
