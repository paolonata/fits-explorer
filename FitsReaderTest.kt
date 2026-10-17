package it.fits.explorer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*
import org.junit.Test

class FitsReaderTest {
    private fun fixture(bits: Int = 16, width: Int = 2, height: Int = 2, extra: Map<String, String> = emptyMap(), write: DataOutputStream.() -> Unit): ByteArray {
        val cards = linkedMapOf("SIMPLE" to "T", "BITPIX" to "$bits", "NAXIS" to "2", "NAXIS1" to "$width", "NAXIS2" to "$height") + extra
        val text = cards.entries.joinToString("") { (k, v) -> (k.padEnd(8) + "= " + v).padEnd(80) } + "END".padEnd(80)
        val out = ByteArrayOutputStream()
        out.write(text.padEnd((text.length + 2879) / 2880 * 2880).toByteArray(Charsets.US_ASCII))
        write.invoke(DataOutputStream(out))
        return out.toByteArray()
    }
    private fun read(bytes: ByteArray) = FitsReader.read(ByteArrayInputStream(bytes))
    @Test fun quotedSlashEscapedQuoteAndExponent() {
        val image = read(fixture(extra = mapOf("OBJECT" to "'M31 / Andromeda''s' / comment", "BSCALE" to "2D+0")) { repeat(4) { writeShort(it) } })
        assertEquals("M31 / Andromeda's", image.header["OBJECT"])
        assertContentEquals(floatArrayOf(0f, 2f, 4f, 6f), image.pixels)
    }
    @Test fun unsigned16BigEndianScaling() {
        val image = read(fixture(extra = mapOf("BZERO" to "32768", "BSCALE" to "2")) { writeShort(-32768); writeShort(-1); writeShort(0); writeShort(32767) })
        assertEquals(-32768.0, image.physicalOffset)
        assertContentEquals(floatArrayOf(0f, 65534f, 65536f, 131070f), image.pixels)
    }
    @Test fun unsigned32RetainsSmallDifferences() {
        val image = read(fixture(bits = 32, extra = mapOf("BZERO" to "2147483648")) { repeat(4) { writeInt(it) } })
        assertEquals(2147483648.0, image.physicalOffset)
        assertContentEquals(floatArrayOf(0f, 1f, 2f, 3f), image.pixels)
    }
    @Test fun float32And64() {
        for (bits in listOf(-32, -64)) {
            val image = read(fixture(bits = bits) { for (v in listOf(-2.5, 0.0, 7.25, Double.NaN)) if (bits == -32) writeFloat(v.toFloat()) else writeDouble(v) })
            assertContentEquals(floatArrayOf(0f, 2.5f, 9.75f, Float.NaN), image.pixels)
        }
    }
    @Test fun unsigned8AndNegativeScale() {
        val image = read(fixture(bits = 8, extra = mapOf("BSCALE" to "-2")) { write(byteArrayOf(0, 1, 127, -1)) })
        assertContentEquals(floatArrayOf(0f, -2f, -254f, -510f), image.pixels)
    }
    @Test fun blankAndNonfinitePixels() {
        val image = read(fixture(extra = mapOf("BLANK" to "-32768")) { writeShort(-32768); writeShort(7); writeShort(8); writeShort(9) })
        assertTrue(image.pixels[0].isNaN()); assertEquals(7.0, image.physicalOffset)
        assertFailsWith<FitsException> { read(fixture(bits = -32) { repeat(4) { writeFloat(Float.NaN) } }) }
    }
    @Test fun dimensionsAndUnsupportedFormatAreRejected() {
        for (extra in listOf(mapOf("NAXIS" to "3"), mapOf("NAXIS1" to "-1"), mapOf("NAXIS1" to "2147483647"), mapOf("BAYERPAT" to "'CYGM'"), mapOf("BSCALE" to "NaN")))
            assertFailsWith<FitsException> { read(fixture(extra = extra) { repeat(4) { writeShort(it) } }) }
        assertFailsWith<FitsException> { read(fixture(bits = 64) { repeat(4) { writeLong(0) } }) }
    }
    @Test fun truncatedHeaderMissingEndAndTruncatedData() {
        assertFailsWith<FitsException> { read(ByteArray(20)) }
        val valid = fixture { repeat(4) { writeShort(it) } }
        assertFailsWith<FitsException> { read(valid.copyOf(valid.size - 1)) }
        val noEnd = valid.copyOf(2880).toString(Charsets.US_ASCII).replace("END", "   ").toByteArray()
        assertFailsWith<FitsException> { read(noEnd) }
    }
    @Test fun bayerOffsetsAndRowOrder() {
        val image = read(fixture(extra = mapOf("BAYERPAT" to "'RGGB'", "XBAYROFF" to "1", "YBAYROFF" to "1", "ROWORDER" to "'TOP-DOWN'")) { repeat(4) { writeShort(it) } })
        assertEquals("RGGB", image.pattern); assertEquals(1, image.xOffset); assertFalse(image.bottomUp)
    }
    @Test fun cancellationPropagates() {
        assertFailsWith<IllegalStateException> { FitsReader.read(ByteArrayInputStream(fixture { repeat(4) { writeShort(it) } })) { error("cancel") } }
    }
}
