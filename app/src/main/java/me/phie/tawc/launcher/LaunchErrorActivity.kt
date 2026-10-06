package me.phie.tawc.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Transparent host for the launch-failure dialog. Launches are
 * fire-and-forget ([EntryLauncher]) and the launching screen may be
 * gone when a spawn failure arrives, so the failure handler starts this
 * from the application context instead of toasting — bind errors are
 * full sentences with paths and don't fit a toast.
 */
class LaunchErrorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "")
            .setMessage(intent.getStringExtra(EXTRA_MESSAGE) ?: "")
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
        // Errors carry paths/settings hints the user may want to copy.
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"

        /**
         * Show the dialog from any context. With an application
         * context this only works while the app has a visible window
         * (background activity-launch rules); a late failure with the
         * app backgrounded (e.g. the 30s Wayland-socket timeout) is
         * silently dropped — accepted, so callers should log the
         * failure independently.
         */
        fun start(context: Context, title: String, message: String) {
            context.startActivity(
                Intent(context, LaunchErrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MESSAGE, message),
            )
        }
    }
}
