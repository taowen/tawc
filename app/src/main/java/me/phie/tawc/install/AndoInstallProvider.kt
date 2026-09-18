package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * Installs the ando guest client (`ando <cmd>` — run an Android command
 * from inside the rootfs; see notes/ando.md) into every rootfs.
 *
 * The binary is a static bionic executable built by
 * `tawcroot/ando/build.sh`, shipped in the APK as
 * `jniLibs/<abi>/libando.so` (the usual jniLib-extractor trick), so
 * `applicationInfo.nativeLibraryDir` already holds exactly the device
 * ABI's build — no asset tar needed. [TawcInstaller.applyToRootfs]
 * preserves the source's exec bit (the copy comes out 0711-style;
 * everything in the rootfs runs as the app uid, so owner-exec is all
 * that matters).
 *
 * `/usr/local/bin/ando` is a TAWC-owned file in user-namespace
 * territory (it's on every distro profile's PATH, which is the point).
 * If it ever conflicts with a user install, move to
 * `/usr/lib/tawc/bin/ando` + a symlink.
 */
internal object AndoInstallProvider : TawcInstallProvider {
    override val name: String = "ando"

    const val GUEST_BIN_PATH = "/usr/local/bin/ando"

    /** Method-independent: a single file in a distro-managed dir, so
     *  it's copied under every method. */
    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        val src = File(context.applicationInfo.nativeLibraryDir, "libando.so")
        if (!src.isFile) return emptyList()
        return listOf(
            TawcInstall(
                src = src.absolutePath,
                dest = GUEST_BIN_PATH,
                type = TawcInstall.Type.COPY,
            ),
        )
    }
}
