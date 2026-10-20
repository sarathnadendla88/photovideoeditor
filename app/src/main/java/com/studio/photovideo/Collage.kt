package com.studio.photovideo

import android.graphics.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cells are normalised rectangles (l, t, r, b). */
enum class CollageLayout(val label: String, val cells: List<FloatArray>) {
    SIDE("2 across", listOf(floatArrayOf(0f, 0f, .5f, 1f), floatArrayOf(.5f, 0f, 1f, 1f))),
    STACK("2 stacked", listOf(floatArrayOf(0f, 0f, 1f, .5f), floatArrayOf(0f, .5f, 1f, 1f))),
    COLS3("3 columns", List(3) { floatArrayOf(it / 3f, 0f, (it + 1) / 3f, 1f) }),
    ROWS3("3 rows", List(3) { floatArrayOf(0f, it / 3f, 1f, (it + 1) / 3f) }),
    BIG_LEFT("1 + 2", listOf(floatArrayOf(0f, 0f, .6f, 1f), floatArrayOf(.6f, 0f, 1f, .5f), floatArrayOf(.6f, .5f, 1f, 1f))),
    BIG_TOP("1 + 3", listOf(floatArrayOf(0f, 0f, 1f, .6f), floatArrayOf(0f, .6f, 1f / 3, 1f), floatArrayOf(1f / 3, .6f, 2f / 3, 1f), floatArrayOf(2f / 3, .6f, 1f, 1f))),
    GRID4("2 × 2", listOf(floatArrayOf(0f, 0f, .5f, .5f), floatArrayOf(.5f, 0f, 1f, .5f), floatArrayOf(0f, .5f, .5f, 1f), floatArrayOf(.5f, .5f, 1f, 1f))),
    GRID6("3 × 2", List(6) { floatArrayOf((it % 3) / 3f, (it / 3) / 2f, (it % 3 + 1) / 3f, (it / 3 + 1) / 2f) }),
}
enum class CollageShape(val label: String, val w: Int, val h: Int) { SQUARE("1:1", 1, 1), P45("4:5", 4, 5), STORY("9:16", 9, 16), WIDE("16:9", 16, 9) }

object CollageRenderer {
    fun render(photos: List<Bitmap?>, layout: CollageLayout, shape: CollageShape, spacing: Float, radius: Float, bg: Palette, width: Int): Bitmap {
        val height = width * shape.h / shape.w
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(bg.argb)
        val gap = spacing * width / 2f
        val r = radius * width
        layout.cells.forEachIndexed { i, n ->
            val rect = RectF(n[0] * width + gap, n[1] * height + gap, n[2] * width - gap, n[3] * height - gap)
            val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            val b = photos.getOrNull(i)
            if (b == null) c.drawRect(rect, Paint().apply { color = 0x33808080 }) else {
                val s = maxOf(rect.width() / b.width, rect.height() / b.height)
                val dw = b.width * s; val dh = b.height * s
                c.drawBitmap(b, null, RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            c.restore()
        }
        return out
    }
}

@Composable
fun CollageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var layout by remember { mutableStateOf(CollageLayout.GRID4) }
    var shape by remember { mutableStateOf(CollageShape.SQUARE) }
    var spacing by remember { mutableFloatStateOf(0.02f) }
    var radius by remember { mutableFloatStateOf(0.02f) }
    var bg by remember { mutableStateOf(Palette.WHITE) }
    var replacing by remember { mutableIntStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val snack = remember { SnackbarHostState() }

    suspend fun load(uris: List<Uri>) = withContext(Dispatchers.IO) { uris.map { PhotoProcessor.decode(ctx, it, 1080) } }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) scope.launch { photos = (photos + load(uris)).filterNotNull().take(9) }
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val i = replacing
        if (uri != null && i >= 0) scope.launch {
            val b = load(listOf(uri)).first() ?: return@launch
            photos = photos.toMutableList().also { if (i < it.size) it[i] = b else it.add(b) }
        }
        replacing = -1
    }
    val preview = remember(photos, layout, shape, spacing, radius, bg) { CollageRenderer.render(photos, layout, shape, spacing, radius, bg, 720) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            StudioTopBar("Collage", onBack) {
                IconButton(onClick = {
                    saving = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { PhotoProcessor.saveCopy(ctx, CollageRenderer.render(photos, layout, shape, spacing, radius, bg, 1440), false) } }
                        saving = false
                        snack.showSnackbar(if (r.isSuccess) "Saved to Pictures/PhotoVideoStudio" else "Save failed: ${r.exceptionOrNull()?.message}")
                    }
                }, enabled = photos.isNotEmpty() && !saving) { Icon(Icons.Default.Save, "Save collage") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).background(MaterialTheme.colorScheme.surfaceContainer, Studio.CardShape), contentAlignment = Alignment.Center) {
                if (photos.isEmpty()) EmptyState(Icons.Default.GridView, "Make a collage", "Pick up to 9 photos, then choose a layout. Tap any cell later to swap its photo.", "Pick photos") {
                    multi.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    val scale = if (box.width == 0) 1f else minOf(box.width / preview.width.toFloat(), box.height / preview.height.toFloat())
                    val w = preview.width * scale; val h = preview.height * scale
                    Box(Modifier.fillMaxSize().padding(8.dp).onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
                        Image(preview.asImageBitmap(), "Collage preview", Modifier.fillMaxSize().pointerInput(layout, box) {
                            detectTapGestures { p ->
                                val nx = (p.x - (box.width - w) / 2f) / w; val ny = (p.y - (box.height - h) / 2f) / h
                                val i = layout.cells.indexOfFirst { nx >= it[0] && nx <= it[2] && ny >= it[1] && ny <= it[3] }
                                if (i >= 0) { replacing = i; single.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            }
                        })
                    }
                }
            }
            if (photos.isNotEmpty()) Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(Studio.Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Layout", style = MaterialTheme.typography.titleSmall)
                ChipRow(CollageLayout.entries, layout, { it.label }) { layout = it }
                Text("Shape", style = MaterialTheme.typography.titleSmall)
                ChipRow(CollageShape.entries, shape, { it.label }) { shape = it }
                LabeledSlider("Spacing", spacing, 0f..0.08f) { spacing = it }
                LabeledSlider("Rounded corners", radius, 0f..0.08f) { radius = it }
                Text("Background", style = MaterialTheme.typography.titleSmall)
                ChipRow(Palette.entries, bg, { it.label }) { bg = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ multi.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = photos.size < 9) { Text("Add photos") }
                    OutlinedButton({ photos = photos.shuffled() }) { Text("Shuffle") }
                    OutlinedButton({ photos = emptyList() }) { Text("Clear") }
                }
                if (photos.size < layout.cells.size) Text("This layout has ${layout.cells.size} cells; ${layout.cells.size - photos.size} are empty. Tap an empty cell to fill it.", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
