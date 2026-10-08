package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.AndoBrokers
import me.phie.tawc.install.util.AndroidFsOps
import me.phie.tawc.install.util.FsOps
import me.phie.tawc.install.util.HostArch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Recreates a distro from an import archive under a new id. Run by
 * [InstallationService] as `JobKind.IMPORT`, an install variant: same
 * INSTALLING → READY | FAILED machine, same empty-slot gate.
 *
 * Archives come in three [Kind]s (notes/installation.md "Export /
 * import" and "Custom distros"): a well-framed [DistroArchive] export,
 * a damaged one (re-packed, bad manifest, …) imported best effort, or
 * any plain Linux rootfs tarball, imported as a [Installation.DISTRO_CUSTOM]
 * distro.
 *
 * The archive is untrusted input even when the user made it: every
 * extracted entry passes [DistroArchive.importRejection] and the
 * extractor's canonical-path containment, confined to `rootfs/` and
 * `tawcroot/`.
 */
internal object DistroImporter {

    enum class Kind { EXPORT, DAMAGED_EXPORT, PLAIN_ROOTFS }

    /** The two leading entries of a well-framed export. */
    data class Header(val manifest: DistroArchive.Manifest, val metadata: Installation)

    /**
     * What the import form shows. [header] is set for [Kind.EXPORT]
     * (classified from the first two entries only); the rest comes
     * from one headers-only pass over any other archive.
     */
    data class Scan(
        val kind: Kind,
        val header: Header? = null,
        val layout: ArchiveLayout? = null,
        /** Parsed from a damaged export, when they parse. */
        val manifest: DistroArchive.Manifest? = null,
        val metadata: Installation? = null,
        /** Why a damaged export isn't well-framed. */
        val problems: List<String> = emptyList(),
        val facts: RootfsFacts? = null,
        val entries: Long,
        val uncompressedBytes: Long,
    )

    /** Why the export [h] can't be imported on a [hostAbi] phone, or
     *  null. An unknown distro key is fine (a newer app's distro, or a
     *  custom one); only method and arch matter. */
    fun incompatibility(h: Header, hostAbi: String): String? {
        val m = h.metadata
        if (m.method != TawcrootMethod.KEY) return "only tawcroot exports can be imported (method: ${m.method})"
        if (m.arch != hostAbi) {
            return "${m.distro} for ${RootfsFacts.linuxArch(m.arch)} can't run on this " +
                "${RootfsFacts.linuxArch(hostAbi)} phone"
        }
        return null
    }

    /** Whether this build knows `(distro, arch)`. */
    fun knownDistro(distro: String, arch: String): Boolean =
        me.phie.tawc.install.distro.DistroRegistry.all.any { it.key == distro && it.androidAbi == arch }

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

    /** Inputs for [looseRecord] that don't come from the archive. */
    data class Target(
        val id: String,
        val label: String?,
        val hostAbi: String,
        val sourceName: String,
        val nowMillis: Long,
        val appVersionCode: Long,
        val allFilesDeclared: Boolean,
    )

    /**
     * The record for a damaged export or plain rootfs: a damaged
     * export's own settings when its metadata parsed (as its recorded
     * distro if this build knows it, else custom), otherwise a fresh
     * [Installation.DISTRO_CUSTOM] record.
     */
    fun looseRecord(
        t: Target,
        metadata: Installation?,
        manifest: DistroArchive.Manifest?,
        facts: RootfsFacts?,
        known: (String, String) -> Boolean = ::knownDistro,
    ): Installation {
        if (metadata != null && metadata.method == TawcrootMethod.KEY) {
            val r = rewrite(
                metadata, t.id, t.label ?: metadata.label, manifest?.sourcePackage.orEmpty(), t.nowMillis,
                t.allFilesDeclared,
            )
                .copy(arch = t.hostAbi)
            if (known(metadata.distro, t.hostAbi)) return r
            return r.copy(
                distro = Installation.DISTRO_CUSTOM,
                label = r.label ?: facts?.osName ?: r.osName,
                osName = facts?.osName ?: r.osName,
                osId = facts?.osId ?: r.osId,
                libc = facts?.libc ?: r.libc,
            )
        }
        return Installation(
            id = t.id,
            label = t.label ?: facts?.osName,
            distro = Installation.DISTRO_CUSTOM,
            arch = t.hostAbi,
            method = TawcrootMethod.KEY,
            installedAtMillis = t.nowMillis,
            sourceUrl = t.sourceName,
            state = Installation.State.INSTALLING,
            installedAtAppVersionCode = t.appVersionCode,
            bootstrapFlavor = Installation.FLAVOR_IMPORTED,
            importedAtMillis = t.nowMillis,
            osName = facts?.osName,
            osId = facts?.osId,
            libc = facts?.libc,
        )
    }

    /**
     * Form-time classification of [input]. Throws [IOException] with a
     * user-facing reason for an archive that can't be imported (not a
     * tar, no rootfs, container image, wrong architecture, nothing to
     * run). [progress] gets the uncompressed bytes read so far.
     */
    fun scan(
        input: InputStream,
        hostAbi: String,
        decompress: (InputStream) -> InputStream = ArchiveFormat::open,
        progress: (Long) -> Unit = {},
    ): Scan {
        val r = Reader(input, decompress = decompress)
        r.openExport()?.let { h ->
            return Scan(Kind.EXPORT, header = h, entries = h.manifest.entries,
                uncompressedBytes = h.manifest.uncompressedBytes)
        }
        r.drain(progress)
        return r.classify(hostAbi, complete = true)
    }

    /**
     * Streaming reader. [openExport] first: a well-framed export then
     * goes through [extractTo], which digests every entry and checks
     * the trailer — such an archive never completes without a matching
     * one. Anything else is [lookahead] + [classify] + [extractLoose].
     * [decompress] is injectable for JVM tests (no host zstd-jni lib).
     */
    class Reader(
        input: InputStream,
        /** Content [lookahead] may hold in memory. */
        private val holdBytes: Long = LOOKAHEAD_BYTES,
        decompress: (InputStream) -> InputStream = ArchiveFormat::open,
    ) {
        private val tin = TarArchiveInputStream(decompress(input))
        private val digest = DistroArchive.EntryDigest()
        private var digesting = true
        var trailerOk = false
            private set

        /** Where the current entry's bytes come from: [tin], or a held
         *  copy while [extractLoose] replays the lookahead. */
        private var source: InputStream = tin

        /** Current entry's bytes, digested while [digesting]. */
        private val data = object : InputStream() {
            override fun read(): Int {
                val b = source.read()
                if (b >= 0 && digesting) digest.content(byteArrayOf(b.toByte()), 0, 1)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = source.read(b, off, len)
                if (n > 0 && digesting) digest.content(b, off, n)
                return n
            }
        }

        private class Held(val entry: TarArchiveEntry, val bytes: ByteArray?)

        private val detector = ArchiveLayout.Detector()
        private val observer = RootfsFacts.Observer()
        /** Small TAWC JSON entries (any depth the detector looks at). */
        private val json = HashMap<String, String>()
        private val held = ArrayList<Held>()
        private var heldBytes = 0L
        /** The last held entry's content is still in [tin]. */
        private var heldOpen = false
        private var ended = false
        private var firstName: String? = null
        private var lastName: String? = null
        private var entries = 0L
        private var bytes = 0L

        /** [openExport], or throw: for callers that only take exports. */
        fun readHeader(): Header = openExport()
            ?: throw IOException(notExport ?: "not a TAWC distro export")

        private var notExport: String? = null

        /**
         * The header of a well-framed export (manifest first, metadata
         * second, both valid), or null. On null the entries read so
         * far are held for [lookahead] and the reason is kept.
         */
        fun openExport(): Header? {
            val first = tin.nextEntry ?: throw IOException("the archive is empty")
            if (first.name != DistroArchive.MANIFEST || !regular(first)) {
                return notExport("not a TAWC distro export (no ${DistroArchive.MANIFEST} first)", first)
            }
            digest.header(first)
            val mText = readSmall(first, data)
            val manifest = try {
                DistroArchive.Manifest.parse(mText)
            } catch (e: IllegalArgumentException) {
                return notExport(e.message ?: "bad export manifest", first, mText)
            }
            val second = tin.nextEntry
            if (second == null || second.name != DistroArchive.METADATA || !regular(second)) {
                hold(first, mText.toByteArray())
                return notExport("corrupt export: ${DistroArchive.METADATA} missing", second)
            }
            digest.header(second)
            val mdText = readSmall(second, data)
            val metadata = try {
                Installation.fromJson(mdText)
            } catch (e: Exception) {
                hold(first, mText.toByteArray())
                return notExport("corrupt export metadata: ${e.message}", second, mdText)
            }
            return Header(manifest, metadata)
        }

        private fun notExport(why: String, e: TarArchiveEntry?, text: String? = null): Header? {
            notExport = why
            digesting = false
            if (e != null) hold(e, text?.toByteArray())
            return null
        }

        /** Read and hold entries until the guest root is certain, the
         *  archive ends, or [maxEntries] / the byte budget runs out. */
        fun lookahead(maxEntries: Int = LOOKAHEAD_ENTRIES) {
            while (!ended && !heldOpen && !detector.certain && held.size < maxEntries) {
                if (Thread.interrupted()) throw InterruptedIOException("import cancelled")
                val e = tin.nextEntry
                if (e == null) {
                    ended = true
                    break
                }
                hold(e)
            }
        }

        private fun hold(e: TarArchiveEntry, content: ByteArray? = null) {
            val n = ArchiveLayout.normalize(e.name)
            note(e, n)
            val regular = regular(e)
            val c = when {
                content != null -> content
                !regular -> null
                e.size > holdBytes - heldBytes -> {
                    // Too big to hold: stays in the stream, ends lookahead.
                    held += Held(e, null)
                    heldOpen = true
                    observe(n, e, null)
                    return
                }
                else -> readFully(e)
            }
            heldBytes += c?.size ?: 0
            held += Held(e, c ?: ByteArray(0))
            observe(n, e, c?.let { b -> { k: Int -> b.copyOf(minOf(k, b.size)) } })
        }

        /** Headers-only pass over the rest of the archive (the form scan). */
        fun drain(progress: (Long) -> Unit) {
            var lastReport = 0L
            while (true) {
                if (Thread.interrupted()) throw InterruptedIOException("scan cancelled")
                val e = tin.nextEntry ?: break
                val n = ArchiveLayout.normalize(e.name)
                note(e, n)
                observe(n, e) { k -> readUpTo(k) }
                if (bytes - lastReport >= PROGRESS_STEP) {
                    lastReport = bytes
                    progress(bytes)
                }
            }
            ended = true
            progress(bytes)
        }

        private fun note(e: TarArchiveEntry, n: String) {
            if (firstName == null) firstName = e.name
            lastName = n
            entries++
            if (regular(e)) bytes += e.size
        }

        private fun observe(n: String, e: TarArchiveEntry, read: ((Int) -> ByteArray)?) {
            detector.add(n, e.isDirectory)
            observer.entry(n)
            if (read == null || !regular(e)) return
            val want = observer.wants(n, true)
            if (want > 0) observer.content(n, read(want))
            val leaf = n.substringAfterLast('/')
            if ((leaf == DistroArchive.METADATA || leaf == DistroArchive.MANIFEST || leaf == DistroArchive.TRAILER) &&
                e.size <= DistroArchive.MAX_JSON_BYTES && json.size < MAX_JSON_ENTRIES
            ) {
                json[n] = String(read(e.size.toInt()), Charsets.UTF_8)
            }
        }

        /**
         * Kind, layout and facts from what was read so far. With
         * [complete] false (the import job, after [lookahead]) the
         * layout may be a guess and only the architecture is checked;
         * the job re-checks the extracted tree.
         */
        fun classify(hostAbi: String, complete: Boolean = ended): Scan {
            val layout = detector.decide(complete) ?: throw IOException(detector.unrecognized())
            val facts = observer.finish(layout.root)
            if (complete) {
                facts.refusal(hostAbi)?.let { throw IOException(it) }
            } else if (facts.abi != null && facts.abi != hostAbi) {
                facts.refusal(hostAbi)?.let { throw IOException(it) }
            }
            val damaged = detector.hasMarkers(layout)
            val base = layout.base
            val mText = json[base + DistroArchive.MANIFEST]
            var mProblem: String? = null
            val manifest = mText?.let {
                try {
                    DistroArchive.Manifest.parse(it)
                } catch (e: IllegalArgumentException) {
                    mProblem = e.message
                    null
                }
            }
            val mdText = json[base + DistroArchive.METADATA]
            var mdProblem: String? = null
            val metadata = mdText?.let {
                try {
                    Installation.fromJson(it)
                } catch (e: Exception) {
                    mdProblem = "${DistroArchive.METADATA}: ${e.message}"
                    null
                }
            }
            val problems = if (!damaged) emptyList() else buildList {
                when {
                    mText == null -> add("no ${DistroArchive.MANIFEST}")
                    mProblem != null -> add(mProblem!!)
                    firstName != DistroArchive.MANIFEST -> add("re-packed (${DistroArchive.MANIFEST} isn't the first entry)")
                }
                when {
                    mdText == null -> add("no ${DistroArchive.METADATA}, so default settings")
                    mdProblem != null -> add("$mdProblem, so default settings")
                }
                if (complete) {
                    when {
                        json[base + DistroArchive.TRAILER] == null -> add("no end marker")
                        lastName != base + DistroArchive.TRAILER -> add("the end marker isn't the last entry")
                    }
                }
                if (isEmpty()) add(notExport ?: "not framed as an export")
            }
            return Scan(
                kind = if (damaged) Kind.DAMAGED_EXPORT else Kind.PLAIN_ROOTFS,
                layout = layout,
                manifest = manifest,
                metadata = metadata,
                problems = problems,
                facts = facts,
                entries = entries,
                uncompressedBytes = bytes,
            )
        }

        /** Metadata of [layout]'s base, if one was read and parses. */
        fun metadataFor(layout: ArchiveLayout): Installation? =
            json[layout.base + DistroArchive.METADATA]?.let { runCatching { Installation.fromJson(it) }.getOrNull() }

        /** Extract the remaining entries of a well-framed export into
         *  [installDir] and verify the trailer. Throws on any rejection,
         *  truncation or mismatch. */
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
                if (regular(e)) {
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

        /**
         * Extract a damaged export or plain rootfs: the held lookahead,
         * then the rest of the stream, renamed by [layout] into
         * `rootfs/` (and `tawcroot/`). Entries outside it are skipped
         * and counted; metadata found on the way is kept for
         * [metadataFor]. No end marker: only a broken stream fails.
         */
        fun extractLoose(
            installDir: File,
            layout: ArchiveLayout,
            log: (String) -> Unit,
            progress: (Long) -> Unit,
            fs: FsOps = AndroidFsOps,
        ) {
            digesting = false
            var bytes = 0L
            var lastReport = 0L
            var outside = 0L
            val replay = ArrayDeque(held)
            held.clear()
            val next: () -> TarArchiveEntry? = next@{
                while (true) {
                    val h = replay.removeFirstOrNull()
                    val e: TarArchiveEntry
                    if (h != null) {
                        e = h.entry
                        source = h.bytes?.let(::ByteArrayInputStream) ?: tin
                    } else {
                        source = tin
                        e = tin.nextEntry ?: return@next null
                    }
                    val n = ArchiveLayout.normalize(e.name)
                    when (val m = layout.map(n)) {
                        is ArchiveLayout.Mapped.Target -> {
                            val dir = e.isDirectory
                            if (e.isLink) {
                                val t = layout.map(ArchiveLayout.normalize(e.linkName))
                                if (t !is ArchiveLayout.Mapped.Target) {
                                    outside++
                                    continue
                                }
                                e.linkName = t.name
                            }
                            e.name = if (dir) "${m.name}/" else m.name
                            DistroArchive.importRejection(e)?.let { throw IOException("rejected archive entry $it") }
                            if (regular(e)) {
                                bytes += e.size
                                if (bytes - lastReport >= PROGRESS_STEP) {
                                    lastReport = bytes
                                    progress(bytes)
                                }
                            }
                            return@next e
                        }
                        ArchiveLayout.Mapped.Metadata -> if (regular(e) && e.size <= DistroArchive.MAX_JSON_BYTES) {
                            json[n] = readSmall(e, data)
                        }
                        ArchiveLayout.Mapped.Skip -> {}
                        ArchiveLayout.Mapped.Outside -> outside++
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
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
            source = tin
            if (outside > 0) log("[import] skipped $outside entries outside the root filesystem")
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
            return String(readExactly(from, e.size.toInt(), e.name), Charsets.UTF_8)
        }

        private fun readFully(e: TarArchiveEntry): ByteArray = readExactly(tin, e.size.toInt(), e.name)

        /** Up to [n] bytes of the current entry (fewer at its end). */
        private fun readUpTo(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = data.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return if (off == n) buf else buf.copyOf(off)
        }

        private fun readExactly(from: InputStream, size: Int, name: String): ByteArray {
            val buf = ByteArray(size)
            var off = 0
            while (off < buf.size) {
                val n = from.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("archive is truncated in $name")
                off += n
            }
            return buf
        }
    }

    /**
     * Post-extract setup for a custom rootfs, a light idempotent stand-in
     * for `Distro.configure`: DNS, `/tmp`, `/root`, the bash stubs. Nothing
     * package-manager specific.
     */
    fun setupCustom(rootfs: File, facts: RootfsFacts, log: (String) -> Unit, fs: FsOps = AndroidFsOps) {
        fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
        // 0644: the app's umask would make new files owner-only.
        fun write(f: File, text: String) {
            f.writeText(text)
            fs.chmod(f.path, MODE_0644)
        }
        val resolv = File(rootfs, "etc/resolv.conf")
        val dangling = Files.isSymbolicLink(resolv.toPath()) &&
            RootShell.resolveInRootfs(rootfs, "/etc/resolv.conf")?.isFile != true
        if (dangling || !exists(resolv)) {
            if (dangling) Files.delete(resolv.toPath())
            resolv.parentFile?.mkdirs()
            write(resolv, "nameserver 8.8.8.8\n")
            log("[import] wrote /etc/resolv.conf")
        }
        val tmp = File(rootfs, "tmp")
        if (!exists(tmp)) {
            tmp.mkdirs()
            fs.chmod(tmp.path, STICKY_1777)
        }
        val root = File(rootfs, "root")
        if (!exists(root)) {
            root.mkdirs()
            fs.chmod(root.path, MODE_0700)
        }
        if (facts.hasBash && root.isDirectory) {
            val rc = File(root, ".bashrc")
            if (!exists(rc)) write(rc, ShellDefaults.BASHRC_STUB)
            val profile = File(root, ".bash_profile")
            if (!exists(profile)) write(profile, ShellDefaults.BASH_PROFILE_STUB)
        }
        val passwd = File(rootfs, "etc/passwd")
        val hasRoot = runCatching { passwd.readLines().any { it.startsWith("root:") } }.getOrDefault(false)
        if (!hasRoot) log("[import] /etc/passwd has no root entry; terminals use ${RootShell.command(rootfs)}")
    }

    /**
     * The whole import job: classification, INSTALLING record, extract
     * (+ trailer check for an export), the custom setup for a custom
     * distro, then the same app-file refresh an install does. The
     * caller has verified the slot is empty; any throw after the
     * record is written leaves a FAILED slot for the normal Delete.
     * [sourceName] is the picked file's name, recorded for custom
     * distros.
     */
    fun import(
        context: Context,
        store: InstallationStore,
        newId: String,
        label: String?,
        sourceName: String,
        input: InputStream,
        log: (String) -> Unit,
        progress: (String, Int?) -> Unit,
    ) {
        val hostAbi = HostArch.primaryAbi()
        val reader = Reader(input)
        val header = reader.openExport()
        if (store.installationDir(newId).exists()) throw IOException("'$newId' already exists")
        val installDir = store.installationDir(newId)
        fun report(done: Long, total: Long) {
            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null
            val msg = if (total > 0) {
                context.getString(
                    me.phie.tawc.R.string.import_progress_extracting,
                    me.phie.tawc.install.util.HumanSize.format(done),
                    me.phie.tawc.install.util.HumanSize.format(total),
                )
            } else {
                context.getString(
                    me.phie.tawc.R.string.import_progress_extracting_unknown,
                    me.phie.tawc.install.util.HumanSize.format(done),
                )
            }
            progress(msg, pct)
        }

        val record: Installation
        var facts: RootfsFacts? = null
        if (header != null) {
            incompatibility(header, hostAbi)?.let { throw IOException(it) }
            val m = header.manifest
            log("[import] ${m.distro}/${m.arch} '${m.label ?: m.id}' from ${m.sourcePackage} " +
                "v${m.appVersionName}, ${m.entries} entries")
            if (!knownDistro(header.metadata.distro, hostAbi)) {
                log("[import] '${header.metadata.distro}' is not a distro this app supports")
            }
            record = rewrite(
                header.metadata, newId, label, m.sourcePackage, System.currentTimeMillis(),
                AllFilesAccess.declared(context),
            )
            val dropped = header.metadata.externalBinds.size - record.externalBinds.size
            if (dropped > 0) log("[import] dropped $dropped shared-storage bind(s): this build has no all-files access")
            store.save(record)
            reader.extractTo(installDir, log, { report(it, m.uncompressedBytes) })
        } else {
            reader.lookahead()
            val scan = reader.classify(hostAbi)
            val layout = scan.layout!!
            val target = Target(
                id = newId,
                label = label,
                hostAbi = hostAbi,
                sourceName = sourceName,
                nowMillis = System.currentTimeMillis(),
                appVersionCode = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) { 0L },
                allFilesDeclared = AllFilesAccess.declared(context),
            )
            if (scan.kind == Kind.DAMAGED_EXPORT) {
                log("[import] damaged TAWC export (${scan.problems.joinToString("; ")}), importing as-is")
            } else {
                log("[import] plain rootfs, root at '/${layout.root}'")
            }
            store.save(looseRecord(target, scan.metadata, scan.manifest, scan.facts))
            reader.extractLoose(installDir, layout, log, { report(it, 0) })

            val rootfs = store.rootfsDir(newId)
            fun has(rel: String) = Files.exists(File(rootfs, rel).toPath(), LinkOption.NOFOLLOW_LINKS)
            if (!has("etc") || !(has("usr") || has("bin"))) {
                throw IOException("no Linux root filesystem found (a directory with etc/ and usr/ or bin/)")
            }
            val probed = RootfsFacts.probe(rootfs).also { facts = it }
            probed.refusal(hostAbi)?.let { throw IOException(it) }
            record = looseRecord(target, reader.metadataFor(layout) ?: scan.metadata, scan.manifest, probed)
            store.save(record)
            log("[import] ${probed.osName ?: "no os-release"}, ${probed.libc ?: "unknown libc"}" +
                if (probed.hasBash) "" else ", no bash (using /bin/sh)")
            if (probed.libc == RootfsFacts.LIBC_MUSL) log("[import] musl libc: GPU acceleration is unavailable")
        }

        val storeDir = File(installDir, DistroArchive.STORE)
        if (storeDir.isDirectory) {
            File(storeDir, "tmp").mkdirs()
            val ver = File(storeDir, "version").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (ver != null && ver > DistroArchive.LINK_STORE_VERSION) {
                log("[import] link store v$ver is newer than this app's v${DistroArchive.LINK_STORE_VERSION}; " +
                    "hardlinks stay readable but new ones can't be made")
            }
        }
        val rootfs = store.rootfsDir(newId)
        if (record.distro == Installation.DISTRO_CUSTOM) {
            setupCustom(rootfs, facts ?: RootfsFacts.probe(rootfs), log)
        }
        val tmp = File(rootfs, "tmp")
        if (!tmp.isDirectory) {
            tmp.mkdirs()
            AndroidFsOps.chmod(tmp.path, STICKY_1777)
        }

        TawcInstaller.installInto(context, store, newId, log)
        store.setState(newId, Installation.State.READY)
        AndoBrokers.refresh(context)
    }

    /** A regular file: commons-compress's `isFile` is also true for
     *  hardlinks and symlinks. */
    private fun regular(e: TarArchiveEntry) = e.isFile && !e.isLink && !e.isSymbolicLink

    private const val PROGRESS_STEP = 8L shl 20
    private const val LOOKAHEAD_ENTRIES = 4096
    private const val LOOKAHEAD_BYTES = 16L shl 20
    private const val MAX_JSON_ENTRIES = 16
    private const val STICKY_1777 = 1023 // 0o1777
    private const val MODE_0700 = 448
    private const val MODE_0644 = 420
}
