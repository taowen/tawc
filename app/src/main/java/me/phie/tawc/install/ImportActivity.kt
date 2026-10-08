package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.HomePane
import me.phie.tawc.OpenDistro
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.Scaffold
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.verticalLp
import java.text.DateFormat
import java.util.Date

/**
 * Import form: picks an export archive (SAF `OPEN_DOCUMENT`), reads
 * just its two header entries, and shows what's in it plus a Label
 * field. Import → [InstallationService.startImport]. Opened from the
 * install form's "Import from tarball". Settings carried in the archive
 * (ando, binds, hidden entries) aren't listed; binds that would fail
 * here get a warning.
 */
class ImportActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private lateinit var scaffold: Scaffold
    private var uri: Uri? = null
    private var labelText: String? = null

    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) {
            finish()
        } else {
            uri = picked
            load(picked)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold = buildChildScreen(getString(R.string.title_import_distro))
        setContentView(scaffold.root)
        uri = savedInstanceState?.getString(KEY_URI)?.let(Uri::parse)
        labelText = savedInstanceState?.getString(KEY_LABEL)
        val u = uri
        when {
            u != null -> load(u)
            savedInstanceState == null -> open.launch(arrayOf("*/*"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        uri?.let { outState.putString(KEY_URI, it.toString()) }
        labelText?.let { outState.putString(KEY_LABEL, it) }
    }

    private fun load(u: Uri) {
        showMessage(getString(R.string.import_reading))
        lifecycleScope.launch {
            val header = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(u)?.use { DistroImporter.Reader(it).readHeader() }
                        ?: throw java.io.IOException("can't open the file")
                }
            }
            header.fold(
                onSuccess = { showForm(u, it) },
                onFailure = { showMessage(getString(R.string.import_unreadable, it.message ?: it.javaClass.simpleName)) },
            )
        }
    }

    private fun showMessage(text: String) {
        scaffold.content.removeAllViews()
        val pad = pad()
        scaffold.content.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun showForm(u: Uri, h: DistroImporter.Header) {
        val m = h.manifest
        val meta = h.metadata
        val pad = pad()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val distro = DistroRegistry.forInstallation(meta)

        fun row(label: String, value: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply { text = label; textSize = 14f },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad })
            r.addView(TextView(this).apply {
                text = value
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            form.addView(r, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        }

        fun note(text: String, error: Boolean = false) {
            form.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                if (error) {
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
                } else {
                    alpha = 0.75f
                }
            }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        }

        row(getString(R.string.import_row_distro),
            "${distro?.displayName ?: meta.distro} (${distro?.linuxArch ?: meta.arch})")
        row(getString(R.string.import_row_original_label), DistroRegistry.displayLabel(meta))
        if (m.createdAtMillis > 0) {
            row(getString(R.string.import_row_exported), DateFormat.getDateTimeInstance().format(Date(m.createdAtMillis)))
        }
        row(getString(R.string.import_row_from_app), "${m.sourcePackage} v${m.appVersionName}")
        row(getString(R.string.import_row_size), Formatter.formatFileSize(this, m.uncompressedBytes))

        val problem = DistroImporter.incompatibility(h, DistroImporter::hostRunnable)

        // Label → id, validated like the install form.
        form.addView(TextView(this).apply { text = getString(R.string.install_label_label); textSize = 14f },
            verticalLp(MATCH_PARENT, WRAP_CONTENT))
        val labelField = EditText(this).apply {
            isSingleLine = true
            setText(labelText ?: DistroRegistry.displayLabel(meta))
        }
        form.addView(labelField, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        val location = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        form.addView(location, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Carried settings aren't listed; only binds that won't work here
        // get a warning.
        val carried = DistroImporter.rewrite(
            meta, "x", null, m.sourcePackage, 0L, AllFilesAccess.declared(this),
        )
        val dropped = meta.externalBinds.size - carried.externalBinds.size
        if (dropped > 0) note(getString(R.string.import_binds_dropped, dropped))
        if (AllFilesAccess.requiresGrant(carried.externalBinds) && !AllFilesAccess.granted()) {
            note(getString(R.string.import_binds_grant), error = true)
        }
        carried.externalBinds.firstOrNull { AllFilesAccess.hostDirVerifiablyMissing(it.hostPath) }?.let {
            note(getString(R.string.import_binds_missing, it.hostPath), error = true)
        }

        // Advisory: the size comes from the exporter's pre-walk.
        val free = runCatching { StatFs(store.baseDir.apply { mkdirs() }.path).availableBytes }.getOrNull()
        val need = m.uncompressedBytes + m.uncompressedBytes / 10
        if (free != null && free < need) {
            note(getString(R.string.import_space_warning,
                Formatter.formatFileSize(this, free), Formatter.formatFileSize(this, need)), error = true)
        }
        if (problem != null) note(getString(R.string.import_unreadable, problem), error = true)

        lateinit var button: MaterialButton
        var resolvedId: String? = null
        fun revalidate() {
            labelText = labelField.text.toString()
            val check = LabelValidation.check(this, store, labelField.text.toString())
            resolvedId = check.id
            location.text = check.message
            location.setTextColor(MaterialColors.getColor(location,
                if (check.id == null) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant))
            button.isEnabled = check.id != null && problem == null
        }
        button = primaryButton(getString(R.string.action_import)) {
            val id = resolvedId ?: return@primaryButton
            runCatching {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            InstallationService.startImport(this, id, labelField.text.toString().trim(), u, null)
            OpenDistro.set(id)
            Settings.homePane = HomePane.TERMINAL
            startActivity(LogScreenActivity.intentFor(this, "import:$id"))
            // Tells the install form (our caller) to close too.
            setResult(RESULT_OK)
            finish()
        }
        form.addView(button, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        labelField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = revalidate()
        })
        revalidate()

        scaffold.content.removeAllViews()
        scaffold.content.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(form, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun pad(): Int = (16 * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_URI = "tawc.import.uri"
        private const val KEY_LABEL = "tawc.import.label"
    }
}
