package me.phie.tawc.install

/**
 * Where the guest root sits inside an import archive that isn't a
 * well-framed export, and how its entry names map onto the install
 * dir (notes/installation.md "Custom distros").
 *
 * [root] is the normalized name prefix of the guest root (`""`,
 * `"root.x86_64/"`, …). When it ends in `rootfs/` the archive has the
 * TAWC layout ([tawc]): `<base>rootfs/`, `<base>tawcroot/` and
 * `<base>metadata.json` — a re-packed export, or a plain tarball
 * wrapped in a dir that happens to be called `rootfs`, which maps the
 * same way.
 */
internal data class ArchiveLayout(val root: String) {
    val tawc: Boolean get() = root == "${DistroArchive.ROOTFS}/" || root.endsWith("/${DistroArchive.ROOTFS}/")
    val base: String get() = if (tawc) root.removeSuffix("${DistroArchive.ROOTFS}/") else root

    sealed interface Mapped {
        /** Extract as [name] (install-dir relative, normalized). */
        data class Target(val name: String) : Mapped
        object Metadata : Mapped
        /** A TAWC framing entry or the wrapper's own parent: ignored. */
        object Skip : Mapped
        object Outside : Mapped
    }

    /** [n] is [normalize]d. */
    fun map(n: String): Mapped {
        if (tawc) {
            if (base.isNotEmpty() && n == base.removeSuffix("/")) return Mapped.Skip
            if (!n.startsWith(base)) return Mapped.Outside
            val rel = n.removePrefix(base)
            return when {
                rel == DistroArchive.METADATA -> Mapped.Metadata
                rel == DistroArchive.MANIFEST || rel == DistroArchive.TRAILER || rel.isEmpty() -> Mapped.Skip
                DistroArchive.ROOTS.any { rel == it || rel.startsWith("$it/") } -> Mapped.Target(rel)
                else -> Mapped.Outside
            }
        }
        if (n == root.removeSuffix("/")) return Mapped.Target(DistroArchive.ROOTFS)
        if (!n.startsWith(root)) return Mapped.Outside
        return Mapped.Target("${DistroArchive.ROOTFS}/${n.removePrefix(root)}")
    }

    /**
     * Finds [ArchiveLayout] from entry names: the shallowest prefix (at
     * most [MAX_DEPTH] dirs deep) holding `etc` plus `usr` or `bin`.
     * Also notes TAWC framing entries (for the damaged-export kind)
     * and `docker save` / OCI image layouts (refused).
     */
    class Detector {
        private val etc = HashSet<String>()
        private val usrOrBin = HashSet<String>()
        /** Satisfied candidates in the order they became satisfied. */
        private val satisfied = LinkedHashSet<String>()
        private val tops = LinkedHashSet<String>()
        /** Base prefixes of TAWC framing entries seen. */
        val markers = HashSet<String>()
        private var ociManifest = false
        private var ociLayers = false
        var count = 0L
            private set

        /** [n] is [normalize]d; [dir] says it's a directory entry. */
        fun add(n: String, dir: Boolean) {
            count++
            if (n.isEmpty()) return
            val parts = n.split('/')
            // Only dirs can be a wrapper.
            if (tops.size < MAX_TOPS && (dir || parts.size > 1)) tops += parts[0]
            for (d in 0..minOf(MAX_DEPTH, parts.size - 1)) {
                val c = parts[d]
                val isEtc = c == "etc"
                if (!isEtc && c != "usr" && c != "bin") continue
                val prefix = parts.subList(0, d).joinToString("") { "$it/" }
                if (isEtc) etc += prefix else usrOrBin += prefix
                if (prefix in etc && prefix in usrOrBin) satisfied += prefix
            }
            val leaf = parts.last()
            if (parts.size <= MAX_DEPTH + 1 &&
                (leaf == DistroArchive.MANIFEST || leaf == DistroArchive.TRAILER || leaf == DistroArchive.METADATA)
            ) {
                markers += n.removeSuffix(leaf)
            }
            if (n == "manifest.json" || n == "oci-layout" || n == "index.json") ociManifest = true
            if (n.endsWith("/layer.tar") || n.startsWith("blobs/")) ociLayers = true
        }

        /** The guest root is known for sure (the top level qualifies). */
        val certain: Boolean get() = "" in satisfied

        /**
         * The layout, or null if there's no guest root. With
         * [complete] false only part of the archive was seen: lacking a
         * qualifying prefix, guess from the top-level dirs (one non-FHS
         * dir = a wrapper) and let the post-extract check judge.
         */
        fun decide(complete: Boolean): ArchiveLayout? {
            satisfied.minByOrNull { it.count { c -> c == '/' } }?.let { return ArchiveLayout(it) }
            if (complete || count == 0L) return null
            val only = tops.singleOrNull()
            return ArchiveLayout(if (only != null && only !in FHS_TOP) "$only/" else "")
        }

        /** Whether [layout]'s base holds TAWC framing entries. */
        fun hasMarkers(layout: ArchiveLayout): Boolean = layout.tawc && layout.base in markers

        /** Why there is no usable root, for a [decide] that returned null. */
        fun unrecognized(): String = if (ociManifest && ociLayers) {
            "this is a container image (docker save / OCI layout); export a container instead: " +
                "docker export \$(docker create <image>) > rootfs.tar"
        } else {
            "no Linux root filesystem found (a directory with etc/ and usr/ or bin/)"
        }
    }

    companion object {
        const val MAX_DEPTH = 2
        private const val MAX_TOPS = 64
        private val FHS_TOP = setOf(
            "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32", "media", "mnt",
            "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var",
        )

        /** Archive entry name without leading `/` / `./` runs or a
         *  trailing `/`; `.` and `./` become `""`. */
        fun normalize(raw: String): String {
            var n = raw
            while (true) {
                n = when {
                    n.startsWith("/") -> n.substring(1)
                    n.startsWith("./") -> n.substring(2)
                    else -> break
                }
            }
            n = n.trimEnd('/')
            return if (n == ".") "" else n
        }
    }
}
