package me.phie.tawc.launcher

import me.phie.tawc.dev.ActionContext
import me.phie.tawc.dev.ActionRegistry
import me.phie.tawc.dev.BrokerAction
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Broker actions for launcher test visibility, registered from
 * [me.phie.tawc.TawcApplication.onCreate] (debug builds only). Gives
 * integration tests a query surface for hidden filtering / scan shape
 * without screenshot scraping.
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `launcher-list` | `installId`, optional `showHidden` ∈ true|false | print the launcher entry list as a JSON array on stdout |
 * | `set-entry-hidden` | `installId`, `entryId`, `hidden` ∈ true|false | persist hide/unhide through the same metadata write the UI uses |
 *
 * `launcher-list` mirrors what [LauncherActivity] renders: hidden
 * entries are filtered out unless `showHidden=true` (the UI's
 * "Show hidden" toggle). Each element is
 * `{id, name, exec, terminal, iconPath, path, hidden}` — `iconPath` is
 * the resolved on-device PNG (empty when nothing resolved), which is
 * how icon-resolution tests see what `launcher.rs` picked.
 */
internal object LauncherActions {

    fun registerAll() {
        ActionRegistry.register("launcher-list", LauncherListAction)
        ActionRegistry.register("set-entry-hidden", SetEntryHiddenAction)
    }

    private object LauncherListAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val id = args["installId"]
                ?: return ctx.fail("launcher-list: --arg installId=<id> required")
            val showHidden = args["showHidden"]?.let {
                it.toBooleanStrictOrNull()
                    ?: return ctx.fail("launcher-list: invalid boolean for showHidden '$it'")
            } ?: false
            val store = InstallationStore(ctx.appContext)
            val inst = store.load(id)
                ?: return ctx.fail("launcher-list: no installation '$id'")
            val rootfs = store.rootfsDir(id).absolutePath
            val hidden = inst.hiddenDesktopIds.toSet()
            val out = JSONArray()
            for (e in LauncherEntry.scan(rootfs)) {
                val isHidden = e.id in hidden
                if (isHidden && !showHidden) continue
                out.put(JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("exec", e.exec)
                    put("terminal", e.terminal)
                    put("iconPath", e.iconPath)
                    put("path", e.path)
                    put("hidden", isHidden)
                })
            }
            ctx.out(out.toString())
            return 0
        }
    }

    private object SetEntryHiddenAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val id = args["installId"]
                ?: return ctx.fail("set-entry-hidden: --arg installId=<id> required")
            val entryId = args["entryId"]
                ?: return ctx.fail("set-entry-hidden: --arg entryId=<id> required")
            val raw = args["hidden"]
                ?: return ctx.fail("set-entry-hidden: --arg hidden=true|false required")
            val hidden = raw.toBooleanStrictOrNull()
                ?: return ctx.fail("set-entry-hidden: invalid boolean '$raw'")
            val updated: Installation = InstallationStore(ctx.appContext)
                .update(id) { it.withEntryHidden(entryId, hidden) }
                ?: return ctx.fail("set-entry-hidden: no installation '$id'")
            ctx.out(updated.hiddenDesktopIds.joinToString(","))
            return 0
        }
    }

    private fun ActionContext.fail(msg: String): Int {
        err(msg)
        return 2
    }
}
