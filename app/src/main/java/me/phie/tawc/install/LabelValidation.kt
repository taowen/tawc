package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.R

/**
 * Label → id validation shared by the install and import forms: the
 * label slugifies into the on-disk id, which must be free.
 */
internal object LabelValidation {
    /** [id] is null when the label is unusable; [message] is then the
     *  reason, else the resolved install path. */
    data class Result(val id: String?, val message: String)

    fun check(context: Context, store: InstallationStore, rawLabel: String): Result {
        val label = rawLabel.trim()
        val slug = if (label.isEmpty()) null else Installation.slugifyLabel(label)
        val collides = slug != null && store.installationDir(slug).exists()
        return when {
            label.isEmpty() -> Result(null, context.getString(R.string.install_label_empty))
            slug == null -> Result(null, context.getString(R.string.install_label_invalid))
            collides -> Result(
                null,
                context.getString(R.string.install_already_installed_at, store.installationDir(slug).absolutePath),
            )
            else -> Result(slug, store.installationDir(slug).absolutePath)
        }
    }
}
