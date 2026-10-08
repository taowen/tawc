package me.phie.tawc.install

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ops.OperationsRegistry
import me.phie.tawc.ui.destructiveButton
import me.phie.tawc.ui.plainIconButton
import me.phie.tawc.ui.tawcButtonSizePx
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import java.text.DateFormat
import java.util.Date

/**
 * Per-installation detail column, shared by [DistroInfoActivity] and
 * the home screen's info pane (a distro that isn't READY). Shows
 * label/distro/arch/method/source URL/installed-at/full rootfs path, an
 * async `du -sk`-via-su size probe, and the (red, destructive) Delete
 * button (Are-You-Sure dialog → [InstallationService.startUninstall] +
 * [LogScreenActivity] for the live progress view). The state row links
 * to the live op log while installing/uninstalling. Size lives here
 * (not on the home list) so the probe only runs where it's shown.
 *
 * The owner calls [render] on every resume (an op may have flipped the
 * state meanwhile) and [stop] on pause.
 */
class DistroInfoView(private val activity: AppCompatActivity) {

    private val store = InstallationStore(activity)
    private var targetId: String = ""
    private lateinit var sizeValue: TextView
    private var sizeScope: CoroutineScope? = null

    /** The content column; fills its parent (Delete sits at the bottom). */
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    /** Re-render for [installation] and start the size probe. */
    fun render(installation: Installation) {
        targetId = installation.id
        renderContent(installation)
        if (canProbeSize(installation)) startSizeProbe()
    }

    fun stop() {
        sizeScope?.cancel()
        sizeScope = null
    }

    private fun renderContent(installation: Installation) {
        val resolvedDistro: Distro? = DistroRegistry.forInstallation(installation)

        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val content = view
        content.removeAllViews()

        content.addView(
            infoRow(getString(R.string.distro_info_row_label), DistroRegistry.displayLabel(installation)),
            rowLp(pad),
        )
        content.addView(
            infoRow(getString(R.string.distro_info_row_distro), resolvedDistro?.displayName ?: installation.distro),
            rowLp(pad),
        )
        content.addView(
            infoRow(getString(R.string.distro_info_row_architecture), resolvedDistro?.linuxArch ?: installation.arch),
            rowLp(pad),
        )
        content.addView(infoRow(getString(R.string.distro_info_row_method), installation.method), rowLp(pad))
        content.addView(
            infoRow(getString(R.string.distro_info_row_bootstrap), installation.bootstrapFlavor),
            rowLp(pad),
        )
        content.addView(stateRow(installation), rowLp(pad))
        if (installation.failure != null) {
            content.addView(infoRow(getString(R.string.distro_info_row_failure), installation.failure), rowLp(pad))
        }
        content.addView(infoRow(getString(R.string.distro_info_row_source), installation.sourceUrl), rowLp(pad))
        content.addView(
            infoRow(
                getString(R.string.distro_info_row_installed),
                // 0 means "never recorded" (legacy record or corrupt-
                // metadata marker), not Jan 1 1970.
                if (installation.installedAtMillis > 0) {
                    DateFormat.getDateTimeInstance().format(Date(installation.installedAtMillis))
                } else getString(R.string.distro_info_unknown),
            ),
            rowLp(pad),
        )
        content.addView(
            infoRow(
                getString(R.string.distro_info_row_app_version_at_install),
                if (installation.installedAtAppVersionCode > 0) {
                    installation.installedAtAppVersionCode.toString()
                } else getString(R.string.distro_info_unknown),
            ),
            rowLp(pad),
        )
        installation.importedAtMillis?.let {
            content.addView(
                infoRow(
                    getString(R.string.distro_info_row_imported),
                    DateFormat.getDateTimeInstance().format(Date(it)) +
                        (installation.importedFromPackage?.let { p -> " ($p)" } ?: ""),
                ),
                rowLp(pad),
            )
        }
        val rootfsPath = store.rootfsDir(installation.id).absolutePath
        val rootfsRow = infoRow(getString(R.string.distro_info_row_rootfs_path), rootfsPath)
        rootfsRow.gravity = android.view.Gravity.CENTER_VERTICAL
        rootfsRow.addView(
            copyButton(getString(R.string.action_copy_rootfs_path), rootfsPath),
            LinearLayout.LayoutParams(activity.tawcButtonSizePx(), activity.tawcButtonSizePx()),
        )
        content.addView(rootfsRow, rowLp(pad))
        if (installation.method == TawcrootMethod.KEY && AllFilesAccess.declared(activity)) {
            content.addView(
                infoRow(
                    getString(R.string.distro_info_row_external_binds),
                    installation.externalBinds.size.toString(),
                ),
                rowLp(pad),
            )
        }
        sizeValue = TextView(activity).apply {
            text = if (canProbeSize(installation)) {
                getString(R.string.distro_info_computing)
            } else {
                getString(R.string.distro_info_size_unavailable)
            }
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        content.addView(infoRowWithValue(getString(R.string.distro_info_row_size), sizeValue), rowLp(pad))

        // Push the action buttons to the bottom with a flexible spacer
        // so they don't crowd the info rows.
        content.addView(
            View(activity),
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        if (installation.state == Installation.State.READY && installation.method == TawcrootMethod.KEY) {
            content.addView(
                activity.tonalButton(getString(R.string.action_export)) { confirmExport(installation) },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
            )
        }
        content.addView(
            activity.destructiveButton(getString(R.string.action_delete)) { confirmUninstall(installation) },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    /** Export confirm with the optional delete-after (turns the action
     *  red). Continue → file picker. */
    private fun confirmExport(installation: Installation) {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val deleteAfter = CheckBox(activity).apply { text = getString(R.string.export_dialog_delete_after) }
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(deleteAfter)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(getString(R.string.export_dialog_title, renderDistroLabel(installation)))
            .setMessage(getString(R.string.export_dialog_message))
            .setView(box)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.export_dialog_continue)) { _, _ ->
                activity.startActivity(ExportActivity.intentFor(activity, installation.id, deleteAfter.isChecked))
            }
            .show()
        // Neutral Cancel, as on the Delete dialog.
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let { btn ->
            btn.setTextColor(
                MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val normalColor = positive?.textColors
        deleteAfter.setOnCheckedChangeListener { _, checked ->
            positive?.text = getString(
                if (checked) R.string.export_dialog_export_delete else R.string.export_dialog_continue,
            )
            if (checked) positive?.setTextColor(activity.getColor(R.color.tawc_danger))
            else normalColor?.let { positive?.setTextColor(it) }
        }
    }

    private fun confirmUninstall(installation: Installation) {
        val name = renderDistroLabel(installation)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(getString(R.string.distro_info_delete_title, name))
            .setMessage(
                getString(R.string.distro_info_delete_message, store.rootfsDir(installation.id).absolutePath)
            )
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                // The dialog "Delete" press is the user's confirmation;
                // start the uninstall directly via the service helper
                // and open LogScreenActivity to view it. No intent-
                // extras contract — the service is the single mutation
                // surface.
                InstallationService.startUninstall(activity, installation.id)
                activity.startActivity(LogScreenActivity.intentFor(activity, "uninstall:${installation.id}"))
            }
            .show()
        // Tint the destructive action red so it pops, and the Cancel
        // neutral so it doesn't compete with it. Default Material3 uses
        // colorPrimary for both, which made Cancel look like the
        // recommended path next to a red Delete.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(activity.getColor(R.color.tawc_danger))
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let { btn ->
            btn.setTextColor(
                MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
    }

    private fun startSizeProbe() {
        sizeScope?.cancel()
        val cs = CoroutineScope(Dispatchers.Main)
        sizeScope = cs
        sizeValue.text = getString(R.string.distro_info_computing)
        cs.launch {
            // `runInterruptible` maps coroutine cancellation onto thread
            // interrupt; Su.run catches that and `destroyForcibly`s the
            // child `su` so a backgrounded `du -sk` doesn't keep
            // pounding storage after the user leaves this screen.
            val bytes = runInterruptible(Dispatchers.IO) { store.computeSizeBytes(targetId) }
            sizeValue.text = when {
                bytes < 0 -> getString(R.string.distro_info_unknown)
                else -> Formatter.formatFileSize(activity, bytes)
            }
        }
    }

    private fun renderDistroLabel(installation: Installation): String =
        DistroRegistry.displayLabel(installation)

    /**
     * Borderless clipboard icon button. Skips the "copied" toast on
     * T+ where the system already shows its own clipboard overlay.
     */
    private fun copyButton(description: String, text: String): View =
        activity.plainIconButton(R.drawable.ic_content_copy, description) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(description, text))
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(activity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * State row; while an op runs it links to that op's live log
     * (FAILED's text is the failure row — there is no completed-runs
     * history, notes/log-screen.md).
     */
    private fun stateRow(installation: Installation): LinearLayout {
        val op = when (installation.state) {
            // An import is an install variant with its own op.
            Installation.State.INSTALLING ->
                if (OperationsRegistry.get("import:${installation.id}") != null) "import" else "install"
            Installation.State.UNINSTALLING -> "uninstall"
            else -> null
        }
        val label = stateLabel(installation.state)
        val row = infoRow(
            getString(R.string.distro_info_row_state),
            if (op == null) label else getString(R.string.home_state_view_log, label),
        )
        if (op != null) {
            val value = row.getChildAt(1) as TextView
            value.setTextIsSelectable(false)
            value.setTextColor(activity.getColor(R.color.tawc_accent))
            val bg = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
            row.setBackgroundResource(bg.resourceId)
            row.setOnClickListener {
                activity.startActivity(LogScreenActivity.intentFor(activity, "$op:${installation.id}"))
            }
        }
        return row
    }

    private fun stateLabel(state: Installation.State): String =
        when (state) {
            Installation.State.READY -> getString(R.string.install_state_ready)
            Installation.State.INSTALLING -> getString(R.string.install_state_installing)
            Installation.State.UNINSTALLING -> getString(R.string.install_state_uninstalling)
            Installation.State.FAILED -> getString(R.string.install_state_failed)
            Installation.State.CORRUPT -> getString(R.string.install_state_corrupt)
        }

    /**
     * READY, FAILED, and CORRUPT slots all have stable on-disk content
     * worth measuring — the broken states in particular are exactly
     * when the user wants to know how much space the slot is sitting
     * on. INSTALLING / UNINSTALLING are skipped because `du -sk` would
     * fight the installer for IO and the number changes faster than we
     * can render it.
     */
    private fun canProbeSize(installation: Installation): Boolean =
        installation.state == Installation.State.READY ||
            installation.state == Installation.State.FAILED ||
            installation.state == Installation.State.CORRUPT

    private fun infoRow(label: String, value: String): LinearLayout =
        infoRowWithValue(label, TextView(activity).apply {
            text = value
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

    private fun infoRowWithValue(label: String, valueView: TextView): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val l = TextView(activity).apply { text = label; textSize = 14f }
        row.addView(l, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        row.addView(valueView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun getString(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun rowLp(pad: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = pad / 2 }
}
