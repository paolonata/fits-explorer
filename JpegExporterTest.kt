package it.fits.explorer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import it.fits.explorer.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegExporterTest {
    private fun image(dir: File): PreparedImage {
        val source = FitsImage(64, 48, FloatArray(64 * 48) { (it % 64).toFloat() }, emptyMap(), 0.0, null, 0, 0, false)
        return PreparedImage.prepare(source, dir, 16)
    }
    @Test fun jpegIsFullResolutionAndUsesTheChosenStretch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        image(context.cacheDir).use { image ->
            assertEquals(16, image.preview.width)
            val a = Adjustments(gamma = 1.7f, stretch = 8f)
            val output = ByteArrayOutputStream()
            JpegExporter.write(image, a, output)
            val bytes = output.toByteArray()
            assertEquals(0xff, bytes[0].toInt() and 255); assertEquals(0xd8, bytes[1].toInt() and 255)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap); assertEquals(64, bitmap.width); assertEquals(48, bitmap.height)
            val expected = image.renderer(a).value(32f)
            assertTrue(kotlin.math.abs(expected - (bitmap.getPixel(32, 24) and 255)) <= 4)
            bitmap.recycle()
        }
    }
    @Test fun mediaStorePublishesInGalleryAndPreservesSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingProvider(File(context.cacheDir, "gallery-test.jpg"))
        ShadowContentResolver.registerProviderInternal("media", provider)
        image(context.cacheDir).use { image ->
            val original = image.source.pixels.copyOf()
            val uri = JpegExporter.save(context.contentResolver, image, Adjustments(), "test.fit")
            assertEquals(provider.uri, uri)
            assertEquals("Pictures/FITS Explorer", provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
            assertTrue(provider.published); assertFalse(provider.deleted)
            assertArrayEquals(original, image.source.pixels, 0f)
            val bitmap = BitmapFactory.decodeFile(provider.file.path)
            assertEquals(64, bitmap.width); bitmap.recycle()
        }
        provider.file.delete()
    }
    @Test fun failedExportRemovesPendingMediaStoreEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingProvider(File(context.cacheDir, "failed.jpg"), fail = true)
        ShadowContentResolver.registerProviderInternal("media", provider)
        image(context.cacheDir).use { image ->
            try { JpegExporter.save(context.contentResolver, image, Adjustments(), "test.fit"); fail("Expected write failure") }
            catch (_: IOException) { }
            assertTrue(provider.deleted); assertFalse(provider.published)
        }
    }
    @Test fun outputErrorsPropagate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        image(context.cacheDir).use { image ->
            var failed = false
            try { JpegExporter.write(image, Adjustments(), object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }) }
            catch (_: IOException) { failed = true }
            assertTrue(failed)
        }
    }
    class RecordingProvider(val file: File, val fail: Boolean = false) : ContentProvider() {
        val uri = Uri.parse("content://media/external/images/media/42")
        var inserted: ContentValues? = null
        var published = false
        var deleted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri { inserted = ContentValues(values); return this.uri }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            published = values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0; return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; file.delete(); return 1 }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (fail) throw java.io.FileNotFoundException("disk unavailable")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
