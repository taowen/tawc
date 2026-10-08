package me.phie.tawc.launcher

import me.phie.tawc.launcher.IconImport.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class IconImportTest {

    private val a = byteArrayOf(1, 2, 3)
    private val b = byteArrayOf(4, 5, 6)

    private fun choose(kind: Kind, bytes: ByteArray, files: Map<String, ByteArray>) =
        IconImport.chooseName("cat", kind, bytes) { name, k -> files["$name.${k.ext}"] }

    @Test
    fun slugDropsExtensionAndFallsBack() {
        assertEquals("my-cat", IconImport.slugFor("My Cat.PNG"))
        assertEquals("archive-tar", IconImport.slugFor("archive.tar.gz"))
        assertEquals("icon", IconImport.slugFor("!!!.png"))
        assertEquals("icon", IconImport.slugFor(null))
        assertEquals("bashrc", IconImport.slugFor(".bashrc"))
    }

    @Test
    fun freeNameTakesNoSuffix() {
        assertEquals("tawc-cat", choose(Kind.PNG, a, emptyMap()))
    }

    @Test
    fun collisionCountsUpAcrossBothExtensions() {
        val files = mapOf("tawc-cat.png" to b, "tawc-cat-2.svg" to a)
        assertEquals("tawc-cat-3", choose(Kind.PNG, a, files))
    }

    @Test
    fun identicalBytesReuseTheName() {
        val files = mapOf("tawc-cat.png" to b, "tawc-cat-2.png" to a)
        assertEquals("tawc-cat-2", choose(Kind.PNG, a, files))
    }

    @Test
    fun sameBytesInOtherKindDontCount() {
        val files = mapOf("tawc-cat.svg" to a)
        assertEquals("tawc-cat-2", choose(Kind.PNG, a, files))
    }

    @Test
    fun svgDetection() {
        assertEquals(true, IconImport.isSvg("image/svg+xml", "x"))
        assertEquals(true, IconImport.isSvg("application/octet-stream", "Logo.SVG"))
        assertEquals(false, IconImport.isSvg("image/png", "logo.png"))
    }

    @Test
    fun fitNeverUpscales() {
        assertEquals(64 to 32, IconImport.fitWithin(64, 32, 256))
        assertEquals(256 to 128, IconImport.fitWithin(1024, 512, 256))
        assertEquals(128 to 256, IconImport.fitWithin(500, 1000, 256))
        assertEquals(256 to 1, IconImport.fitWithin(5000, 1, 256))
    }
}
