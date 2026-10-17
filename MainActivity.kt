package it.fits.explorer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFA7C0FF), onPrimary = Color(0xFF07101E),
                background = Color(0xFF080C13), surface = Color(0xFF111924),
                onSurface = Color(0xFFF1F5FF), onBackground = Color(0xFFF1F5FF)
            )) {
                val vm: ExplorerViewModel = viewModel()
                LaunchedEffect(Unit) { if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW) intent.data?.let(vm::open) }
                ExplorerScreen(vm)
            }
        }
    }
}

@Composable
private fun ExplorerScreen(vm: ExplorerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    var zoom by remember(state.generation) { mutableFloatStateOf(1f) }
    var pan by remember(state.generation) { mutableStateOf(Offset.Zero) }
    val enabled = state.bitmap != null && !state.busy
    val height = (LocalConfiguration.current.screenHeightDp * .46f).coerceIn(260f, 600f).dp
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("✦  FITS Explorer", style = MaterialTheme.typography.titleLarge); Text("ASTRO IMAGE VIEWER", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA9C0)) }
            Text("OFFLINE", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA7C0FF))
        }
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp)).background(Color(0xFF030609)), contentAlignment = Alignment.Center) {
            val bitmap = state.bitmap
            if (bitmap == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("✧", style = MaterialTheme.typography.displayMedium, color = Color(0xFFA7C0FF))
                    Text("Il cielo prende forma.", style = MaterialTheme.typography.titleLarge)
                    Text("Apri un FITS da ASIAIR per visualizzarlo.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA9C0))
                }
            } else {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Immagine FITS. Usa due dita per zoom e spostamento." }.pointerInput(state.generation) {
                    detectTransformGestures { centroid, delta, scale, _ ->
                        val next = (zoom * scale).coerceIn(.5f, 30f)
                        val ratio = next / zoom
                        pan = (pan - (centroid - Offset(size.width / 2f, size.height / 2f))) * ratio + (centroid - Offset(size.width / 2f, size.height / 2f)) + delta
                        zoom = next
                        pan = Offset(pan.x.coerceIn(-size.width * zoom, size.width * zoom), pan.y.coerceIn(-size.height * zoom, size.height * zoom))
                    }
                }) {
                    val base = minOf(size.width / image.width, size.height / image.height)
                    val w = (image.width * base * zoom).roundToInt().coerceAtLeast(1)
                    val h = (image.height * base * zoom).roundToInt().coerceAtLeast(1)
                    drawImage(image, dstOffset = IntOffset(((size.width - w) / 2 + pan.x).roundToInt(), ((size.height - h) / 2 + pan.y).roundToInt()), dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
                }
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(36.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Apri FITS") }
            Button(onClick = vm::save, enabled = enabled && !state.rendering, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Salva JPG") }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF111924)) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Regolazioni", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = vm::auto, enabled = enabled) { Text("↻  Auto") }
                }
                val a = state.adjustments
                AdjustmentSlider("Nero", a.black, 0f..0.4f, "${fmt(a.black * 100, 1)}%", enabled) { vm.adjust(a.copy(black = it)) }
                AdjustmentSlider("Bianco", a.white, 0.6f..1f, "${fmt(a.white * 100, 1)}%", enabled) { vm.adjust(a.copy(white = it)) }
                AdjustmentSlider("Gamma", a.gamma, 0.2f..3f, fmt(a.gamma, 2), enabled) { vm.adjust(a.copy(gamma = it)) }
                AdjustmentSlider("Stretch", a.stretch, 0f..12f, fmt(a.stretch, 2), enabled) { vm.adjust(a.copy(stretch = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(.5f) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("−") }
                    OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = enabled, modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(8.dp)) { Text("Adatta") }
                    OutlinedButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(30f) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("+") }
                }
                Spacer(Modifier.height(10.dp))
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA9C0))
                state.saved?.let { uri ->
                    TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "Apri il JPG dall’app Galleria", android.widget.Toast.LENGTH_LONG).show() }
                    }) { Text("Apri immagine") }
                }
            }
        }
    }
}
private fun fmt(value: Float, digits: Int) = String.format(Locale.ITALIAN, "%.${digits}f", value)
@Composable
private fun AdjustmentSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, enabled: Boolean, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(formatted, color = Color(0xFFA7C0FF), style = MaterialTheme.typography.bodyMedium) }
    Slider(value = value, onValueChange = change, valueRange = range, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
}
