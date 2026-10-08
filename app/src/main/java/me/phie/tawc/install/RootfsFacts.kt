package me.phie.tawc.install

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * What a custom rootfs looks like from outside: os-release name, CPU
 * architecture (`e_machine` of the first ELF in `bin/` / `usr/bin/`),
 * libc (dynamic loader name) and whether the programs every spawn
 * needs exist. Built by [Observer] from tar entries at form time and
 * from the extracted tree after import ([probe]).
 */
internal data class RootfsFacts(
    val osName: String?,
    val osId: String?,
    /** Android ABI (`arm64-v8a`, `x86_64`, …), `e_machine <n>` for an
     *  unknown machine, or null if no ELF was seen. */
    val abi: String?,
    val libc: String?,
    val hasBash: Boolean,
    val hasSh: Boolean,
    val hasEnv: Boolean,
) {
    /** Why nothing can run in this rootfs on a [hostAbi] phone, or null. */
    fun refusal(hostAbi: String): String? = when {
        abi != null && abi != hostAbi ->
            "built for ${linuxArch(abi)}, but this phone is ${linuxArch(hostAbi)} (no emulation)"
        !hasEnv -> "no /usr/bin/env, which TAWC starts every program through"
        !hasBash && !hasSh -> "no /bin/sh or /bin/bash"
        else -> null
    }

    /**
     * Collects the entries that matter, by full archive-normalized name
     * ([ArchiveLayout.normalize]); the guest-root prefix is only known
     * once the whole archive was seen, so [finish] picks the ones under
     * it. [wants] says how many content bytes [content] should get.
     */
    class Observer {
        private val osRelease = HashMap<String, String>()
        private val elf = ArrayList<Pair<String, String?>>()
        private val perBinDir = HashMap<String, Int>()
        private val loaders = ArrayList<String>()
        private val present = HashSet<String>()

        fun wants(name: String, regular: Boolean): Int {
            if (!regular) return 0
            if (isOsRelease(name)) return OS_RELEASE_MAX
            val dir = name.substringBeforeLast('/', "")
            if (isBinDir(dir) && elf.size < MAX_ELF && (perBinDir[dir] ?: 0) < ELF_TRIES_PER_DIR) return ELF_HEAD
            return 0
        }

        fun entry(name: String) {
            val base = name.substringAfterLast('/')
            if (base.startsWith("ld-linux-") || base.startsWith("ld-musl-")) {
                if (loaders.size < MAX_LOADERS) loaders += name
            }
            if (base == "bash" || base == "sh" || base == "env") present += name
        }

        fun content(name: String, bytes: ByteArray) {
            if (isOsRelease(name)) {
                if (osRelease.size < MAX_OS_RELEASE) osRelease[name] = String(bytes, Charsets.UTF_8)
                return
            }
            val dir = name.substringBeforeLast('/', "")
            perBinDir[dir] = (perBinDir[dir] ?: 0) + 1
            elf += name to elfAbi(bytes)
        }

        /** Facts for the guest root at [root] (`""` or `"x/"`). */
        fun finish(root: String): RootfsFacts {
            val os = (osRelease[root + "etc/os-release"] ?: osRelease[root + "usr/lib/os-release"])
                ?.let(::parseOsRelease).orEmpty()
            fun has(vararg rel: String) = rel.any { (root + it) in present }
            val ld = loaders.filter { it.startsWith(root) }.map { it.substringAfterLast('/') }
            return RootfsFacts(
                osName = os["PRETTY_NAME"]?.takeIf { it.isNotBlank() } ?: os["NAME"]?.takeIf { it.isNotBlank() },
                osId = os["ID"]?.takeIf { it.isNotBlank() },
                abi = elf.firstOrNull { (n, abi) -> abi != null && n.startsWith(root) && isBinDir(n.removePrefix(root).substringBeforeLast('/', ""), exact = true) }?.second,
                libc = when {
                    ld.any { it.startsWith("ld-linux-") } -> LIBC_GLIBC
                    ld.any { it.startsWith("ld-musl-") } -> LIBC_MUSL
                    else -> null
                },
                hasBash = has("bin/bash", "usr/bin/bash"),
                hasSh = has("bin/sh", "usr/bin/sh"),
                hasEnv = has("usr/bin/env"),
            )
        }

        private fun isOsRelease(name: String) =
            name.endsWith("etc/os-release") || name.endsWith("usr/lib/os-release")

        /** A `bin` or `usr/bin` dir at any (exact = root) depth. */
        private fun isBinDir(dir: String, exact: Boolean = false): Boolean =
            if (exact) dir == "bin" || dir == "usr/bin"
            else dir == "bin" || dir == "usr/bin" || dir.endsWith("/bin") && dir.count { it == '/' } <= 3
    }

    companion object {
        const val LIBC_GLIBC = "glibc"
        const val LIBC_MUSL = "musl"

        private const val OS_RELEASE_MAX = 16 * 1024
        private const val ELF_HEAD = 20
        private const val ELF_TRIES_PER_DIR = 16
        private const val MAX_LOADERS = 64
        private const val MAX_ELF = 256
        private const val MAX_OS_RELEASE = 16

        /** Android ABI for an ELF header, `e_machine <n>` if unknown,
         *  null if [head] isn't ELF. */
        fun elfAbi(head: ByteArray): String? {
            if (head.size < ELF_HEAD || head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) return null
            val lo = head[18].toInt() and 0xFF
            val hi = head[19].toInt() and 0xFF
            val machine = if (head[5].toInt() == 2) (lo shl 8) or hi else (hi shl 8) or lo
            return when (machine) {
                183 -> "arm64-v8a"
                62 -> "x86_64"
                40 -> "armeabi-v7a"
                3 -> "x86"
                243 -> "riscv64"
                else -> "e_machine $machine"
            }
        }

        fun linuxArch(abi: String): String = when (abi) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            "armeabi-v7a" -> "arm"
            "x86" -> "i686"
            else -> abi
        }

        /** `KEY=value` lines; values unquoted (`"…"` / `'…'`, with
         *  `\` escapes inside double quotes). */
        fun parseOsRelease(text: String): Map<String, String> = buildMap {
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                var v = line.substring(eq + 1)
                if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
                    v = v.substring(1, v.length - 1).replace(Regex("""\\(.)"""), "$1")
                } else if (v.length >= 2 && v.first() == '\'' && v.last() == '\'') {
                    v = v.substring(1, v.length - 1)
                }
                put(line.substring(0, eq), v)
            }
        }

        /**
         * Facts for an extracted [rootfs], looking only at the dirs
         * [Observer] cares about. Never follows a symlinked dir (an
         * absolute target would point at the host), so a merged-usr
         * `bin -> usr/bin` is read through `usr/bin`.
         */
        fun probe(rootfs: File): RootfsFacts {
            val o = Observer()
            fun isRealDir(f: File) = Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)
            fun isRegular(f: File) = Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)
            fun feed(rel: String) {
                val f = File(rootfs, rel)
                o.entry(rel)
                val n = o.wants(rel, isRegular(f))
                if (n > 0) {
                    val bytes = runCatching { f.inputStream().use { it.readNBytesCompat(n) } }.getOrNull()
                    if (bytes != null) o.content(rel, bytes)
                }
            }
            fun list(dir: String, filter: (String) -> Boolean = { true }) {
                val d = File(rootfs, dir)
                if (!isRealDir(d)) return
                d.list()?.sorted()?.filter(filter)?.forEach { feed("$dir/$it") }
            }
            for (rel in listOf("etc/os-release", "usr/lib/os-release")) {
                if (isRegular(File(rootfs, rel))) feed(rel)
            }
            list("bin")
            list("usr/bin")
            val ld: (String) -> Boolean = { it.startsWith("ld-") }
            for (dir in listOf("lib", "lib64", "usr/lib", "usr/lib64")) {
                list(dir, ld)
                val d = File(rootfs, dir)
                if (isRealDir(d)) {
                    d.list()?.filter { it.contains("-linux-") }?.forEach { list("$dir/$it", ld) }
                }
            }
            return o.finish("")
        }

        private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return buf.copyOf(off)
        }
    }
}
