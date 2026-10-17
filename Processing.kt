package it.fits.explorer.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class Adjustments(val black: Float = 0f, val white: Float = 1f, val gamma: Float = 1f, val stretch: Float = 5f) {
    init {
        require(black.isFinite() && white.isFinite() && black >= 0 && white <= 1 && white > black)
        require(gamma.isFinite() && gamma in 0.2f..3f && stretch.isFinite() && stretch in 0f..12f)
    }
}
data class Levels(val low: Double, val high: Double)
data class Preview(val width: Int, val height: Int, val channels: Int, val data: FloatArray)

object Processing {
    fun levels(values: FloatArray): Levels {
        val stride = max(1, values.size / 140_000)
        val sample = values.indices.step(stride).map { values[it] }.filter { it.isFinite() }.sorted()
        if (sample.isEmpty()) throw FitsException("Nessun pixel valido")
        var low = sample[((sample.size - 1) * .001).toInt()].toDouble()
        var high = sample[((sample.size - 1) * .9995).toInt()].toDouble()
        if (high <= low) { low = sample.first().toDouble(); high = sample.last().toDouble() }
        if (high <= low) high = low + max(1.0, abs(low) * 1e-6)
        return Levels(low, high)
    }
    /** Bilinear RGGB (and translated Bayer tiles); measured samples are preserved. */
    fun demosaic(image: FitsImage, x: Int, y: Int, out: FloatArray) {
        val pattern = requireNotNull(image.pattern)
        fun channel(xx: Int, yy: Int): Int = when (pattern[((yy + image.yOffset) and 1) * 2 + ((xx + image.xOffset) and 1)]) {
            'R' -> 0; 'G' -> 1; else -> 2
        }
        val measured = channel(x, y)
        for (c in 0..2) {
            val center = image.pixels[y * image.width + x]
            if (c == measured && center.isFinite()) { out[c] = center; continue }
            var total = 0.0; var count = 0
            for (yy in max(0, y - 1)..min(image.height - 1, y + 1))
                for (xx in max(0, x - 1)..min(image.width - 1, x + 1)) {
                    val v = image.pixels[yy * image.width + xx]
                    if (channel(xx, yy) == c && v.isFinite()) { total += v; count++ }
                }
            out[c] = if (count == 0) Float.NaN else (total / count).toFloat()
        }
    }
    /** SCNR average-neutral, amount=1, matching the supplied HTML. */
    fun scnr(r: Float, g: Float, b: Float): Float = if (r.isFinite() && g.isFinite() && b.isFinite()) min(g, (r.toDouble() * .5 + b.toDouble() * .5).toFloat()) else g
    fun lut(a: Adjustments): IntArray = IntArray(4096) { index ->
        var t = index / 4095.0
        if (a.stretch > 0) t = asinh(a.stretch * t) / asinh(a.stretch.toDouble())
        (255 * t.pow(1.0 / a.gamma)).roundToInt().coerceIn(0, 255)
    }
}

/** Cached linear demosaic on private disk keeps full-size RGB off the Java heap.
 * The original linear samples remain in memory and are never mutated. */
class PreparedImage private constructor(
    val source: FitsImage, val levels: Levels, val preview: Preview, private val cache: File
) : Closeable {
    val channels = if (source.pattern == null) 1 else 3
    fun renderer(a: Adjustments) = Renderer(levels, a, channels == 3)
    fun renderPreview(a: Adjustments, checkCancelled: () -> Unit = {}): IntArray {
        val render = renderer(a)
        return IntArray(preview.width * preview.height) { i ->
            if (i % preview.width == 0) checkCancelled()
            val j = i * channels
            render.pixel(preview.data[j], preview.data[j + if (channels == 3) 1 else 0], preview.data[j + if (channels == 3) 2 else 0])
        }
    }
    /** Output rows are always in display order, independent of zoom or preview. */
    fun renderRows(a: Adjustments, checkCancelled: () -> Unit = {}, consume: (Int, IntArray) -> Unit) {
        val render = renderer(a)
        val row = IntArray(source.width)
        val bytes = ByteArray(source.width * channels * 4)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
        RandomAccessFile(cache, "r").use { input ->
            for (y in 0 until source.height) {
                checkCancelled()
                val sy = if (source.bottomUp) source.height - 1 - y else y
                input.seek(sy.toLong() * bytes.size)
                input.readFully(bytes); floats.position(0)
                for (x in row.indices) {
                    val r = floats.get(); val g = if (channels == 3) floats.get() else r; val b = if (channels == 3) floats.get() else r
                    row[x] = render.pixel(r, g, b)
                }
                consume(y, row)
            }
        }
    }
    override fun close() { cache.delete() }
    companion object {
        fun prepare(source: FitsImage, cacheDir: File, previewMax: Int = 1500, checkCancelled: () -> Unit = {}): PreparedImage {
            require(previewMax > 0)
            val levels = Processing.levels(source.pixels)
            val channels = if (source.pattern == null) 1 else 3
            val factor = min(1.0, previewMax.toDouble() / max(source.width, source.height))
            val pw = max(1, (source.width * factor).roundToInt()); val ph = max(1, (source.height * factor).roundToInt())
            val preview = Preview(pw, ph, channels, FloatArray(pw * ph * channels))
            val file = File.createTempFile("linear-", ".rgb", cacheDir)
            try {
                RandomAccessFile(file, "rw").use { output ->
                    val bytes = ByteArray(source.width * channels * 4)
                    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
                    val rgb = FloatArray(3)
                    var py = 0
                    for (y in 0 until source.height) {
                        checkCancelled(); floats.position(0)
                        for (x in 0 until source.width) {
                            if (channels == 3) { Processing.demosaic(source, x, y, rgb); floats.put(rgb) }
                            else floats.put(source.pixels[y * source.width + x])
                        }
                        output.write(bytes)
                        if (py < ph && y == (py.toLong() * source.height / ph).toInt()) {
                            val dy = if (source.bottomUp) ph - 1 - py else py
                            for (px in 0 until pw) {
                                val sx = (px.toLong() * source.width / pw).toInt()
                                for (c in 0 until channels) preview.data[(dy * pw + px) * channels + c] = floats.get(sx * channels + c)
                            }
                            py++
                        }
                    }
                }
                return PreparedImage(source, levels, preview, file)
            } catch (e: Throwable) { file.delete(); throw e }
        }
    }
}
class Renderer(levels: Levels, a: Adjustments, private val color: Boolean) {
    private val black = levels.low + (levels.high - levels.low) * a.black
    private val white = levels.low + (levels.high - levels.low) * a.white
    private val inv = 4095.0 / (white - black)
    private val lut = Processing.lut(a)
    fun value(v: Float): Int = if (!v.isFinite()) 0 else lut[((v.toDouble() - black) * inv).coerceIn(0.0, 4095.0).roundToInt()]
    fun pixel(r: Float, g: Float, b: Float): Int {
        val green = if (color) Processing.scnr(r, g, b) else g
        return (0xff shl 24) or (value(r) shl 16) or (value(green) shl 8) or value(b)
    }
}
