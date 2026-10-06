package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * Installs TAWC's own OpenSSH `sftp-server` (static bionic, built by
 * `remote/sftp-server/build.sh`, shipped as `jniLibs/<abi>/libsftp-server.so`)
 * into every rootfs, so remote access sftp/scp work without openssh in
 * the distro (notes/remote-access.md). Same shape as [AndoInstallProvider];
 * lives in TAWC's own `/usr/lib/tawc`, off PATH.
 */
internal object SftpServerInstallProvider : TawcInstallProvider {
    override val name: String = "sftp-server"

    const val GUEST_BIN_PATH = "/usr/lib/tawc/sftp-server"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        val src = File(context.applicationInfo.nativeLibraryDir, "libsftp-server.so")
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
