package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.GraphicsBackend
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds
import kotlin.concurrent.thread

/**
 * Entry point for user-launched rootfs commands. Installer/package setup
 * calls [InstallationMethod.startInside] directly; this wrapper is for
 * user commands: it holds a session reason while the process lives and
 * makes sure the compositor's sockets are listening.
 */
internal object UserRootfsSession {
    fun startInside(
        context: Context,
        method: InstallationMethod,
        rootfs: String,
        command: String?,
        graphics: GraphicsBackend? = null,
    ): Process {
        // Nothing starts the compositor here: its sockets always accept,
        // and the first connection starts it.
        CompositorService.ensureActivation(context)
        // The hold follows the process, so no caller has to cooperate.
        val hold = SessionHolds.acquire(Reason.Command(commandLabel(command)))
        val proc = try {
            method.startInside(rootfs, command, graphics)
        } catch (t: Throwable) {
            hold.release()
            throw t
        }
        thread(name = "tawc-session-wait", isDaemon = true) {
            try {
                proc.waitFor()
            } catch (_: InterruptedException) {
            } finally {
                hold.release()
            }
        }
        return proc
    }

    /** Short name for the notification: the program's basename. */
    internal fun commandLabel(command: String?): String {
        val word = command?.trim()?.split(Regex("\\s+"))
            ?.firstOrNull { it.isNotEmpty() && !ENV_ASSIGNMENT.matches(it) }
            ?: return "shell"
        return word.substringAfterLast('/').ifEmpty { "shell" }
    }

    private val ENV_ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")

    fun runInside(
        context: Context,
        method: InstallationMethod,
        rootfs: String,
        command: String,
        onLine: ((String) -> Unit)? = null,
        graphics: GraphicsBackend? = null,
    ): MethodResult {
        val proc = startInside(context, method, rootfs, command, graphics)
        return MethodRunHelper.collectProcess(proc, onLine)
    }
}
