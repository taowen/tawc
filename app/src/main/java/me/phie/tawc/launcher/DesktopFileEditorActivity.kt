package me.phie.tawc.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.util.atomicWriteText
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import java.io.File
import java.io.IOException

/**
 * Create/edit/delete a personal `.desktop` entry in a rootfs's managed
 * dir ([DesktopEntryFile.MANAGED_SUBDIR]). Makes personal launchers,
 * not production `.desktop` files: Exec (required) + Name (defaults to Exec), Icon
 * (freeform `Icon=` value with a live preview, picked from the distro's
 * icons by [IconPickerActivity] or imported by [IconImport]), Terminal
 * checkbox — no locale keys, actions,
 * field codes or extra groups. `Comment=` is read and written back but
 * not shown in the form. Saving writes the file wholesale via
 * [DesktopEntryFile.serialize]; a foreign file (keys/groups outside
 * that set) loads its known keys and shows a warning that saving drops
 * the rest.
 *
 * Launched by [AppsPane] (via MainActivity) for result (RESULT_OK = the rootfs
 * changed, rescan). Writes are plain app-uid file I/O, so entry points
 * are hidden for chroot installs (root-owned rootfs — see
 * notes/launcher.md "Access model").
 */
class DesktopFileEditorActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }

    private lateinit var nameField: EditText
    private lateinit var execField: EditText
    private lateinit var iconField: EditText
    private lateinit var terminalCheckbox: CheckBox
    private lateinit var saveButton: MaterialButton
    private lateinit var iconPreview: ImageView
    private lateinit var rootfs: File
    private var installId = ""

    private val iconLoader by lazy {
        IconLoader(lifecycleScope, (PREVIEW_DP * resources.displayMetrics.density).toInt())
    }
    private var previewJob: Job? = null

    private val pickIcon = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(IconPickerActivity.EXTRA_NAME)
            ?.takeIf { result.resultCode == RESULT_OK }
            ?.let { setIcon(it) }
    }

    private val loadIcon = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importIcon(it) }
    }

    /** `Comment=` from the loaded file. Not exposed in the form (a
     *  personal launcher doesn't need a description), but written back
     *  on save so editing doesn't clobber an existing value. */
    private var existingComment = ""

    /** The file being edited, or null when creating a new entry. */
    private var editFile: File? = null
    private lateinit var managedDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installId = intent?.getStringExtra(EXTRA_ID) ?: ""
        if (store.load(installId) == null) {
            finish()
            return
        }
        rootfs = store.rootfsDir(installId)
        managedDir = DesktopEntryFile.managedDir(rootfs)

        val path = intent?.getStringExtra(EXTRA_PATH)
        // New entries default to Terminal=true: hand-made entries are
        // usually CLI scripts. Editing keeps the file's value.
        var loaded = DesktopEntryFile.Parsed(DesktopEntryFile.Draft(terminal = true), false)
        if (path != null) {
            // Only managed files are editable; the launcher gates the
            // Edit action the same way, so this is just belt-and-braces
            // against a stale/forged intent.
            val f = File(path)
            if (!DesktopEntryFile.isManaged(path, rootfs) || !f.isFile) {
                finish()
                return
            }
            editFile = f
            // A failed read must not fall through to the empty default
            // draft: the form would open blank with no foreign-content
            // warning, and Save would rewrite the file wholesale from
            // nothing — silently wiping the entry. Refuse to edit what
            // we couldn't read.
            val bytes = try {
                f.readBytes()
            } catch (e: IOException) {
                Toast.makeText(this, getString(R.string.editor_load_failed, e.message), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val text = String(bytes)
            val parsed = DesktopEntryFile.parse(text)
            // String() decodes malformed UTF-8 to U+FFFD without ever
            // throwing, so a non-UTF-8 file would round-trip with
            // silently corrupted values. Valid UTF-8 re-encodes byte-
            // identically; anything else gets the foreign-content
            // warning so the user knows saving rewrites the file.
            loaded = if (text.toByteArray().contentEquals(bytes)) parsed
            else parsed.copy(hasForeignContent = true)
        }

        val title = getString(
            if (editFile == null) R.string.editor_title_new else R.string.editor_title_edit,
        )
        val scaffold = buildChildScreen(title)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (loaded.hasForeignContent) {
            form.addView(
                TextView(this).apply {
                    text = getString(R.string.editor_foreign_warning)
                    textSize = 13f
                    setTextColor(getColor(R.color.tawc_danger))
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
            )
        }
        existingComment = loaded.draft.comment
        execField = addField(form, R.string.editor_field_exec, loaded.draft.exec, pad)
        nameField = addField(form, R.string.editor_field_name, loaded.draft.name, pad)
        iconField = addIconRow(form, loaded.draft.icon, pad)
        terminalCheckbox = CheckBox(this).apply {
            text = getString(R.string.editor_field_terminal)
            isChecked = loaded.draft.terminal
            // The fallback glyph follows Terminal.
            setOnCheckedChangeListener { _, _ -> refreshPreview(debounce = false) }
        }
        form.addView(terminalCheckbox, verticalLp(WRAP_CONTENT, WRAP_CONTENT, bottomMargin = pad))

        saveButton = primaryButton(getString(R.string.editor_save)) { save() }
        form.addView(saveButton, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        if (editFile != null) {
            // Delete lives in the toolbar as a trash action, not a
            // big destructive button next to Save; it still confirms.
            // Tinted manually — MaterialToolbar doesn't tint menu-item
            // icons, and the vector's own fill is white.
            scaffold.toolbar.menu.add(getString(R.string.editor_delete)).apply {
                icon = AppCompatResources
                    .getDrawable(this@DesktopFileEditorActivity, R.drawable.ic_delete)
                    ?.mutate()
                    ?.apply { setTint(getColor(R.color.tawc_danger)) }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { confirmDelete(); true }
            }
        }

        scaffold.content.addView(
            ScrollView(this).apply { addView(form) },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(scaffold.root)
        revalidate()
        refreshPreview(debounce = false)
    }

    /**
     * ```
     * Icon
     * [preview]  [ field ............ ✕ ]
     *            [ Select ]  [ Load ]
     * ```
     */
    private fun addIconRow(form: LinearLayout, value: String, pad: Int): EditText {
        form.addView(
            TextView(this).apply { text = getString(R.string.editor_field_icon); textSize = 14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val previewPx = (PREVIEW_DP * resources.displayMetrics.density).toInt()
        iconPreview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(iconPreview, LinearLayout.LayoutParams(previewPx, previewPx).also {
            it.marginEnd = pad
            it.topMargin = pad / 4
        })

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val field = TextInputEditText(this).apply {
            setText(value)
            isSingleLine = true
            // Icon names aren't words; no spellcheck squiggles.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // As tall as the ✕, so the row doesn't jump when it shows.
            minHeight = (48 * resources.displayMetrics.density).toInt()
            doAfterTextChanged { revalidate(); refreshPreview(debounce = true) }
        }
        // Box-less, so it reads like the plain fields above; the layout
        // is only here for the clear (✕) end icon.
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            isHintEnabled = false
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            addView(field)
        }
        column.addView(layout, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(tonalButton(getString(R.string.editor_icon_select)) {
            pickIcon.launch(Intent(this, IconPickerActivity::class.java).putExtra(IconPickerActivity.EXTRA_ID, installId))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = pad / 2 })
        buttons.addView(tonalButton(getString(R.string.editor_icon_load)) {
            loadIcon.launch(arrayOf("image/*"))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        column.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        form.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        return field
    }

    /** Show what the launcher grid would draw for the current Icon
     *  value: the resolved PNG, else the same fallback glyph. */
    private fun refreshPreview(debounce: Boolean) {
        if (!::iconPreview.isInitialized || !::terminalCheckbox.isInitialized) return
        val value = iconField.text.toString()
        val fallback = if (terminalCheckbox.isChecked) R.drawable.ic_terminal_fallback else R.drawable.ic_app_fallback
        val rootfsPath = rootfs.absolutePath
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            if (debounce) delay(PREVIEW_DEBOUNCE_MS)
            val path = if (value.isBlank()) "" else withContext(Dispatchers.IO) {
                runCatching { NativeBridge.nativeResolveIcon(rootfsPath, value) }.getOrDefault("")
            }
            iconLoader.load(path, iconPreview, fallback)
        }
    }

    private fun setIcon(value: String) {
        iconField.setText(value)
        iconField.setSelection(value.length)
    }

    /** Copy a picked image into the distro ([IconImport]) and put its
     *  name in the field. Failure leaves the field untouched. */
    private fun importIcon(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { IconImport.import(this@DesktopFileEditorActivity, uri, rootfs) }
            }
            name.onSuccess { setIcon(it) }.onFailure {
                Toast.makeText(
                    this@DesktopFileEditorActivity,
                    getString(R.string.editor_icon_import_failed, it.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Label + single-line EditText, InstallActivity's form idiom. */
    private fun addField(form: LinearLayout, labelRes: Int, value: String, pad: Int): EditText {
        form.addView(
            TextView(this).apply { text = getString(labelRes); textSize = 14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        val field = EditText(this).apply {
            setText(value)
            isSingleLine = true
            doAfterTextChanged { revalidate() }
        }
        form.addView(field, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        return field
    }

    /** Name defaults to the command when left blank; the field shows it
     *  as the hint. */
    private fun draft() = DesktopEntryFile.Draft(
        name = nameField.text.toString().ifBlank { execField.text.toString().trim() },
        exec = execField.text.toString(),
        comment = existingComment,
        icon = iconField.text.toString(),
        terminal = terminalCheckbox.isChecked,
    )

    private fun revalidate() {
        if (!::saveButton.isInitialized) return
        nameField.hint = execField.text.toString().trim()
        saveButton.isEnabled = draft().exec.isNotBlank()
    }

    /**
     * Write the file wholesale. New entries pick a fresh slug filename
     * ([DesktopEntryFile.newFile]); edits keep the existing one — the
     * filename is the entry id, which pins and hidden-state reference.
     */
    private fun save() {
        val file = editFile ?: DesktopEntryFile.newFile(managedDir, draft().name)
        try {
            managedDir.mkdirs()
            // Atomic: the filename is the entry id (pins and hidden-
            // state reference it), so a truncated file from a mid-write
            // kill would quietly break existing references.
            atomicWriteText(file, DesktopEntryFile.serialize(draft()))
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.editor_save_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDelete() {
        val file = editFile ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_delete_confirm, nameField.text.toString().ifBlank { file.name }))
            .setPositiveButton(R.string.editor_delete) { _, _ ->
                if (file.delete() || !file.exists()) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, R.string.editor_delete_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** Installation id whose rootfs hosts the managed dir. */
        const val EXTRA_ID = "id"

        /** Absolute path of the `.desktop` file to edit; absent = new entry. */
        const val EXTRA_PATH = "path"

        /** Icon preview edge, close to the launcher grid's icon. */
        private const val PREVIEW_DP = 48f

        private const val PREVIEW_DEBOUNCE_MS = 250L
    }
}
