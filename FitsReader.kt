package it.fits.explorer.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

class FitsException(message: String) : java.io.IOException(message)

data class FitsImage(
    val width: Int, val height: Int, val pixels: FloatArray,
    val header: Map<String, String>, val physicalOffset: Double,
    val pattern: String?, val xOffset: Int, val yOffset: Int,
    val bottomUp: Boolean
)

/** Only reads the source stream. FITS samples are big endian, including IEEE floats. */
object FitsReader {
    const val MAX_PIXELS = 32_000_000L
    fun header(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val data = DataInputStream(input)
        var cards = 0
        while (cards < 3600) {
            val bytes = ByteArray(80)
            try { data.readFully(bytes) } catch (_: EOFException) { throw FitsException("Header FITS incompleto") }
            val card = bytes.toString(Charsets.US_ASCII)
            val key = card.substring(0, 8).trim()
            if (cards == 0 && key != "SIMPLE") throw FitsException("Il file non è un FITS primario")
            cards++
            if (key == "END") {
                val padding = (2880 - cards * 80 % 2880) % 2880
                try { data.readFully(ByteArray(padding)) } catch (_: EOFException) { throw FitsException("Header FITS troncato") }
                if (result["SIMPLE"] != "T") throw FitsException("FITS SIMPLE non valido")
                return result
            }
            if (card[8] == '=') result[key] = value(card.substring(10))
        }
        throw FitsException("Header FITS troppo grande o END assente")
    }
    private fun value(text: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'') {
                if (quoted && i + 1 < text.length && text[i + 1] == '\'') { out.append('\''); i++ }
                else quoted = !quoted
            } else if (c == '/' && !quoted) break
            else out.append(c)
            i++
        }
        if (quoted) throw FitsException("Stringa FITS non terminata")
        return out.toString().trim()
    }
    fun read(input: InputStream, checkCancelled: () -> Unit = {}): FitsImage {
        val data = DataInputStream(input.buffered(128 * 1024))
        val h = header(data)
        fun integer(key: String): Int = h[key]?.toIntOrNull() ?: throw FitsException("Header $key non valido")
        fun number(key: String, default: Double): Double = h[key]?.let {
            it.replace('D', 'E').replace('d', 'e').toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: throw FitsException("Header $key non valido")
        } ?: default
        if (integer("NAXIS") != 2) throw FitsException("Sono supportate immagini FITS 2D, non cubi o tabelle")
        val width = integer("NAXIS1"); val height = integer("NAXIS2")
        if (width < 1 || height < 1 || width.toLong() * height > MAX_PIXELS)
            throw FitsException("Dimensioni non supportate (massimo 32 megapixel)")
        val bitpix = integer("BITPIX")
        if (bitpix !in listOf(8, 16, 32, -32, -64)) throw FitsException("BITPIX $bitpix non supportato")
        if (h["ZIMAGE"] == "T" || h["GROUPS"] == "T") throw FitsException("FITS compresso o a gruppi non supportato")
        val scale = number("BSCALE", 1.0); val zero = number("BZERO", 0.0)
        val blank = if (bitpix > 0) h["BLANK"]?.toLongOrNull() else null
        val pattern = h["BAYERPAT"]?.uppercase()?.replace(" ", "")?.takeIf { it.isNotBlank() }
        if (pattern != null && pattern !in listOf("RGGB", "BGGR", "GRBG", "GBRG")) throw FitsException("Pattern Bayer $pattern non supportato")
        if (pattern != null && (width < 2 || height < 2)) throw FitsException("Immagine Bayer troppo piccola")
        val pixels = FloatArray(width * height)
        var origin: Double? = null
        try {
            for (i in pixels.indices) {
                if (i % width == 0) checkCancelled()
                val sample = when (bitpix) {
                    8 -> data.readUnsignedByte().toDouble()
                    16 -> data.readShort().toDouble()
                    32 -> data.readInt().toDouble()
                    -32 -> data.readFloat().toDouble()
                    else -> data.readDouble()
                }
                val physical = if (blank != null && sample == blank.toDouble()) Double.NaN else sample * scale + zero
                if (physical.isFinite() && origin == null) origin = physical
                // Subtract an origin in double precision before conversion: unsigned 32-bit
                // data retain small differences even with a large BZERO pedestal.
                val relative = physical - (origin ?: 0.0)
                if (relative.isFinite() && kotlin.math.abs(relative) > Float.MAX_VALUE)
                    throw FitsException("Intervallo numerico FITS troppo ampio")
                pixels[i] = relative.toFloat()
            }
        } catch (_: EOFException) { throw FitsException("Dati FITS incompleti") }
        if (origin == null) throw FitsException("Il FITS non contiene pixel validi")
        fun offset(key: String): Int = h[key]?.let { it.toIntOrNull() ?: throw FitsException("Header $key non valido") } ?: 0
        return FitsImage(width, height, pixels, h, origin, pattern, offset("XBAYROFF"), offset("YBAYROFF"), h["ROWORDER"]?.uppercase() != "TOP-DOWN")
    }
}
