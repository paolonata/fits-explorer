package it.fits.explorer

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import it.fits.explorer.core.Adjustments
import it.fits.explorer.core.PreparedImage
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JpegExporter {
    fun write(image: PreparedImage, adjustments: Adjustments, output: OutputStream, checkCancelled: () -> Unit = {}) {
        val bitmap = Bitmap.createBitmap(image.source.width, image.source.height, Bitmap.Config.ARGB_8888)
        try {
            image.renderRows(adjustments, checkCancelled) { y, row -> bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1) }
            checkCancelled()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw IOException("Impossibile creare il JPG")
            output.flush()
        } finally { bitmap.recycle() }
    }
    fun save(resolver: ContentResolver, image: PreparedImage, adjustments: Adjustments, sourceName: String, checkCancelled: () -> Unit = {}): Uri {
        val stem = sourceName.substringBeforeLast('.', sourceName).replace(Regex("[^\\p{L}\\p{N}_. -]"), "_").take(100).ifBlank { "FITS" }
        val date = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$stem-$date.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FITS Explorer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Galleria non disponibile")
        try {
            resolver.openOutputStream(uri, "w")?.use { write(image, adjustments, it, checkCancelled) }
                ?: throw IOException("Impossibile aprire la destinazione")
            checkCancelled()
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            if (published != 1) throw IOException("Impossibile pubblicare il JPG in Galleria")
            return uri
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { /* Preserve original failure. */ }
            throw e
        }
    }
}
