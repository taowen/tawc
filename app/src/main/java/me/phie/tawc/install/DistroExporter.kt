package me.phie.tawc.install

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.luben.zstd.ZstdOutputStream
import me.phie.tawc.BuildConfig
import me.phie.tawc.install.util.AndroidFsOps
import me.phie.tawc.install.util.FsOps
import me.phie.tawc.install.util.FsStat
import me.phie.tawc.remote.RemoteSession
import me.phie.tawc.tasks.ProcessScanner
import me.phie.tawc.terminal.TerminalSessions
import me.phie.tawc.terminal.kill
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.Date

/**
 * Writes a READY tawcroot distro to a [DistroArchive] stream. The
 * service ([InstallationService], `JobKind.EXPORT`) owns the gate and
 * the output stream; this owns quiesce and the walk.
 *
 * The walk reads the **host** install dir with `lstat` and never
 * follows symlinks, so bind sources (shared storage, GPU stacks, the
 * share dir) are never included — only their empty guest mountpoints.
 */
internal object DistroExporter {

    data class Result(val entries: Long, val bytes: Long, val skipped: Int)

    /**
     * Quiesce [id] and write it to [out] (closed on return, success or
     * not). [progress] gets (content bytes written, advisory total).
     */
    fun export(
        context: Context,
        store: InstallationStore,
        id: String,
        out: OutputStream,
        log: (String) -> Unit,
        progress: (Long, Long) -> Unit,
    ): Result {
        out.use {
            val inst = store.load(id) ?: throw IOException("no distro '$id'")
            if (inst.state != Installation.State.READY) {
                throw IOException("distro '$id' is ${inst.state.name.lowercase()}, not ready")
            }
            if (inst.method != TawcrootMethod.KEY) {
                throw IOException("only tawcroot distros can be exported (method: ${inst.method})")
            }
            if (!DistroBusy.mark(id)) throw IOException("distro '$id' is already being exported")
            try {
                quiesce(context, store, id, log)
                val installDir = store.installationDir(id)
                val metadata = store.metadataFile(id).readBytes()
                val versionCode = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) { 0L }
                val writer = ArchiveWriter(installDir, AndroidFsOps, log)
                val manifest = DistroArchive.Manifest(
                    format = DistroArchive.FORMAT,
                    createdAtMillis = System.currentTimeMillis(),
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = versionCode,
                    sourcePackage = context.packageName,
                    id = inst.id,
                    label = inst.label,
                    distro = inst.distro,
                    arch = inst.arch,
                    method = inst.method,
                    uncompressedBytes = 0,
                    entries = 0,
                )
                return writer.write(manifest, metadata, compress(out, log), progress)
            } finally {
                DistroBusy.clear(id)
            }
        }
    }

    /**
     * Stop everything running in [id] and settle its link store. The
     * caller holds the [DistroBusy] mark, so nothing new can start.
     */
    private fun quiesce(context: Context, store: InstallationStore, id: String, log: (String) -> Unit) {
        log("[export] stopping programs in '$id'")
        RemoteSession.stopFor(id)
        // Tabs close through their normal exit path once the shells die.
        Handler(Looper.getMainLooper()).post {
            for (s in TerminalSessions.list(id)) s.kill()
        }
        val installDir = store.installationDir(id)
        val rootfs = store.rootfsDir(id).absolutePath
        ProcessScanner.killAllInRootfs(
            rootfsPath = rootfs,
            installId = id,
            includeChroot = false,
            extraCmdlinePath = installDir.absolutePath,
            log = { log("[export] kill: $it") },
        )
        val storeDir = File(installDir, DistroArchive.STORE)
        if (storeDir.isDirectory) {
            // Any tawcroot session start replays a pending link-store
            // journal (tawcroot_linkstore_recover_now).
            val r = TawcrootMethod(context).runNoop(rootfs)
            if (!r.ok) throw IOException("link store recovery spawn failed (exit ${r.exitCode}): ${r.output}")
            if (File(storeDir, "intent").exists()) {
                throw IOException("link store has pending recovery; open a terminal in '$id' once and retry")
            }
        }
    }

    /** zstd level 3; multithreaded when the bundled zstd-jni supports it. */
    private fun compress(out: OutputStream, log: (String) -> Unit): OutputStream {
        val z = ZstdOutputStream(BufferedOutputStream(out, 1 shl 20), 3)
        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        if (workers > 1) {
            try {
                z.setWorkers(workers)
            } catch (t: Throwable) {
                log("[export] zstd: single-threaded (${t.message})")
            }
        }
        return z
    }

    /**
     * The walk + tar writer, split from [export] so JVM tests can run
     * it over a temp dir with a non-Android [FsOps].
     */
    class ArchiveWriter(
        private val installDir: File,
        private val fs: FsOps,
        private val log: (String) -> Unit,
    ) {
        private var skipped = 0

        /** Pre-walk then write [out] (closed). [base]'s counts are filled in. */
        fun write(
            base: DistroArchive.Manifest,
            metadata: ByteArray,
            out: OutputStream,
            progress: (Long, Long) -> Unit,
        ): Result {
            var totalEntries = 2L
            var totalBytes = 0L
            walk { _, _, st ->
                totalEntries++
                if (st.isFile) totalBytes += st.size
            }
            skipped = 0
            val manifest = base.copy(uncompressedBytes = totalBytes, entries = totalEntries)
            val digest = DistroArchive.EntryDigest()
            val now = (manifest.createdAtMillis / 1000) * 1000
            var written = 0L
            var lastReport = 0L
            val tar = TarArchiveOutputStream(out).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                setAddPaxHeadersForNonAsciiNames(true)
            }
            tar.use {
                fun put(e: TarArchiveEntry, content: InputStream?) {
                    e.userId = 0
                    e.groupId = 0
                    e.userName = "root"
                    e.groupName = "root"
                    digest.header(e)
                    tar.putArchiveEntry(e)
                    if (content != null) {
                        val buf = ByteArray(256 * 1024)
                        var left = e.size
                        while (left > 0) {
                            val n = content.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("${e.name} shrank during export")
                            tar.write(buf, 0, n)
                            digest.content(buf, 0, n)
                            left -= n
                            written += n
                            if (written - lastReport >= PROGRESS_STEP) {
                                lastReport = written
                                progress(written, totalBytes)
                            }
                        }
                    }
                    tar.closeArchiveEntry()
                }

                fun json(name: String, bytes: ByteArray) {
                    val e = TarArchiveEntry(name).apply {
                        size = bytes.size.toLong()
                        mode = 420 // 0644
                        modTime = Date(now)
                    }
                    put(e, bytes.inputStream())
                }

                json(DistroArchive.MANIFEST, manifest.toJson().toByteArray())
                json(DistroArchive.METADATA, metadata)

                // dev:ino -> first archived name, for real hardlinks.
                // tawcroot denies link(2), so normally empty.
                val seen = HashMap<String, String>()
                walk { rel, f, st ->
                    when {
                        st.isDir -> put(
                            TarArchiveEntry("$rel/").apply {
                                mode = st.perm
                                modTime = mtime(st)
                                addXattrs(this, f)
                            },
                            null,
                        )
                        st.isSymlink -> put(
                            TarArchiveEntry(rel, TarConstants.LF_SYMLINK).apply {
                                linkName = fs.readlink(f.path)
                                mode = st.perm
                                modTime = mtime(st)
                            },
                            null,
                        )
                        else -> {
                            val first = if (st.nlink > 1) seen.putIfAbsent("${st.dev}:${st.ino}", rel) else null
                            if (first != null) {
                                put(
                                    TarArchiveEntry(rel, TarConstants.LF_LINK).apply {
                                        linkName = first
                                        mode = st.perm
                                        modTime = mtime(st)
                                    },
                                    null,
                                )
                            } else {
                                openReadable(f, st).use { input ->
                                    put(
                                        TarArchiveEntry(rel).apply {
                                            size = st.size
                                            mode = st.perm
                                            modTime = mtime(st)
                                            addXattrs(this, f)
                                        },
                                        input,
                                    )
                                }
                            }
                        }
                    }
                }
                val trailer = DistroArchive.Trailer(digest.count, digest.hex())
                val tb = trailer.toJson().toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(DistroArchive.TRAILER).apply {
                    size = tb.size.toLong()
                    mode = 420
                    modTime = Date(now)
                    userName = "root"
                    groupName = "root"
                })
                tar.write(tb)
                tar.closeArchiveEntry()
                tar.finish()
            }
            progress(written, totalBytes)
            if (skipped > 0) log("[export] skipped $skipped sockets/fifos/devices")
            if (skippedXattrs > 0) log("[export] skipped $skippedXattrs non-text xattrs")
            return Result(digest.count + 1, written, skipped)
        }

        private fun mtime(st: FsStat) = Date((st.mtimeMs / 1000) * 1000)

        private var skippedXattrs = 0

        /** `user.*` xattrs as PAX `SCHILY.xattr.*` (what GNU tar writes;
         *  e.g. browsers' `user.xdg.origin.url`). commons-compress keeps
         *  PAX values as strings, so non-UTF-8 values are skipped. */
        private fun addXattrs(e: TarArchiveEntry, f: File) {
            for ((name, value) in fs.userXattrs(f.path)) {
                val text = try {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    skippedXattrs++
                    continue
                }
                e.addPaxHeader(DistroArchive.XATTR_PAX_PREFIX + name, text)
            }
        }

        /**
         * Depth-first, name-sorted walk of the exportable tree, calling
         * [visit] for every dir, file and symlink. Sockets/FIFOs/devices
         * are counted in [skipped]. Hand-rolled over `list()` + lstat so
         * nothing is ever followed.
         */
        private fun walk(visit: (rel: String, f: File, st: FsStat) -> Unit) {
            for (top in DistroArchive.ROOTS) {
                val f = File(installDir, top)
                val st = try { fs.lstat(f.path) } catch (_: IOException) { continue }
                if (!st.isDir) throw IOException("$f is not a directory")
                walkEntry(top, f, st, visit)
            }
        }

        private fun walkEntry(rel: String, f: File, st: FsStat, visit: (String, File, FsStat) -> Unit) {
            if (Thread.interrupted()) throw InterruptedIOException("export cancelled")
            when (DistroArchive.exportRule(rel)) {
                DistroArchive.ExportRule.EXCLUDE -> return
                DistroArchive.ExportRule.FAIL ->
                    throw IOException("$rel present after quiesce; link store has pending recovery")
                else -> Unit
            }
            if (!st.isDir && !st.isFile && !st.isSymlink) {
                skipped++
                return
            }
            visit(rel, f, st)
            if (!st.isDir || DistroArchive.exportRule(rel) == DistroArchive.ExportRule.DIR_ONLY) return
            for (name in listReadable(f, st).sorted()) {
                val child = File(f, name)
                val cst = fs.lstat(child.path)
                walkEntry("$rel/$name", child, cst, visit)
            }
        }

        /** `list()`, briefly granting the owner r-x on a dir that lacks
         *  it (tawcroot emulates DAC override for guests; we're not one). */
        private fun listReadable(dir: File, st: FsStat): Array<String> {
            dir.list()?.let { return it }
            if (st.perm and OWNER_RX == OWNER_RX) throw IOException("cannot list $dir")
            fs.chmod(dir.path, st.perm or OWNER_RX)
            try {
                return dir.list() ?: throw IOException("cannot list $dir")
            } finally {
                fs.chmod(dir.path, st.perm)
            }
        }

        /** Open [f], briefly granting the owner read if it lacks it.
         *  The mode is restored once the open succeeds. */
        private fun openReadable(f: File, st: FsStat): InputStream {
            try {
                return FileInputStream(f)
            } catch (e: IOException) {
                if (st.perm and OWNER_R != 0) throw e
            }
            fs.chmod(f.path, st.perm or OWNER_R)
            try {
                return FileInputStream(f)
            } finally {
                fs.chmod(f.path, st.perm)
            }
        }
    }

    private const val PROGRESS_STEP = 8L shl 20
    private const val OWNER_R = 256   // 0o400
    private const val OWNER_RX = 320  // 0o500
}
