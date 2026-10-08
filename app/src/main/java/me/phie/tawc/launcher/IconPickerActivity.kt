package me.phie.tawc.launcher

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.plainIconButton
import me.phie.tawc.ui.searchPill
import me.phie.tawc.ui.verticalLp
import org.json.JSONArray

/**
 * Searchable grid of every icon name in a distro
 * ([NativeBridge.nativeListIcons]), for the entry editor's Select
 * button. Started for result with [EXTRA_ID]; returns the tapped name
 * in [EXTRA_NAME]. Cells resolve lazily ([NativeBridge.nativeResolveIcon]),
 * through the same resolver and SVG cache as the launcher grid, so a
 * cell shows exactly what the launcher would draw for that name.
 */
class IconPickerActivity : AppCompatActivity() {

    private class Icon(val name: String, val user: Boolean)

    private lateinit var rootfs: String
    private lateinit var searchField: EditText
    private lateinit var statusView: TextView
    private val adapter = IconAdapter()

    private var all: List<Icon> = emptyList()
    private var loaded = false

    /** name → resolved path, so scrolling back doesn't re-resolve. */
    private val resolved = HashMap<String, String>()

    /** Resolves run in parallel, but not one IO thread per visible cell. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val resolveDispatcher = Dispatchers.IO.limitedParallelism(RESOLVE_PARALLELISM)

    private val density by lazy { resources.displayMetrics.density }
    private val iconSizePx by lazy { (AppsPane.ICON_SIZE_DP * density).toInt() }
    private val iconLoader by lazy { IconLoader(lifecycleScope, iconSizePx) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = InstallationStore(this)
        val installId = intent?.getStringExtra(EXTRA_ID) ?: ""
        if (store.load(installId) == null) {
            finish()
            return
        }
        rootfs = store.rootfsDir(installId).absolutePath

        val scaffold = buildChildScreen(getString(R.string.icon_picker_title))
        val pad = (16 * density).toInt()

        val clearButton = plainIconButton(R.drawable.ic_close, getString(R.string.action_clear_search)) {
            searchField.text.clear()
        }.apply { visibility = View.INVISIBLE }
        searchField = EditText(this).apply {
            hint = getString(R.string.icon_picker_search_hint)
            textSize = 16f
            isSingleLine = true
            background = null
            doAfterTextChanged {
                clearButton.visibility = if (it.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                applyFilter()
            }
        }
        scaffold.content.addView(
            searchPill(searchField, clearButton),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.setMargins(pad, pad * 3 / 4, pad, pad / 2)
            },
        )

        statusView = TextView(this).apply {
            text = getString(R.string.icon_picker_loading)
            textSize = 14f
            alpha = 0.7f
            setPadding(pad, pad / 2, pad, 0)
        }
        scaffold.content.addView(statusView, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        val gridLayout = GridLayoutManager(this, AppsPane.MIN_COLUMNS)
        val grid = RecyclerView(this).apply {
            layoutManager = gridLayout
            adapter = this@IconPickerActivity.adapter
            setPadding(pad / 2, pad / 4, pad / 2, pad)
            clipToPadding = false
            // Columns follow the width, as in the launcher grid.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val columns = maxOf(AppsPane.MIN_COLUMNS, v.width / (AppsPane.CELL_MIN_WIDTH_DP * density).toInt())
                if (columns != gridLayout.spanCount) v.post { gridLayout.spanCount = columns }
            }
        }
        scaffold.content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(scaffold.root)

        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { parse(runCatching { NativeBridge.nativeListIcons(rootfs) }.getOrNull()) }
            loaded = true
            applyFilter()
        }
    }

    private fun parse(json: String?): List<Icon> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Icon(o.getString("name"), o.optBoolean("user"))
        }
    }

    /** Case-insensitive substring match; imports first with no query. */
    private fun applyFilter() {
        val query = searchField.text.toString().trim()
        val shown = if (query.isEmpty()) {
            all.filter { it.user } + all.filterNot { it.user }
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submit(shown)
        if (!loaded) return
        statusView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        statusView.text = getString(if (all.isEmpty()) R.string.icon_picker_none else R.string.launcher_no_matches)
    }

    private fun pick(name: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NAME, name))
        finish()
    }

    private class Cell(val root: LinearLayout, val icon: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(root) {
        var name: String? = null
        var job: Job? = null
    }

    private inner class IconAdapter : RecyclerView.Adapter<Cell>() {
        private var icons: List<Icon> = emptyList()

        fun submit(icons: List<Icon>) {
            this.icons = icons
            notifyDataSetChanged()
        }

        override fun getItemCount() = icons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val cellPad = (8 * density).toInt()
            val root = LinearLayout(this@IconPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(cellPad, cellPad, cellPad, cellPad)
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val icon = ImageView(this@IconPickerActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            root.addView(icon, LinearLayout.LayoutParams(iconSizePx, iconSizePx).also { it.bottomMargin = cellPad * 3 / 4 })
            val label = TextView(this@IconPickerActivity).apply {
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return Cell(root, icon, label)
        }

        override fun onBindViewHolder(cell: Cell, position: Int) {
            val name = icons[position].name
            cell.label.text = name
            cell.root.contentDescription = name
            cell.root.setOnClickListener { pick(name) }
            cell.job?.cancel()
            cell.name = name
            resolved[name]?.let {
                iconLoader.load(it, cell.icon, R.drawable.ic_app_fallback)
                return
            }
            // Clear the recycled cell's old icon while this one resolves.
            cell.icon.tag = null
            cell.icon.setImageDrawable(null)
            cell.job = lifecycleScope.launch {
                val path = withContext(resolveDispatcher) {
                    runCatching { NativeBridge.nativeResolveIcon(rootfs, name) }.getOrDefault("")
                }
                resolved[name] = path
                if (cell.name == name) iconLoader.load(path, cell.icon, R.drawable.ic_app_fallback)
            }
        }
    }

    companion object {
        /** Installation id whose rootfs icons are listed. */
        const val EXTRA_ID = "id"

        /** Result: the chosen icon name. */
        const val EXTRA_NAME = "name"

        private const val RESOLVE_PARALLELISM = 4
    }
}
