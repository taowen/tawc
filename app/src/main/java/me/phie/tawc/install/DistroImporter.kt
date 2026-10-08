package me.phie.tawc.install

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import me.phie.tawc.AndoBrokers
import me.phie.tawc.install.util.AndroidFsOps
import me.phie.tawc.install.util.FsOps
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Recreates a distro from a [DistroArchive] stream under a new id.
 * Run by [InstallationService] as `JobKind.IMPORT`, an install variant:
 * same INSTALLING → READY | FAILED machine, same empty-slot gate.
 *
 * The archive is untrusted input even when the user made it: every
 * entry passes [DistroArchive.importRejection] and the extractor's
 * canonical-path containment, confined to `rootfs/` and `tawcroot/`.
 */
internal object DistroImporter {

    /** The two leading entries; enough for the import form and the gate. */
    data class Header(val manifest: DistroArchive.Manifest, val metadata: Installation)

    /**
     * Why [h] can't be imported here, or null. [runnable] says whether
     * a `(distro, androidAbi)` pair is installable on this host.
     */
    fun incompatibility(h: Header, runnable: (distro: String, arch: String) -> Boolean): String? {
        val m = h.metadata
        if (m.method != TawcrootMethod.KEY) return "only tawcroot exports can be imported (method: ${m.method})"
        if (!runnable(m.distro, m.arch)) return "${m.distro} (${m.arch}) can't run on this device"
        return null
    }

    fun hostRunnable(distro: String, arch: String): Boolean =
        me.phie.tawc.install.distro.DistroRegistry.availableForHost()
            .any { it.key == distro && it.androidAbi == arch }

    /**
     * The record for the new slot: identity and label from the form,
     * state INSTALLING, a null [Installation.tawcStamp] so
     * [TawcInstaller] refreshes this app's files (its old manifest
     * still names the dests to wipe), everything else carried. Shared-
     * storage binds are dropped when [allFilesDeclared] is false — that
     * build could never launch them and has no binds UI to fix it.
     */
    fun rewrite(
        src: Installation,
        newId: String,
        label: String?,
        sourcePackage: String,
        nowMillis: Long,
        allFilesDeclared: Boolean,
    ): Installation = src.copy(
        id = newId,
        label = label,
        state = Installation.State.INSTALLING,
        failure = null,
        schemaVersion = Installation.CURRENT_SCHEMA_VERSION,
        tawcStamp = null,
        externalBinds = if (allFilesDeclared) src.externalBinds
        else src.externalBinds.filterNot { AllFilesAccess.requiresGrant(it.hostPath) },
        importedAtMillis = nowMillis,
        importedFromPackage = sourcePackage.ifEmpty { null },
    )

    /**
     * Streaming reader: [readHeader] first, then [extractTo]. Digests
     * every entry before the trailer and checks it against the trailer;
     * an archive without a matching trailer never completes.
     * [decompress] is injectable for JVM tests (no host zstd-jni lib).
     */
    class Reader(
        input: InputStream,
        decompress: (InputStream) -> InputStream = ::ZstdInputStream,
    ) {
        private val tin = TarArchiveInputStream(decompress(BufferedInputStream(input, 1 shl 20)))
        private val digest = DistroArchive.EntryDigest()
        var trailerOk = false
            private set

        /** Current entry's bytes, digested. */
        private val data = object : InputStream() {
            override fun read(): Int {
                val b = tin.read()
                if (b >= 0) digest.content(byteArrayOf(b.toByte()), 0, 1)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = tin.read(b, off, len)
                if (n > 0) digest.content(b, off, n)
                return n
            }
        }

        fun readHeader(): Header {
            val m = tin.nextEntry
            if (m == null || m.name != DistroArchive.MANIFEST || !m.isFile) {
                throw IOException("not a TAWC distro export (no ${DistroArchive.MANIFEST} first)")
            }
            digest.header(m)
            val manifest = try {
                DistroArchive.Manifest.parse(readSmall(m, data))
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message, e)
            }
            val md = tin.nextEntry
            if (md == null || md.name != DistroArchive.METADATA || !md.isFile) {
                throw IOException("corrupt export: ${DistroArchive.METADATA} missing")
            }
            digest.header(md)
            val metadata = try {
                Installation.fromJson(readSmall(md, data))
            } catch (e: Exception) {
                throw IOException("corrupt export metadata: ${e.message}", e)
            }
            return Header(manifest, metadata)
        }

        /** Extract the remaining entries into [installDir] and verify the
         *  trailer. Throws on any rejection, truncation or mismatch. */
        fun extractTo(
            installDir: File,
            log: (String) -> Unit,
            progress: (Long) -> Unit,
            fs: FsOps = AndroidFsOps,
        ) {
            var bytes = 0L
            var lastReport = 0L
            val next: () -> TarArchiveEntry? = next@{
                val e = tin.nextEntry ?: throw IOException(
                    "archive is truncated (no ${DistroArchive.TRAILER} end marker)",
                )
                if (e.name == DistroArchive.TRAILER) {
                    verifyTrailer(e)
                    return@next null
                }
                DistroArchive.importRejection(e)?.let { throw IOException("rejected archive entry $it") }
                digest.header(e)
                if (e.isFile && !e.isLink) {
                    bytes += e.size
                    if (bytes - lastReport >= PROGRESS_STEP) {
                        lastReport = bytes
                        progress(bytes)
                    }
                }
                e
            }
            ProotArchiveExtractor.extractEntries(
                next = next,
                data = data,
                destDir = installDir.absolutePath,
                stripPrefix = null,
                onLine = log,
                fs = fs,
                preserveMtime = true,
                roots = DistroArchive.ROOTS,
                restoreXattrs = true,
            )
            if (!trailerOk) throw IOException("archive is truncated (no end marker)")
            progress(bytes)
        }

        private fun verifyTrailer(e: TarArchiveEntry) {
            if (!e.isFile) throw IOException("corrupt export: bad end marker")
            val t = DistroArchive.Trailer.parse(readSmall(e, tin))
            val got = digest.hex()
            if (t.entries != digest.count || t.sha256 != got) {
                throw IOException(
                    "archive is corrupt: end marker says ${t.entries} entries/${t.sha256.take(12)}, " +
                        "read ${digest.count}/${got.take(12)}",
                )
            }
            if (tin.nextEntry != null) throw IOException("corrupt export: data after the end marker")
            trailerOk = true
        }

        private fun readSmall(e: TarArchiveEntry, from: InputStream): String {
            if (e.size > DistroArchive.MAX_JSON_BYTES) throw IOException("${e.name} is too large")
            val buf = ByteArray(e.size.toInt())
            var off = 0
            while (off < buf.size) {
                val n = from.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("archive is truncated in ${e.name}")
                off += n
            }
            return String(buf, Charsets.UTF_8)
        }
    }

    /**
     * The whole import job: header checks, INSTALLING record, extract +
     * trailer check, then the same app-file refresh an install does.
     * The caller has verified the slot is empty; any throw after the
     * record is written leaves a FAILED slot for the normal Delete.
     */
    fun import(
        context: Context,
        store: InstallationStore,
        newId: String,
        label: String?,
        input: InputStream,
        log: (String) -> Unit,
        progress: (String, Int?) -> Unit,
    ) {
        val reader = Reader(input)
        val header = reader.readHeader()
        incompatibility(header, ::hostRunnable)?.let { throw IOException(it) }
        val m = header.manifest
        log("[import] ${m.distro}/${m.arch} '${m.label ?: m.id}' from ${m.sourcePackage} " +
            "v${m.appVersionName}, ${m.entries} entries")
        if (store.installationDir(newId).exists()) throw IOException("'$newId' already exists")

        val record = rewrite(
            header.metadata, newId, label, m.sourcePackage, System.currentTimeMillis(),
            AllFilesAccess.declared(context),
        )
        val dropped = header.metadata.externalBinds.size - record.externalBinds.size
        if (dropped > 0) log("[import] dropped $dropped shared-storage bind(s): this build has no all-files access")
        store.save(record)

        val installDir = store.installationDir(newId)
        reader.extractTo(installDir, log, { done ->
            val pct = if (m.uncompressedBytes > 0) ((done * 100) / m.uncompressedBytes).toInt().coerceIn(0, 100) else null
            progress(
                context.getString(
                    me.phie.tawc.R.string.import_progress_extracting,
                    me.phie.tawc.install.util.HumanSize.format(done),
                    me.phie.tawc.install.util.HumanSize.format(m.uncompressedBytes),
                ),
                pct,
            )
        })

        val storeDir = File(installDir, DistroArchive.STORE)
        if (storeDir.isDirectory) {
            File(storeDir, "tmp").mkdirs()
            val ver = File(storeDir, "version").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (ver != null && ver > DistroArchive.LINK_STORE_VERSION) {
                log("[import] link store v$ver is newer than this app's v${DistroArchive.LINK_STORE_VERSION}; " +
                    "hardlinks stay readable but new ones can't be made")
            }
        }
        val tmp = File(store.rootfsDir(newId), "tmp")
        if (!tmp.isDirectory) {
            tmp.mkdirs()
            AndroidFsOps.chmod(tmp.path, STICKY_1777)
        }

        TawcInstaller.installInto(context, store, newId, log)
        store.setState(newId, Installation.State.READY)
        AndoBrokers.refresh(context)
    }

    private const val PROGRESS_STEP = 8L shl 20
    private const val STICKY_1777 = 1023 // 0o1777
}
