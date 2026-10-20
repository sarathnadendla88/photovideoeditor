package com.sarath.photovideo

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val tabs = listOf(
    ToolTab("Adjust", Icons.Default.Tune), ToolTab("Filters", Icons.Default.AutoAwesome), ToolTab("Crop", Icons.Default.Crop),
    ToolTab("Text", Icons.Default.TextFields), ToolTab("Stickers", Icons.Default.EmojiEmotions), ToolTab("Draw", Icons.Default.Brush),
    ToolTab("Frame", Icons.Default.PhotoSizeSelectActual), ToolTab("Portrait", Icons.Default.Face),
)
private val emojis = listOf("😀", "😍", "🎉", "🎂", "❤️", "🔥", "⭐", "🌈", "🌸", "🐶", "🐱", "🎈", "👑", "🕶️", "🎵", "✈️")

@Composable
fun PhotoEditorScreen(initialUri: Uri? = null, onTakePhoto: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var maskState by remember { mutableStateOf("idle") } // idle | working | failed
    val stack = remember { UndoStack(PhotoEdit()) }
    var rev by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var selectedSticker by remember { mutableIntStateOf(-1) }
    var selText by remember { mutableIntStateOf(-1) }
    var selImage by remember { mutableIntStateOf(-1) }
    var layerKind by remember { mutableIntStateOf(0) }   // Stickers tab: 0 = emoji, 1 = picture
    var dragBase by remember { mutableStateOf<PhotoEdit?>(null) }
    val imgCache = remember { java.util.concurrent.ConcurrentHashMap<String, Bitmap>() }
    val imageLoader = remember { { u: String -> imgCache[u] ?: PhotoProcessor.decode(ctx, Uri.parse(u), 1024)?.also { imgCache[u] = it } } }
    var drawColor by remember { mutableStateOf(Palette.CORAL) }
    var drawWidth by remember { mutableFloatStateOf(0.012f) }
    val snack = remember { SnackbarHostState() }
    val looks = remember { LookStore(ctx) }
    var myLooks by remember { mutableStateOf(looks.all()) }
    var saveLookDialog by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf<String?>(null) }
    val edit = stack.current
    fun commit(e: PhotoEdit) { stack.push(e); rev++ }

    suspend fun open(uri: Uri) {
        val bmp = withContext(Dispatchers.IO) { PhotoProcessor.decode(ctx, uri, 1600) }
        if (bmp == null) snack.showSnackbar("This image couldn't be opened.")
        else { source = bmp; sourceUri = uri; mask = null; maskState = "idle"; rev++ }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scope.launch { open(uri) } }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val look = stack.current.toLook("batch").toEdit()
            var ok = 0
            uris.forEachIndexed { i, u ->
                batchProgress = "Applying look ${i + 1} of ${uris.size}…"
                val r = withContext(Dispatchers.IO) { runCatching { val b = PhotoProcessor.decode(ctx, u, 3072) ?: error("open"); PhotoProcessor.saveCopy(ctx, PhotoProcessor.apply(b, null, look), false) } }
                if (r.isSuccess) ok++
            }
            batchProgress = null
            snack.showSnackbar("Saved $ok of ${uris.size} photos to Pictures/PhotoVideoStudio")
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val local = withContext(Dispatchers.IO) { OverlayFiles.import(ctx, uri) } ?: uri
            commit(stack.current.copy(images = stack.current.images + ImageLayer(local.toString()))); selImage = stack.current.images.lastIndex; layerKind = 1
        }
    }
    LaunchedEffect(initialUri) { if (initialUri != null && source == null) open(initialUri) }

    LaunchedEffect(source, rev, comparing, tab == 2) {
        val s = source ?: return@LaunchedEffect
        val e = if (comparing) PhotoEdit() else if (tab == 2) stack.current.copy(cropRect = CropRect()) else stack.current
        preview = withContext(Dispatchers.Default) { PhotoProcessor.apply(s, mask, e, imageLoader) }
    }
    // Person mask is computed lazily the first time a portrait option is chosen.
    LaunchedEffect(edit.background, source) {
        val s = source ?: return@LaunchedEffect
        if (edit.background != Background.KEEP && mask == null && maskState != "failed") {
            maskState = "working"
            val m = withContext(Dispatchers.Default) { PhotoProcessor.segment(s) }
            mask = m; maskState = if (m == null) "failed" else "idle"; rev++
        }
    }

    val moveLayer: (Float, Float, Boolean) -> Unit = { dx, dy, final ->
        if (final) { dragBase?.let { stack.commitFrom(it) }; dragBase = null }
        else {
            if (dragBase == null) dragBase = stack.current
            val e = stack.current
            fun c(v: Float) = v.coerceIn(0f, 1f)
            val moved = when {
                tab == 3 && selText in e.texts.indices -> e.copy(texts = e.texts.toMutableList().also { l -> val t = l[selText]; l[selText] = t.copy(x = c(t.x + dx), y = c(t.y + dy)) })
                tab == 4 && layerKind == 1 && selImage in e.images.indices -> e.copy(images = e.images.toMutableList().also { l -> val t = l[selImage]; l[selImage] = t.copy(x = c(t.x + dx), y = c(t.y + dy)) })
                tab == 4 && layerKind == 0 && selectedSticker in e.stickers.indices -> e.copy(stickers = e.stickers.toMutableList().also { l -> val t = l[selectedSticker]; l[selectedSticker] = t.copy(x = c(t.x + dx), y = c(t.y + dy)) })
                else -> e
            }
            stack.live(moved); rev++
        }
    }
    if (saveLookDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saveLookDialog = false }, title = { Text("Save this look") },
            text = { OutlinedTextField(name, { name = it.take(20) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton({ if (name.isNotBlank()) { myLooks = looks.save(edit.toLook(name.trim())); saveLookDialog = false } }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ saveLookDialog = false }) { Text("Cancel") } })
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            StudioTopBar("Photo Editor", onBack) {
                if (source != null) IconButton(onTakePhoto) { Icon(Icons.Default.CameraAlt, "Take a new photo") }
                IconButton({ stack.undo(); rev++ }, enabled = stack.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton({ stack.redo(); rev++ }, enabled = stack.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                IconButton(onClick = {
                    val src = sourceUri ?: return@IconButton
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val full = PhotoProcessor.decode(ctx, src, 3072) ?: error("Could not open the original")
                                val fullMask = if (stack.current.background != Background.KEEP) PhotoProcessor.segment(full) else null
                                val out = PhotoProcessor.apply(full, fullMask, stack.current, imageLoader)
                                PhotoProcessor.saveCopy(ctx, out, transparent = stack.current.background == Background.REMOVE)
                            }
                        }
                        saving = false
                        val saved = result.getOrNull()
                        val r = snack.showSnackbar(if (saved != null) "Saved a copy to Pictures/PhotoVideoStudio" else "Save failed: ${result.exceptionOrNull()?.message}", actionLabel = if (saved != null) "Share" else null)
                        if (r == SnackbarResult.ActionPerformed && saved != null) {
                            val mime = ctx.contentResolver.getType(saved) ?: "image/jpeg"
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share photo"))
                        }
                    }
                }, enabled = source != null && !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Save, "Save copy")
                }
            }
        },
        bottomBar = { if (source != null) ToolTabBar(tabs, tab) { tab = it } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).background(MaterialTheme.colorScheme.surfaceContainer, Studio.CardShape), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) {
                    EmptyState(Icons.Default.AddPhotoAlternate, "Start with a photo", "Pick one from your gallery. Your original is never changed — edits are saved as a copy.", "Pick a photo", {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, "Take a photo", onTakePhoto)
                } else {
                    PreviewWithDrawing(p, drawing = tab == 5 && !comparing, color = drawColor, width = drawWidth, cropRect = if (tab == 2 && !comparing) edit.cropRect else null, onCrop = { r -> commit(edit.copy(cropRect = r, crop = CropRatio.FREE)) }, onMove = if ((tab == 3 || tab == 4) && !comparing) moveLayer else null) { stroke -> commit(edit.copy(strokes = edit.strokes + stroke)) }
                    FilledTonalIconToggleButton(comparing, { comparing = it }, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Default.Compare, "Hold to compare before and after") }
                    if (maskState == "working") LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp))
                }
            }
            if (source != null) {
                Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(horizontal = Studio.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (tab) {
                        0 -> {
                            FilledTonalButton({ source?.let { b -> commit(PhotoProcessor.autoEnhance(b, edit)) } }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("Auto enhance") }
                            LabeledSlider("Brightness", edit.brightness, -0.5f..0.5f) { commit(edit.copy(brightness = it)) }
                            LabeledSlider("Contrast", edit.contrast, 0.5f..1.5f) { commit(edit.copy(contrast = it)) }
                            LabeledSlider("Saturation", edit.saturation, 0f..2f) { commit(edit.copy(saturation = it)) }
                            LabeledSlider("Warmth", edit.warmth, -1f..1f) { commit(edit.copy(warmth = it)) }
                            LabeledSlider("Sharpen", edit.sharpen, 0f..1f) { commit(edit.copy(sharpen = it)) }
                            LabeledSlider("Fade", edit.fade, 0f..1f) { commit(edit.copy(fade = it)) }
                            LabeledSlider("Vignette", edit.vignette, 0f..1f) { commit(edit.copy(vignette = it)) }
                            TextButton({ commit(PhotoEdit()) }) { Text("Reset all edits") }
                        }
                        1 -> {
                            ChipRow(PhotoFilter.entries, edit.filter, { it.label }) { commit(edit.copy(filter = it)) }
                            Text("My looks", style = MaterialTheme.typography.labelMedium)
                            if (myLooks.isNotEmpty()) ChipRow(myLooks, null, { it.name }) { commit(edit.withLook(it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ saveLookDialog = true }) { Icon(Icons.Default.BookmarkAdd, null); Text(" Save look") }
                                OutlinedButton({ batchPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = batchProgress == null) { Icon(Icons.Default.PhotoLibrary, null); Text(" Apply to more") }
                            }
                            if (myLooks.isNotEmpty()) TextButton({ myLooks = looks.delete(myLooks.last().name) }) { Text("Delete last saved look") }
                            batchProgress?.let { Text(it, style = MaterialTheme.typography.labelMedium); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        2 -> {
                            Text("Drag the corners or edges on the photo, or pick a shape", style = MaterialTheme.typography.labelMedium)
                            ChipRow(CropRatio.entries, edit.crop, { it.label }) { r ->
                                val a = source?.let { b -> if (edit.rotation % 180 == 0) b.width.toFloat() / b.height else b.height.toFloat() / b.width } ?: 1f
                                commit(edit.copy(crop = r, cropRect = CropRect.forRatio(r.ratio, a)))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ commit(edit.copy(rotation = (edit.rotation + 90) % 360)) }) { Icon(Icons.Default.RotateRight, null); Text(" Rotate") }
                                OutlinedButton({ commit(edit.copy(flipH = !edit.flipH)) }) { Icon(Icons.Default.Flip, null); Text(" Flip") }
                            }
                        }
                        3 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton({ commit(edit.copy(texts = edit.texts + TextLayer())); selText = edit.texts.size }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add text") }
                                Spacer(Modifier.width(8.dp))
                                if (edit.texts.isNotEmpty()) ChipRow(edit.texts.indices.toList(), selText, { "Text ${it + 1}" }) { selText = it }
                            }
                            val i = selText.takeIf { it in edit.texts.indices }
                            if (i == null) Text(if (edit.texts.isEmpty()) "Add as many text layers as you like, each with its own font, colour and position." else "Pick a text layer to edit it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                val t = edit.texts[i]
                                fun upd(n: TextLayer) = commit(edit.copy(texts = edit.texts.toMutableList().also { it[i] = n }))
                                OutlinedTextField(t.text, { upd(t.copy(text = it)) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                                Text("Drag the text on the photo to move it", style = MaterialTheme.typography.labelMedium)
                                ChipRow(TextFont.entries, t.font, { it.label }) { upd(t.copy(font = it)) }
                                ChipRow(Palette.entries, t.color, { it.label }) { upd(t.copy(color = it)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(t.bold, { upd(t.copy(bold = !t.bold)) }, { Text("Bold") })
                                    FilterChip(t.italic, { upd(t.copy(italic = !t.italic)) }, { Text("Italic") })
                                    FilterChip(t.box, { upd(t.copy(box = !t.box)) }, { Text("Box") })
                                }
                                LabeledSlider("Size", t.size, 0.02f..0.25f) { upd(t.copy(size = it)) }
                                LabeledSlider("Rotate", t.rotation, -180f..180f, "${t.rotation.toInt()}°") { upd(t.copy(rotation = it)) }
                                TextButton({ commit(edit.copy(texts = edit.texts.toMutableList().also { it.removeAt(i) })); selText = -1 }) { Text("Remove text") }
                            }
                        }
                        4 -> {
                            ChipRow(emojis, "", { it }) { commit(edit.copy(stickers = edit.stickers + Sticker(it))); selectedSticker = edit.stickers.size; layerKind = 0 }
                            OutlinedButton({ imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add your own picture") }
                            if (edit.stickers.isNotEmpty()) ChipRow(edit.stickers.indices.toList(), if (layerKind == 0) selectedSticker else -1, { edit.stickers[it].emoji }) { selectedSticker = it; layerKind = 0 }
                            if (edit.images.isNotEmpty()) ChipRow(edit.images.indices.toList(), if (layerKind == 1) selImage else -1, { "Picture ${it + 1}" }) { selImage = it; layerKind = 1 }
                            val si = selectedSticker.takeIf { layerKind == 0 && it in edit.stickers.indices }
                            val ii = selImage.takeIf { layerKind == 1 && it in edit.images.indices }
                            if (si != null) {
                                val st = edit.stickers[si]
                                fun upd(n: Sticker) = commit(edit.copy(stickers = edit.stickers.toMutableList().also { it[si] = n }))
                                Text("Drag the sticker on the photo to move it", style = MaterialTheme.typography.labelMedium)
                                LabeledSlider("Size", st.size, 0.05f..0.7f) { upd(st.copy(size = it)) }
                                LabeledSlider("Rotate", st.rotation, -180f..180f, "${st.rotation.toInt()}°") { upd(st.copy(rotation = it)) }
                                TextButton({ commit(edit.copy(stickers = edit.stickers.toMutableList().also { it.removeAt(si) })); selectedSticker = -1 }) { Text("Remove sticker") }
                            }
                            if (ii != null) {
                                val im = edit.images[ii]
                                fun upd(n: ImageLayer) = commit(edit.copy(images = edit.images.toMutableList().also { it[ii] = n }))
                                Text("Drag the picture on the photo to move it", style = MaterialTheme.typography.labelMedium)
                                LabeledSlider("Size", im.size, 0.05f..1f) { upd(im.copy(size = it)) }
                                LabeledSlider("Rotate", im.rotation, -180f..180f, "${im.rotation.toInt()}°") { upd(im.copy(rotation = it)) }
                                LabeledSlider("Opacity", im.alpha, 0.1f..1f) { upd(im.copy(alpha = it)) }
                                TextButton({ commit(edit.copy(images = edit.images.toMutableList().also { it.removeAt(ii) })); selImage = -1 }) { Text("Remove picture") }
                            }
                        }
                        5 -> {
                            Text("Draw on the photo with your finger", style = MaterialTheme.typography.labelMedium)
                            ChipRow(Palette.entries, drawColor, { it.label }) { drawColor = it }
                            LabeledSlider("Brush size", drawWidth, 0.004f..0.04f, "%.3f".format(drawWidth)) { drawWidth = it }
                            TextButton({ commit(edit.copy(strokes = edit.strokes.dropLast(1))) }, enabled = edit.strokes.isNotEmpty()) { Text("Remove last stroke") }
                        }
                        6 -> {
                            LabeledSlider("Border width", edit.border, 0f..0.1f) { commit(edit.copy(border = it)) }
                            ChipRow(Palette.entries, edit.borderColor, { it.label }) { commit(edit.copy(borderColor = it)) }
                        }
                        7 -> {
                            Text("Detects the person on your device. No upload.", style = MaterialTheme.typography.labelMedium)
                            ChipRow(Background.entries, edit.background, { it.label }) { commit(edit.copy(background = it)) }
                            if (edit.background == Background.COLOR) ChipRow(Palette.entries, edit.backgroundColor, { it.label }) { commit(edit.copy(backgroundColor = it)) }
                            if (maskState == "failed") Text("Couldn't find a person in this photo, so the background is unchanged.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            if (edit.background == Background.REMOVE) Text("Remove saves a transparent PNG.", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Shows the bitmap letterboxed and, when [drawing], turns drags into normalised strokes. */
@Composable
private fun PreviewWithDrawing(bmp: Bitmap, drawing: Boolean, color: Palette, width: Float, cropRect: CropRect?, onCrop: (CropRect) -> Unit, onMove: ((Float, Float, Boolean) -> Unit)?, onStroke: (Stroke) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val live = remember { mutableStateListOf<Offset>() }
    val scale = if (box.width == 0) 1f else minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())
    val w = bmp.width * scale; val h = bmp.height * scale
    val left = (box.width - w) / 2f; val top = (box.height - h) / 2f
    Box(Modifier.fillMaxSize().padding(8.dp).onSizeChangedCompat { box = it }) {
        Image(bmp.asImageBitmap(), "Edited preview", Modifier.fillMaxSize())
        if (cropRect != null && w > 0) CropOverlay(cropRect, left, top, w, h, onCrop)
        if (onMove != null && w > 0) Box(Modifier.fillMaxSize().pointerInput(w, h, onMove) {
            detectDragGestures(onDragEnd = { onMove(0f, 0f, true) }, onDragCancel = { onMove(0f, 0f, true) }) { c, d -> c.consume(); onMove(d.x / w, d.y / h, false) }
        })
        if (drawing) Canvas(Modifier.fillMaxSize().pointerInput(bmp, color, width, box) {
            detectDragGestures(
                onDragStart = { live.clear(); live.add(it) },
                onDrag = { c, _ -> live.add(c.position) },
                onDragEnd = {
                    if (live.size > 1 && w > 0) onStroke(Stroke(live.map { ((it.x - left) / w).coerceIn(0f, 1f) to ((it.y - top) / h).coerceIn(0f, 1f) }, color, width))
                    live.clear()
                },
                onDragCancel = { live.clear() },
            )
        }) {
            if (live.size > 1) drawPath(Path().apply { moveTo(live[0].x, live[0].y); live.drop(1).forEach { lineTo(it.x, it.y) } }, color.compose,
                style = DrawStroke(width * h, cap = StrokeCap.Round))
        }
    }
}

private fun Modifier.onSizeChangedCompat(f: (IntSize) -> Unit) = this.onSizeChanged(f)


/** Draggable crop frame over the displayed image rect (left, top, w, h in px). */
@Composable
private fun CropOverlay(rect: CropRect, left: Float, top: Float, w: Float, h: Float, onCommit: (CropRect) -> Unit) {
    var live by remember(rect) { mutableStateOf(rect) }
    var mode by remember { mutableStateOf(0) } // bit mask: 1=left 2=top 4=right 8=bottom, 16=move
    val handle = 90f
    val min = 0.08f
    Canvas(Modifier.fillMaxSize().pointerInput(left, top, w, h) {
        detectDragGestures(
            onDragStart = { p ->
                val r = live
                val x1 = left + r.l * w; val y1 = top + r.t * h; val x2 = left + r.r * w; val y2 = top + r.b * h
                var m = 0
                if (kotlin.math.abs(p.x - x1) < handle) m = m or 1
                if (kotlin.math.abs(p.x - x2) < handle) m = m or 4
                if (kotlin.math.abs(p.y - y1) < handle) m = m or 2
                if (kotlin.math.abs(p.y - y2) < handle) m = m or 8
                if (m == 0 && p.x in x1..x2 && p.y in y1..y2) m = 16
                mode = m
            },
            onDrag = { c, d ->
                val dx = d.x / w; val dy = d.y / h; var r = live
                if (mode == 16) {
                    val nl = (r.l + dx).coerceIn(0f, 1f - (r.r - r.l)); val nt = (r.t + dy).coerceIn(0f, 1f - (r.b - r.t))
                    r = CropRect(nl, nt, nl + (r.r - r.l), nt + (r.b - r.t))
                } else {
                    if (mode and 1 != 0) r = r.copy(l = (r.l + dx).coerceIn(0f, r.r - min))
                    if (mode and 4 != 0) r = r.copy(r = (r.r + dx).coerceIn(r.l + min, 1f))
                    if (mode and 2 != 0) r = r.copy(t = (r.t + dy).coerceIn(0f, r.b - min))
                    if (mode and 8 != 0) r = r.copy(b = (r.b + dy).coerceIn(r.t + min, 1f))
                }
                live = r; c.consume()
            },
            onDragEnd = { if (mode != 0) onCommit(live) },
        )
    }) {
        val r = live
        val x1 = left + r.l * w; val y1 = top + r.t * h; val x2 = left + r.r * w; val y2 = top + r.b * h
        val dim = androidx.compose.ui.graphics.Color(0x99000000)
        drawRect(dim, Offset(left, top), androidx.compose.ui.geometry.Size(w, y1 - top))
        drawRect(dim, Offset(left, y2), androidx.compose.ui.geometry.Size(w, top + h - y2))
        drawRect(dim, Offset(left, y1), androidx.compose.ui.geometry.Size(x1 - left, y2 - y1))
        drawRect(dim, Offset(x2, y1), androidx.compose.ui.geometry.Size(left + w - x2, y2 - y1))
        drawRect(androidx.compose.ui.graphics.Color.White, Offset(x1, y1), androidx.compose.ui.geometry.Size(x2 - x1, y2 - y1), style = DrawStroke(3f))
        for (i in 1..2) { // rule-of-thirds guides
            drawLine(androidx.compose.ui.graphics.Color(0x88FFFFFF), Offset(x1 + (x2 - x1) * i / 3, y1), Offset(x1 + (x2 - x1) * i / 3, y2), 1.5f)
            drawLine(androidx.compose.ui.graphics.Color(0x88FFFFFF), Offset(x1, y1 + (y2 - y1) * i / 3), Offset(x2, y1 + (y2 - y1) * i / 3), 1.5f)
        }
        listOf(x1 to y1, x2 to y1, x1 to y2, x2 to y2).forEach { (cx, cy) -> drawCircle(androidx.compose.ui.graphics.Color.White, 14f, Offset(cx, cy)) }
    }
}
