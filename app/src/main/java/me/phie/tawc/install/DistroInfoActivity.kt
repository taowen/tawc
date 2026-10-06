package me.phie.tawc.install

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ui.Scaffold
import me.phie.tawc.ui.buildChildScreen

/**
 * Per-installation detail screen: a [DistroInfoView] under a toolbar.
 * Reached from the home ⋮ menu and the drawer's per-distro ⋮, for any
 * install. Editable per-install settings live in
 * [me.phie.tawc.SettingsActivity].
 */
class DistroInfoActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var targetId: String = Installation.DISTRO_ARCH

    private lateinit var scaffold: Scaffold
    private lateinit var info: DistroInfoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetId = intent?.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH
        scaffold = buildChildScreen(targetId)
        info = DistroInfoView(this)
        scaffold.content.addView(info.view, android.widget.LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(scaffold.root)
        // Defer all view population to onResume so a returning trip
        // from LogScreenActivity (which may have flipped the slot
        // INSTALLING/READY → FAILED on cancel) re-reads metadata and
        // re-renders the right state row, button, and size probe.
    }

    override fun onResume() {
        super.onResume()
        val installation = store.load(targetId)
        if (installation == null) {
            // Uninstall happened in a child activity while we were paused;
            // there's nothing to show so back out to the home screen.
            finish()
            return
        }
        scaffold.toolbar.title = DistroRegistry.displayLabel(installation)
        info.render(installation)
    }

    override fun onPause() {
        super.onPause()
        info.stop()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
