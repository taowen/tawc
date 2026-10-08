package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.phie.tawc.OpenDistro
import me.phie.tawc.R
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.install.util.HostArch
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.Scaffold
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import java.text.DateFormat
import java.util.Date

/**
 * Import form: picks an archive (SAF `OPEN_DOCUMENT`), classifies it
 * with [DistroImporter.scan] — an export from its two header entries,
 * anything else with one cancellable headers-only pass — and shows
 * what's in it, a kind banner, warnings, and a Label field. Import →
 * [InstallationService.startImport]. Opened from the install form's
 * "Import from tarball". Settings carried in an export (ando, binds,
 * hidden entries) aren't listed; binds that would fail here get a
 * warning. Unimportable archives replace the form with the reason.
 */
class ImportActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private lateinit var scaffold: Scaffold
    private var uri: Uri? = null
    private var labelText: String? = null
    /** Scan progress posts are dropped once this is false: the last
     *  one can land after the result. Main thread only. */
    private var scanning = false

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

    /** Scan [u] off the main thread; leaving the screen cancels it. */
    private fun load(u: Uri) {
        scanning = true
        showScanning(null)
        lifecycleScope.launch {
            val scan = runCatching {
                runInterruptible(Dispatchers.IO) {
                    contentResolver.openInputStream(u)?.use { input ->
                        DistroImporter.scan(input, HostArch.primaryAbi()) { done ->
                            runOnUiThread { if (scanning) showScanning(done) }
                        }
                    } ?: throw java.io.IOException("can't open the file")
                }
            }
            scanning = false
            if (!isActive) return@launch
            scan.fold(
                onSuccess = { showForm(u, it) },
                onFailure = { showMessage(getString(R.string.import_unreadable, it.message ?: it.javaClass.simpleName)) },
            )
        }
    }

    private fun showScanning(done: Long?) {
        showMessage(
            if (done == null) getString(R.string.import_reading)
            else getString(R.string.import_scanning, Formatter.formatFileSize(this, done)),
        )
        scaffold.content.addView(tonalButton(getString(R.string.action_cancel)) { finish() },
            verticalLp(WRAP_CONTENT, WRAP_CONTENT))
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

    /** The picked document's name, for the default label. */
    private fun displayName(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun showForm(u: Uri, scan: DistroImporter.Scan) {
        val hostAbi = HostArch.primaryAbi()
        val header = scan.header
        // The settings that travel: an export's, or a damaged one's if
        // its metadata parsed.
        val meta = header?.metadata ?: scan.metadata?.takeIf { it.method == TawcrootMethod.KEY }
        val facts = scan.facts
        val known = meta != null && DistroImporter.knownDistro(meta.distro, hostAbi)
        val pad = pad()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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

        fun note(text: String, error: Boolean = false, warning: Boolean = false) {
            form.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                when {
                    error -> setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
                    warning -> setTextColor(ContextCompat.getColor(context, R.color.tawc_warning))
                    else -> alpha = 0.75f
                }
            }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        }

        // Kind banner: only a damaged export gets one; "unsupported"
        // shows in the Distro row.
        val osName = facts?.osName ?: getString(R.string.import_unknown_distro)
        val customName = if (meta != null && !known) {
            DistroRegistry.forInstallation(meta)?.displayName ?: meta.osName ?: meta.distro
        } else osName
        if (scan.kind == DistroImporter.Kind.DAMAGED_EXPORT) {
            note(getString(R.string.import_banner_damaged, scan.problems.joinToString("; ")), warning = true)
        }

        val arch = RootfsFacts.linuxArch(facts?.abi ?: meta?.arch ?: hostAbi)
        val distroValue = if (known) {
            val name = DistroRegistry.forInstallation(meta!!.copy(arch = hostAbi))?.displayName ?: meta.distro
            getString(R.string.import_value_distro, name, arch)
        } else getString(R.string.import_value_unsupported, customName, arch)
        row(getString(R.string.import_row_distro), distroValue)
        if (meta != null) row(getString(R.string.import_row_original_label), DistroRegistry.displayLabel(meta))
        val m = header?.manifest ?: scan.manifest
        if (m != null && m.createdAtMillis > 0) {
            row(getString(R.string.import_row_exported), DateFormat.getDateTimeInstance().format(Date(m.createdAtMillis)))
        }
        if (m != null) row(getString(R.string.import_row_from_app), "${m.sourcePackage} v${m.appVersionName}")
        facts?.libc?.let { row(getString(R.string.import_row_libc), it) }
        row(getString(R.string.import_row_size), Formatter.formatFileSize(this, scan.uncompressedBytes))

        val problem = header?.let { DistroImporter.incompatibility(it, hostAbi) }

        // What probably won't work.
        if (facts != null) {
            if (facts.libc == RootfsFacts.LIBC_MUSL) note(getString(R.string.import_warn_musl), warning = true)
            if (!facts.hasBash) note(getString(R.string.import_warn_no_bash), warning = true)
            if (facts.abi == null) note(getString(R.string.import_warn_no_arch), warning = true)
        }
        if (scan.kind != DistroImporter.Kind.EXPORT) note(getString(R.string.import_warn_no_end_marker))

        // Label → id, validated like the install form.
        form.addView(TextView(this).apply { text = getString(R.string.install_label_label); textSize = 14f },
            verticalLp(MATCH_PARENT, WRAP_CONTENT))
        val labelField = EditText(this).apply {
            isSingleLine = true
            setText(labelText ?: meta?.let { DistroRegistry.displayLabel(it) } ?: facts?.osName
                ?: displayName(u)?.substringBefore('.')?.takeIf { it.isNotBlank() }
                ?: getString(R.string.import_default_label))
        }
        form.addView(labelField, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        val location = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        form.addView(location, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Carried settings aren't listed; only binds that won't work here
        // get a warning.
        if (meta != null) {
            val carried = DistroImporter.rewrite(
                meta, "x", null, "", 0L, AllFilesAccess.declared(this),
            )
            val dropped = meta.externalBinds.size - carried.externalBinds.size
            if (dropped > 0) note(getString(R.string.import_binds_dropped, dropped))
            if (AllFilesAccess.requiresGrant(carried.externalBinds) && !AllFilesAccess.granted()) {
                note(getString(R.string.import_binds_grant), error = true)
            }
            carried.externalBinds.firstOrNull { AllFilesAccess.hostDirVerifiablyMissing(it.hostPath) }?.let {
                note(getString(R.string.import_binds_missing, it.hostPath), error = true)
            }
        }

        // Advisory: an export's size comes from the exporter's pre-walk.
        val free = runCatching { StatFs(store.baseDir.apply { mkdirs() }.path).availableBytes }.getOrNull()
        val need = scan.uncompressedBytes + scan.uncompressedBytes / 10
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
