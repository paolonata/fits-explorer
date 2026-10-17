package it.fits.explorer.core

import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class ProcessingTest {
    private fun image(width: Int = 4, height: Int = 4, pattern: String? = "RGGB", bottomUp: Boolean = false): FitsImage {
        val p = FloatArray(width * height) { i ->
            if (pattern == null) i.toFloat() else when (pattern[(i / width and 1) * 2 + (i % width and 1)]) { 'R' -> 10f; 'G' -> 80f; else -> 30f }
        }
        return FitsImage(width, height, p, emptyMap(), 0.0, pattern, 0, 0, bottomUp)
    }
    @Test fun allBayerTilesAndEdgesReconstructConstantColor() {
        for (pattern in listOf("RGGB", "BGGR", "GRBG", "GBRG")) {
            val image = image(pattern = pattern)
            val original = image.pixels.copyOf()
            for (y in 0..3) for (x in 0..3) {
                val rgb = FloatArray(3); Processing.demosaic(image, x, y, rgb)
                assertContentEquals(floatArrayOf(10f, 80f, 30f), rgb)
            }
            assertContentEquals(original, image.pixels)
        }
    }
    @Test fun bayerMeasuredSampleIsNotBlurred() {
        val image = image(); image.pixels[0] = 900f
        val rgb = FloatArray(3); Processing.demosaic(image, 0, 0, rgb)
        assertEquals(900f, rgb[0])
    }
    @Test fun scnrOnlyRemovesExcessGreen() {
        assertEquals(20f, Processing.scnr(10f, 80f, 30f))
        assertEquals(5f, Processing.scnr(10f, 5f, 30f))
        assertEquals(80f, Processing.scnr(Float.NaN, 80f, 30f))
    }
    @Test fun stretchExtremesMonotonicAndGamma() {
        for (stretch in listOf(0f, 5f, 12f)) for (gamma in listOf(.2f, 1f, 3f)) {
            val lut = Processing.lut(Adjustments(gamma = gamma, stretch = stretch))
            assertEquals(0, lut.first()); assertEquals(255, lut.last())
            assertTrue(lut.asList().zipWithNext().all { it.first <= it.second })
        }
        val render = Renderer(Levels(-10.0, 20.0), Adjustments(stretch = 0f), false)
        assertEquals(0, render.value(-100f)); assertEquals(255, render.value(100f))
        assertEquals(0, render.value(Float.NaN)); assertEquals(0, render.value(Float.POSITIVE_INFINITY))
        assertEquals(128, render.value(5f))
        assertFailsWith<IllegalArgumentException> { Adjustments(black = 1f, white = 1f) }
    }
    @Test fun levelsHandleConstantNegativeAndInvalid() {
        assertTrue(Processing.levels(FloatArray(20) { -5f }).let { it.high > it.low })
        assertEquals(Levels(-10.0, -5.0), Processing.levels(floatArrayOf(-10f, -5f, -2f)))
        assertFailsWith<FitsException> { Processing.levels(floatArrayOf(Float.NaN)) }
    }
    @Test fun cacheFullResolutionAndPreviewUseSameAdjustments() {
        val dir = Files.createTempDirectory("fits-test").toFile()
        try {
            val source = image()
            PreparedImage.prepare(source, dir).use { prepared ->
                for (a in listOf(Adjustments(), Adjustments(.1f, .9f, 2f, 9f))) {
                    val preview = prepared.renderPreview(a)
                    val full = mutableListOf<Int>()
                    prepared.renderRows(a) { _, row -> full.addAll(row.toList()) }
                    assertContentEquals(preview, full.toIntArray())
                    assertEquals(source.width * source.height, full.size)
                }
                assertTrue(dir.listFiles()!!.isNotEmpty())
            }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun monoIsNeutralAndFitsOrientationIsApplied() {
        val dir = Files.createTempDirectory("fits-test").toFile()
        try {
            PreparedImage.prepare(image(pattern = null, bottomUp = true), dir).use { prepared ->
                val rows = mutableListOf<IntArray>()
                prepared.renderRows(Adjustments(stretch = 0f)) { _, row -> rows.add(row.copyOf()) }
                val pixels = prepared.renderPreview(Adjustments(stretch = 0f))
                assertContentEquals(rows.flatMap { it.toList() }.toIntArray(), pixels)
                assertTrue((rows.first().first() and 255) > (rows.last().first() and 255))
                for (pixel in pixels) assertEquals(pixel and 255, pixel shr 8 and 255)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun reducedPreviewAndCancellationCleanUpCache() {
        val dir = Files.createTempDirectory("fits-test").toFile()
        try {
            PreparedImage.prepare(image(100, 80, null), dir, 30).use {
                assertEquals(30, it.preview.width); assertEquals(24, it.preview.height)
                var rows = 0; it.renderRows(Adjustments()) { _, row -> assertEquals(100, row.size); rows++ }; assertEquals(80, rows)
            }
            assertFailsWith<IllegalStateException> { PreparedImage.prepare(image(), dir) { error("cancel") } }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
