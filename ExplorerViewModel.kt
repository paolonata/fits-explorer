package it.fits.explorer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.fits.explorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 data class ExplorerState(
    val bitmap: Bitmap? = null, val adjustments: Adjustments = Adjustments(),
    val fileName: String = "", val busy: Boolean = false, val rendering: Boolean = false,
    val message: String = "Nessun file aperto.", val saved: Uri? = null, val generation: Int = 0
)

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ExplorerState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var prepared: PreparedImage? = null
    private var renderJob: Job? = null
    private val resolver get() = getApplication<Application>().contentResolver
    private val cacheDir get() = java.io.File(getApplication<Application>().cacheDir, "fits-linear").apply { mkdirs() }

    init { cacheDir.listFiles()?.forEach { it.delete() } }
    fun open(uri: Uri) {
        if (mutable.value.busy) return
        renderJob?.cancel()
        mutable.update { it.copy(busy = true, rendering = false, message = "Lettura ed elaborazione…", saved = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val ctx = currentCoroutineContext()
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: "immagine.fits"
                        // Release the previous session before allocating another sensor-sized buffer.
                        prepared?.close(); prepared = null
                        withContext(Dispatchers.Main) { mutable.update { it.copy(bitmap = null) } }
                        val image = resolver.openInputStream(uri)?.use { FitsReader.read(it) { ctx.ensureActive() } }
                            ?: throw FitsException("Impossibile aprire il file")
                        val session = PreparedImage.prepare(image, cacheDir) { ctx.ensureActive() }
                        try {
                            val pixels = session.renderPreview(Adjustments()) { ctx.ensureActive() }
                            val bitmap = Bitmap.createBitmap(pixels, session.preview.width, session.preview.height, Bitmap.Config.ARGB_8888)
                            prepared = session
                            name to bitmap
                        } catch (e: Throwable) { session.close(); throw e }
                    }
                }
                mutable.update { it.copy(bitmap = result.second, fileName = result.first, adjustments = Adjustments(), generation = it.generation + 1, message = "Pronto.") }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(bitmap = null, message = "Memoria insufficiente per questo FITS. Chiudi altre app e riprova.") } }
            catch (e: Exception) { mutable.update { it.copy(bitmap = null, message = "Apertura non riuscita: ${e.message ?: "file non supportato"}") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun adjust(a: Adjustments) {
        if (mutable.value.busy || mutable.value.bitmap == null) return
        mutable.update { it.copy(adjustments = a, rendering = true, saved = null) }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            try {
                delay(16)
                val bitmap = withContext(Dispatchers.Default) {
                    mutex.withLock {
                        val session = prepared ?: return@withLock null
                        val ctx = currentCoroutineContext()
                        val pixels = session.renderPreview(a) { ctx.ensureActive() }
                        Bitmap.createBitmap(pixels, session.preview.width, session.preview.height, Bitmap.Config.ARGB_8888)
                    }
                }
                mutable.update { it.copy(bitmap = bitmap, rendering = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(rendering = false, message = "Memoria insufficiente per l’anteprima.") } }
            catch (e: Exception) { mutable.update { it.copy(rendering = false, message = "Anteprima non riuscita: ${e.message}") } }
        }
    }
    fun auto() = adjust(Adjustments())
    fun save() {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.rendering || snapshot.bitmap == null) return
        mutable.update { it.copy(busy = true, saved = null, message = "Salvataggio JPG…") }
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val image = prepared ?: throw FitsException("Apri prima un FITS")
                        val ctx = currentCoroutineContext()
                        JpegExporter.save(resolver, image, snapshot.adjustments, snapshot.fileName) { ctx.ensureActive() }
                    }
                }
                mutable.update { it.copy(saved = uri, message = "JPG salvato in Galleria · FITS Explorer") }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(message = "Memoria insufficiente per esportare il JPG a piena risoluzione.") } }
            catch (e: Exception) { mutable.update { it.copy(message = "Salvataggio non riuscito: ${e.message}") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() {
        super.onCleared()
        // viewModelScope is cancelled by Lifecycle. Wait for any row worker to stop
        // before deleting its cache. No Activity is retained by this cleanup job.
        CoroutineScope(Dispatchers.IO).launch { mutex.withLock { prepared?.close(); prepared = null } }
    }
}
