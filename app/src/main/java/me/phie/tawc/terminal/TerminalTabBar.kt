package me.phie.tawc.terminal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.phie.tawc.R

/**
 * Top row of the home screen's [TerminalPane]:
 * `[≡][ tabs… ][+] …… [⋮]`. The tab strip scrolls horizontally; `+`
 * sits outside it, right after the last tab, and stays in place once
 * the tabs overflow. `≡` and `⋮` are pinned at the edges. Each tab is an ellipsized label plus a small `×` close
 * button. While the pane's shell is pending ([setPendingLabel]) the
 * strip and `+` are replaced by the distro label.
 *
 * Imperative custom view, no XML layout (app style). Indices map 1:1
 * to `TerminalSessions.list(distroId)` — the bar never reorders; the
 * pane drives all mutations and supplies the callbacks. Click
 * handlers resolve the index at click time (`indexOfChild`) so
 * removals don't stale captured positions.
 *
 * Fixed dark palette regardless of day/night theme: the bar sits
 * against the always-black terminal/extra-keys surface, so
 * theme-following tonal colors would clash in light mode.
 */
internal class TerminalTabBar(context: Context) : LinearLayout(context) {

    var onTabSelected: (Int) -> Unit = {}
    var onTabCloseClicked: (Int) -> Unit = {}
    var onNewTabClicked: () -> Unit = {}
    var onDrawerClicked: () -> Unit = {}
    var onMenuClicked: (View) -> Unit = {}

    private val scroller: HorizontalScrollView
    private val tabsRow: LinearLayout
    private val strip: LinearLayout
    private val pendingLabel: TextView
    private val newTab: View
    private var selectedIndex = -1

    init {
        orientation = HORIZONTAL
        setBackgroundColor(BAR_BG)

        addView(
            barButton(R.drawable.ic_menu, R.string.action_open_drawer) { onDrawerClicked() },
            LayoutParams(dp(NEW_TAB_WIDTH_DP), MATCH_PARENT),
        )

        strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        newTab = barButton(R.drawable.ic_add, R.string.terminal_new_tab) { onNewTabClicked() }
        tabsRow = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Measure contents as wrap-content so `+` hugs the tabs; the
                // weighted scroller gives back any overflow. Still claim the
                // full width so `⋮` stays pinned right.
                val width = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
                setMeasuredDimension(width, measuredHeight)
            }
        }.apply {
            orientation = HORIZONTAL
            addView(scroller, LayoutParams(WRAP_CONTENT, MATCH_PARENT, 1f))
            addView(newTab, LayoutParams(dp(NEW_TAB_WIDTH_DP), MATCH_PARENT))
        }
        addView(tabsRow, LayoutParams(0, MATCH_PARENT, 1f))
        pendingLabel = TextView(context).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            textSize = PENDING_TEXT_SP
            setTextColor(FG_SELECTED)
            setPadding(dp(TAB_PAD_H_DP) / 2, 0, 0, 0)
            visibility = GONE
        }
        addView(pendingLabel, LayoutParams(0, MATCH_PARENT, 1f))

        lateinit var menu: View
        menu = barButton(R.drawable.ic_more_vert, R.string.home_menu_description) { onMenuClicked(menu) }
        addView(menu, LayoutParams(dp(NEW_TAB_WIDTH_DP), MATCH_PARENT))
    }

    private fun barButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(FG_UNSELECTED)
            setBackgroundColor(Color.TRANSPARENT)
            // ImageView's FIT_CENTER upscales the icon to the button
            // bounds; pad it back down to a small glyph.
            setPadding(dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP))
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
        }

    /** Show [label] instead of the tabs (pending shell), or the tabs
     *  again when null. */
    fun setPendingLabel(label: CharSequence?) {
        pendingLabel.text = label
        pendingLabel.visibility = if (label != null) VISIBLE else GONE
        tabsRow.visibility = if (label != null) GONE else VISIBLE
    }

    /** Append a tab and scroll it into view; returns its index. */
    fun addTab(label: CharSequence): Int {
        val tab = buildTab(label)
        strip.addView(tab, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        scrollIntoView(tab)
        return tabCount() - 1
    }

    fun removeTab(index: Int) {
        strip.removeViewAt(index)
        if (selectedIndex >= tabCount()) selectedIndex = tabCount() - 1
    }

    private fun tabCount(): Int = strip.childCount

    /** Highlight [index] and scroll it into view. */
    fun setSelected(index: Int) {
        selectedIndex = index
        for (i in 0 until tabCount()) {
            val tab = strip.getChildAt(i) as LinearLayout
            val selected = i == index
            tab.background = if (selected) selectedBackground() else null
            (tab.getChildAt(0) as TextView)
                .setTextColor(if (selected) FG_SELECTED else FG_UNSELECTED)
            (tab.getChildAt(1) as ImageView)
                .imageTintList = ColorStateList.valueOf(if (selected) FG_SELECTED else FG_UNSELECTED)
        }
        if (index in 0 until tabCount()) scrollIntoView(strip.getChildAt(index))
    }

    fun setLabel(index: Int, label: CharSequence) {
        if (index !in 0 until tabCount()) return
        val tab = strip.getChildAt(index) as LinearLayout
        (tab.getChildAt(0) as TextView).text = label
    }

    /** Faint fill with an accent strip along the top. */
    private fun selectedBackground(): LayerDrawable =
        LayerDrawable(arrayOf(ColorDrawable(TAB_BG_SELECTED), ColorDrawable(context.getColor(R.color.tawc_accent))))
            .apply {
                setLayerGravity(1, Gravity.TOP)
                setLayerHeight(1, dp(SELECTED_BORDER_DP))
            }

    private fun buildTab(label: CharSequence): View {
        val tab = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(TAB_PAD_H_DP), 0, 0, 0)
        }
        val text = TextView(context).apply {
            this.text = label
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(TAB_MAX_LABEL_DP)
            textSize = TAB_TEXT_SP
            setTextColor(FG_UNSELECTED)
        }
        tab.addView(text, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(FG_UNSELECTED)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP))
            contentDescription = context.getString(R.string.terminal_close_tab)
            setOnClickListener { onTabCloseClicked(strip.indexOfChild(tab)) }
        }
        tab.addView(close, LayoutParams(dp(CLOSE_WIDTH_DP), MATCH_PARENT))
        tab.setOnClickListener { onTabSelected(strip.indexOfChild(tab)) }
        return tab
    }

    private fun scrollIntoView(tab: View) {
        // Post: a freshly added/restyled tab has no geometry until the
        // next layout pass.
        scroller.post {
            tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), false)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BAR_BG = Color.parseColor("#1A1A1A")
        val TAB_BG_SELECTED = Color.parseColor("#2C2C2C")
        val FG_SELECTED = Color.parseColor("#FFFFFF")
        val FG_UNSELECTED = Color.parseColor("#9E9E9E")
        const val TAB_TEXT_SP = 13f
        const val SELECTED_BORDER_DP = 2
        const val PENDING_TEXT_SP = 15f
        const val TAB_MAX_LABEL_DP = 180
        const val TAB_PAD_H_DP = 12
        const val CLOSE_WIDTH_DP = 36
        const val NEW_TAB_WIDTH_DP = 44
        const val ICON_PAD_DP = 10
    }
}
