package me.phie.tawc

import android.app.Application
import me.phie.tawc.dev.DevActivityTracker
import me.phie.tawc.dev.ExecBroker
import me.phie.tawc.dev.InputActions
import me.phie.tawc.dev.SessionActions
import me.phie.tawc.dev.SettingsActions
import me.phie.tawc.install.InstallActions
import me.phie.tawc.launcher.LauncherActions

/**
 * Debug build of the process-start dev hooks, called unconditionally
 * from [TawcApplication.onCreate].
 *
 * The release source set has an empty twin at the same FQCN, which is
 * why the whole `me.phie.tawc.dev` package (plus [InstallActions] and
 * [LauncherActions]) can live in `src/debug/java` and be absent from the
 * release APK entirely — not merely never started. See
 * notes/exec-broker.md.
 */
internal object DevHooks {
    fun start(app: Application) {
        // Started here (not from MainActivity) so the broker is
        // available no matter which Activity / Service the cold-start
        // went through — the install + integration test flows often
        // start at InstallActivity.
        app.registerActivityLifecycleCallbacks(DevActivityTracker)
        ExecBroker.start(app)
        // Action handlers must register before any host connection;
        // the broker thread spawned by start() above accepts asynchronously
        // but won't dispatch ACTION headers to a missing handler.
        InstallActions.registerAll()
        InputActions.registerAll()
        SettingsActions.registerAll()
        SessionActions.registerAll()
        LauncherActions.registerAll()
    }
}
