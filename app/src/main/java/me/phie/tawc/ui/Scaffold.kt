package me.phie.tawc.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import me.phie.tawc.R

/**
 * Helpers for building screens that share the app's chrome — a top bar
 * with the platform-standard back/up affordance plus a vertically
 * stacked content area — without duplicating boilerplate across
 * activities.
 *
 * Layouts are still built imperatively in Kotlin; these helpers just
 * install the toolbar and hand back the inner column so the caller can
 * keep using `addView(...)` like before.
 */

data class Scaffold(
    val root: LinearLayout,
    val toolbar: MaterialToolbar,
    val content: LinearLayout,
)

/**
 * Build a child screen (Install, Uninstall, Distro info, …): toolbar
 * with the up arrow + a content column. Tapping the up arrow calls
 * [AppCompatActivity.finish], i.e. the screen pops back to its parent.
 */
fun AppCompatActivity.buildChildScreen(title: CharSequence): Scaffold =
    buildScreenInternal(title, withUp = true)

/** Top-level screen (Home): toolbar with title only, no up arrow. */
fun AppCompatActivity.buildHomeScreen(title: CharSequence): Scaffold =
    buildScreenInternal(title, withUp = false).also {
        it.toolbar.setTitleCentered(true)
        it.toolbar.setTitleTextAppearance(this, R.style.TextAppearance_Tawc_HomeTitle)
    }

private fun AppCompatActivity.buildScreenInternal(title: CharSequence, withUp: Boolean): Scaffold {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
    root.applySystemBarPadding()

    val toolbar = MaterialToolbar(this).apply {
        this.title = title
        if (withUp) {
            setNavigationIcon(R.drawable.ic_arrow_back)
            // Use the platform's "Navigate up" string so TalkBack reads
            // the same affordance users already know from other apps.
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
    }
    root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    val pad = (16 * resources.displayMetrics.density).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    return Scaffold(root, toolbar, content)
}

private fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

// MaterialButton's default fully-rounded "pill" looks slick but reads
// as a chip more than an action; the app prefers near-square buttons
// with just enough corner softening to not feel sharp. 6dp on the
// 48dp button height lands at "barely rounded".
private const val BUTTON_CORNER_DP = 6f

// Uniform visible height for every app button (text and icon). The
// Material default is ~48dp of layout with 6dp transparent insets top
// and bottom, which makes mixed text/icon rows look misaligned; zero
// the insets and pin minHeight instead so the painted area is the
// same everywhere. 44dp: 48 read slightly chunky next to the cards'
// text rows.
private const val BUTTON_HEIGHT_DP = 44

// Glyph edge for [plainIconButton]: what a toolbar's own up arrow
// draws at, not MaterialButton's 18dp default (which is sized to sit
// beside a label, and reads small on its own).
private const val PLAIN_ICON_SIZE_DP = 24

/**
 * The uniform button height in pixels — also the exact width/height
 * callers should give [tonalIconButton]s' layout params so the squares
 * can't be squeezed by their row (LinearLayout only guarantees
 * minHeight for exact-size children).
 */
fun Context.tawcButtonSizePx(): Int = (BUTTON_HEIGHT_DP * resources.displayMetrics.density).toInt()

private fun MaterialButton.applyTawcButtonShape() {
    val density = resources.displayMetrics.density
    insetTop = 0
    insetBottom = 0
    minHeight = (BUTTON_HEIGHT_DP * density).toInt()
    minimumHeight = minHeight
    cornerRadius = (BUTTON_CORNER_DP * density).toInt()
}

/**
 * Filled accent-colored button for primary actions (Install, Open).
 * Inherits `colorPrimary` from the theme.
 */
fun AppCompatActivity.primaryButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * Filled red button for destructive actions (Uninstall). Tinted
 * programmatically — no XML style indirection — so it's resilient
 * against future Material widget churn.
 */
fun AppCompatActivity.destructiveButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.tawc_danger))
        setTextColor(getColor(R.color.tawc_on_danger))
        iconTint = ColorStateList.valueOf(getColor(R.color.tawc_on_danger))
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * Tonal Material button for secondary / subdued actions (Manage,
 * Cancel, Task manager). Muted fill, no border, same
 * near-square corners as [primaryButton] — reads clearly as "a button,
 * but not the headline action." Context-bound so non-Activity UI
 * surfaces (e.g. `OperationLogPanel`) can use it too.
 */
fun Context.tonalButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.tawc_tonal_bg))
        setTextColor(getColor(R.color.tawc_on_tonal))
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * Square icon-only variant of [tonalButton] (e.g. the per-distro
 * gear/Terminal buttons on the home screen). Fixed
 * [BUTTON_HEIGHT_DP]-square so every icon button matches the text
 * buttons' height regardless of icon size. MaterialButton centers a
 * TEXT_START icon when there's no text and iconPadding is 0.
 * Colors default to the tonal pair; pass e.g. [R.color.tawc_accent]
 * for an accent-filled button (same pairing as the primary-styled
 * install button).
 */
fun Context.tonalIconButton(
    iconRes: Int,
    description: CharSequence,
    backgroundColor: Int = R.color.tawc_tonal_bg,
    foregroundColor: Int = R.color.tawc_on_tonal,
    iconSizeDp: Int? = null,
    onClick: () -> Unit,
): MaterialButton =
    MaterialButton(this).apply {
        icon = AppCompatResources.getDrawable(context, iconRes)
        if (iconSizeDp != null) iconSize = (iconSizeDp * resources.displayMetrics.density).toInt()
        iconPadding = 0
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        applyTawcButtonShape()
        val size = (BUTTON_HEIGHT_DP * resources.displayMetrics.density).toInt()
        minWidth = size
        minimumWidth = size
        setPadding(0, 0, 0, 0)
        contentDescription = description
        backgroundTintList = ColorStateList.valueOf(getColor(backgroundColor))
        iconTint = ColorStateList.valueOf(getColor(foregroundColor))
        setOnClickListener { onClick() }
    }

/**
 * Background-less [tonalIconButton] for chrome that sits inside
 * another surface (the launcher's back / ⋮ buttons beside the search
 * field), where a filled square would read as a second control rather
 * than part of the row. Same square footprint, no fill — only the
 * press ripple shows, so the shape is a circle (a rounded square
 * looks like a stray button once the fill is gone).
 *
 * The glyph is [PLAIN_ICON_SIZE_DP] at `?attr/colorControlNormal`,
 * i.e. what a toolbar's own up arrow draws: these buttons are the same
 * kind of chrome, so a screen's back arrow and a row's must be one
 * mark. Pass [foregroundColor] only where the colour carries meaning,
 * and [iconSizeDp] only for a glyph that reads heavier than the rest
 * at the shared size.
 */
fun Context.plainIconButton(
    iconRes: Int,
    description: CharSequence,
    foregroundColor: Int? = null,
    iconSizeDp: Int = PLAIN_ICON_SIZE_DP,
    onClick: () -> Unit,
): MaterialButton =
    tonalIconButton(
        iconRes,
        description,
        android.R.color.transparent,
        foregroundColor ?: R.color.tawc_on_tonal,
        iconSizeDp,
        onClick,
    ).apply {
        cornerRadius = tawcButtonSizePx() / 2
        if (foregroundColor == null) iconTint = controlTint()
    }

/** `?attr/colorControlNormal`, the tint the platform's own chrome uses. */
private fun Context.controlTint(): ColorStateList {
    val value = android.util.TypedValue()
    theme.resolveAttribute(androidx.appcompat.R.attr.colorControlNormal, value, true)
    return if (value.resourceId != 0) {
        AppCompatResources.getColorStateList(this, value.resourceId)
    } else {
        ColorStateList.valueOf(value.data)
    }
}

/**
 * Card / panel surface used for distro rows on the home screen, the
 * task manager's per-install group cards, the launcher's app rows, and
 * the operation log panel. Filled with [R.color.tawc_card_bg] (a
 * slight contrast against the window surface) and no stroke — the
 * fill alone is what separates the card from the background.
 */
fun Context.tawcCard(): MaterialCardView =
    MaterialCardView(this).apply {
        strokeWidth = 0
        cardElevation = 0f
        setCardBackgroundColor(getColor(R.color.tawc_card_bg))
    }

/** Convenience: vertical [LinearLayout.LayoutParams] with a bottom margin. */
fun verticalLp(width: Int, height: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height).also { it.bottomMargin = bottomMargin }
