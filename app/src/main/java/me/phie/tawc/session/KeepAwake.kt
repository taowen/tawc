package me.phie.tawc.session

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.R
import me.phie.tawc.Settings

/**
 * UI side of [SessionWake]'s toggle. The first enable while TAWC is
 * battery-optimized offers (once) the system's battery-optimization list.
 * Not the direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog:
 * its permission is Play-restricted.
 */
fun Activity.toggleKeepAwake() {
    val on = !SessionWake.held.value
    SessionWake.set(on)
    if (!on || Settings.batteryPromptShown) return
    if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
    Settings.batteryPromptShown = true
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.battery_prompt_title)
        .setMessage(R.string.battery_prompt_message)
        .setPositiveButton(R.string.battery_prompt_open) { _, _ ->
            try {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
