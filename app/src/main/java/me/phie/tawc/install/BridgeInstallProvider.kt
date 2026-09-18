package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.compositor.CompositorService
import java.io.File

/**
 * Lays the gfxstream-bridge guest-side bits into a rootfs:
 *  - `libvulkan_gfxstream.so` at [GUEST_LIB_PATH]
 *  - the Vulkan ICD JSON at [GUEST_ICD_PATH]
 *
 * When the gfxstream backend is enabled, both ride in the APK as raw
 * assets under `assets/mesa-gfxstream/`,
 * built by Gradle's `packMesaGfxstream` and extracted to
 * `<filesDir>/mesa-gfxstream/` by
 * [CompositorService.ensureMesaGfxstreamExtracted]. From there
 * [TawcInstaller] copies them into each proot/chroot rootfs at install
 * time; tawcroot instead binds `<filesDir>/mesa-gfxstream` at
 * [GUEST_LIB_DIR] read-only ([TawcrootMethod.assetBinds]), so this
 * provider contributes nothing to a tawcroot manifest.
 *
 * Installed when the backend and asset are shipped, regardless of the
 * current runtime graphics preference — the pref controls which env
 * [RootfsEnv] sets, not which files exist on disk.
 *
 * Returns an empty list if for some reason no asset shipped for this
 * device's ABI (e.g. a custom `-PtawcAbis` build that dropped it).
 */
internal object BridgeInstallProvider : TawcInstallProvider {
    override val name: String = "mesa-gfxstream"

    /** Where Mesa's gfxstream-vk lands inside the rootfs. The
     *  matching `VK_ICD_FILENAMES` value lives in [RootfsEnv]; keep
     *  these two strings in sync. `/usr/lib/gfxstream/` is a TAWC-owned
     *  namespace, matching `/usr/lib/hybris/` — see
     *  notes/installation.md "Install paths in the rootfs". The .so
     *  and the ICD JSON co-locate; the ICD JSON's internal
     *  `library_path` is baked at Mesa build time by
     *  `--prefix=/usr --libdir=lib/gfxstream` (see
     *  scripts/build-mesa-gfxstream.sh). */
    const val GUEST_LIB_DIR = "/usr/lib/gfxstream"
    const val GUEST_LIB_PATH = "$GUEST_LIB_DIR/${CompositorService.MESA_GFXSTREAM_LIB_ASSET}"
    const val GUEST_ICD_PATH = "$GUEST_LIB_DIR/${CompositorService.MESA_GFXSTREAM_ICD_ASSET}"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        if (!EnabledGraphicsBackends.gfxstream) return emptyList()
        // Whole-dir RO bind under tawcroot ([TawcrootMethod.assetBinds]) —
        // both entries below live in [GUEST_LIB_DIR], which the bind supplies.
        if (methodKey == TawcrootMethod.KEY) return emptyList()
        if (!CompositorService.ensureMesaGfxstreamExtracted(context)) return emptyList()
        val srcDir = File(context.filesDir, "mesa-gfxstream")
        return listOf(
            TawcInstall(
                src = File(srcDir, CompositorService.MESA_GFXSTREAM_LIB_ASSET).absolutePath,
                dest = GUEST_LIB_PATH,
                type = TawcInstall.Type.COPY,
            ),
            TawcInstall(
                src = File(srcDir, CompositorService.MESA_GFXSTREAM_ICD_ASSET).absolutePath,
                dest = GUEST_ICD_PATH,
                type = TawcInstall.Type.COPY,
            ),
        )
    }
}
