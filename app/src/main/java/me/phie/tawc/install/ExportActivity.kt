package me.phie.tawc.install

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.ops.LogScreenActivity

/**
 * UI-less trampoline for an export confirmed on distro info: asks the
 * system file picker (SAF `CREATE_DOCUMENT`) where to save, then starts
 * the export job and shows its log. The picker works in every build
 * and lets the user pick Downloads, a USB drive or a cloud provider.
 */
class ExportActivity : AppCompatActivity() {

    private val create = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DistroArchive.MIME),
    ) { uri ->
        val id = intent.getStringExtra(EXTRA_ID)
        if (uri != null && id != null) {
            // The job outlives this activity; the service releases it.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            InstallationService.startExport(
                this, id, uri, null, intent.getBooleanExtra(EXTRA_DELETE_AFTER, false),
            )
            startActivity(LogScreenActivity.intentFor(this, "export:$id"))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        // Recreation (rotation) mid-picker: the pending result is
        // redelivered to the new instance, don't open a second picker.
        if (savedInstanceState == null) {
            create.launch(DistroArchive.suggestedFileName(id, System.currentTimeMillis()))
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_DELETE_AFTER = "deleteAfter"

        fun intentFor(context: Context, id: String, deleteAfter: Boolean): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_DELETE_AFTER, deleteAfter)
    }
}
