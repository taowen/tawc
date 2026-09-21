package me.phie.tawc.session

import android.content.Context
import android.util.Log
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.install.ChrootMethod
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.ops.OperationsRegistry
import me.phie.tawc.tasks.ProcessScanner
import me.phie.tawc.terminal.TerminalSessions
import kotlin.concurrent.thread

/**
 * Notification "Exit": kill everything in every rootfs. One notification
 * stands for every reason, so a partial exit would leave it up. Holds are
 * not released here — each follows its own process/session/compositor
 * down, and [SessionService] stops once the last one is gone.
 */
internal object SessionExit {
    private const val TAG = "tawc"

    /** Main thread. */
    fun killEverything(context: Context) {
        Log.i(TAG, "Session exit requested")
        // Shells die through the normal path: the exit closes the tab
        // (or the detached client drops the entry) and releases the hold.
        for (session in TerminalSessions.all()) session.finishIfRunning()
        CompositorService.stop()
        val app = context.applicationContext
        thread(name = "tawc-session-exit", isDaemon = true) {
            val store = InstallationStore(app)
            for (install in store.list()) {
                // An installer's processes are not ours to kill.
                if (install.state != Installation.State.READY || hasLiveInstallOp(install.id)) continue
                try {
                    ProcessScanner.killAllInRootfs(
                        rootfsPath = store.rootfsDir(install.id).absolutePath,
                        installId = install.id,
                        includeChroot = install.method == ChrootMethod.KEY,
                        log = {},
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "exit: kill in ${install.id} failed", t)
                }
            }
        }
    }

    private fun hasLiveInstallOp(id: String): Boolean =
        OperationsRegistry.get("install:$id") != null ||
            OperationsRegistry.get("uninstall:$id") != null
}
