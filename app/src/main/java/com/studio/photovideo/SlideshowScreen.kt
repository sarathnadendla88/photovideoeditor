package com.studio.photovideo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val tabs = listOf(
    ToolTab("Photos", Icons.Default.PhotoLibrary), ToolTab("Style", Icons.Default.AutoAwesome), ToolTab("Titles", Icons.Default.Title),
    ToolTab("Music", Icons.Default.MusicNote), ToolTab("Output", Icons.Default.AspectRatio),
)

@Composable
fun SlideshowScreen(vm: SlideshowViewModel, onBack: () -> Unit) {
    val project by vm.project.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var stickerSel by remember { mutableStateOf<OverlaySel?>(null) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (vm.addPicture(uri)) stickerSel = OverlaySel(1, vm.project.value.stickers.lastIndex) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(SlideshowProject.MAX_PHOTOS)) { vm.addPhotos(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }
    fun pickPhotos() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    Scaffold(
        topBar = {
            StudioTopBar("Slideshow", onBack) {
                Button({ vm.export() }, enabled = project.canExport && export !is ExportState.Running, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export")
                }
            }
        },
        bottomBar = { ToolTabBar(tabs, tab) { tab = it } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                if (project.photos.isEmpty()) {
                    EmptyState(Icons.Default.PhotoLibrary, "Turn photos into a video", "Choose photos, pick a style, add music and export an MP4. Everything stays on your device.", "Choose photos", ::pickPhotos)
                } else {
                    val ratio = project.canvas.width.toFloat() / project.canvas.height
                    Box(Modifier.fillMaxHeight().aspectRatio(ratio).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                        AsyncImage(project.photos[selected.coerceIn(project.photos.indices)], "Selected photo preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (project.title.isNotBlank() && selected == 0) PreviewText(project.title, Alignment.TopCenter)
                        val view = VideoProject(captions = listOfNotNull(project.slideCaptions[project.photos[selected.coerceIn(project.photos.indices)].toString()]?.takeIf { it.isNotBlank() }?.let { Caption(it, 0, Long.MAX_VALUE, 0f, -0.4f) }),
                            captionStyle = CaptionStyle.CLASSIC, stickers = project.stickers)
                        OverlayLayer(view, 0L, stickerSel, tab == 2, { stickerSel = it }) { s, dx, dy -> if (s.kind == 1) vm.updateSticker(s.index) { st -> st.copy(x = (st.x + dx).coerceIn(-1f, 1f), y = (st.y + dy).coerceIn(-1f, 1f)) } }
                        if (project.endText.isNotBlank() && selected == project.photos.lastIndex) PreviewText(project.endText, Alignment.BottomCenter)
                        Text("Approximate preview · motion applies on export", Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState()).padding(horizontal = Studio.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${project.photos.size} photos · ${project.totalDurationMs / 1000}s", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            FilledTonalButton(::pickPhotos, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Add") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(project.photos, key = { _, u -> u.toString() }) { i, uri -> PhotoThumb(uri, i, project.photos.size, i == selected, { selected = i }, { vm.movePhoto(i, i - 1); selected = i - 1 }, { vm.movePhoto(i, i + 1); selected = i + 1 }, { vm.removePhoto(i) }) }
                        }
                        LabeledSlider("Seconds per photo", project.secondsPerPhoto, 1f..8f, "%.1fs".format(project.secondsPerPhoto), vm::setSeconds)
                        if (project.photos.isNotEmpty()) {
                            val cur = project.photos[selected.coerceIn(project.photos.indices)]
                            OutlinedTextField(project.slideCaptions[cur.toString()].orEmpty(), { vm.setSlideCaption(cur, it) }, label = { Text("Caption for photo ${selected.coerceIn(project.photos.indices) + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    1 -> {
                        Text("Templates", style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(SlideshowTemplates) { _, t -> TemplateCard(t) { vm.applyTemplate(t) } }
                        }
                        Text("Motion", style = MaterialTheme.typography.titleSmall)
                        ChipRow(Motion.entries, project.motion, { it.label }, vm::setMotion)
                        Text("Transition between photos", style = MaterialTheme.typography.titleSmall)
                        ChipRow(Transition.entries, project.transition, { it.label }, vm::setTransition)
                    }
                    2 -> {
                        OutlinedTextField(project.title, vm::setTitle, label = { Text("Opening title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(project.endText, vm::setEndText, label = { Text("Ending text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(project.watermark, vm::setWatermark, label = { Text("Watermark / credit (bottom-right)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        HorizontalDivider()
                        Text("Stickers & pictures (on every photo)", style = MaterialTheme.typography.titleSmall)
                        ChipRow(listOf("😀", "😍", "🔥", "🎉", "❤️", "⭐", "👑", "🎵"), "", { it }) { e -> vm.addSticker(VideoSticker(e)); stickerSel = OverlaySel(1, project.stickers.size) }
                        OutlinedButton({ stickerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add your own picture") }
                        project.stickers.forEachIndexed { i, st ->
                            val on = stickerSel == OverlaySel(1, i)
                            ElevatedCard({ stickerSel = OverlaySel(1, i) }, Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (st.imageUri != null) AsyncImage(st.imageUri, "Picture ${i + 1}", Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) else Text(st.emoji, style = MaterialTheme.typography.headlineSmall)
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (st.imageUri != null) "Picture ${i + 1}" else "Sticker ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        IconButton({ vm.removeSticker(i); stickerSel = null }) { Icon(Icons.Default.Delete, "Remove") }
                                    }
                                    if (on) {
                                        Text("Drag it on the preview to move it", style = MaterialTheme.typography.labelMedium)
                                        LabeledSlider("Size", st.size, 0.05f..1f) { v -> vm.updateSticker(i) { it.copy(size = v) } }
                                        LabeledSlider("Rotate", st.rotation, -180f..180f, "${st.rotation.toInt()}°") { v -> vm.updateSticker(i) { it.copy(rotation = v) } }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        val m = project.music
                        FilledTonalButton({ audioPicker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(8.dp)); Text(if (m == null) "Choose a track" else "Change track") }
                        if (m == null) Text("Add your own music from your device. Only music you have the rights to use.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall)
                            LabeledSlider("Volume", m.volume, 0f..1f, "${(m.volume * 100).toInt()}%", vm::setMusicVolume)
                            if (m.durationMs > 2000) LabeledSlider("Start at", m.startMs.toFloat(), 0f..(m.durationMs - 1000).toFloat(), fmtMs(m.startMs)) { vm.setMusicStart(it.toLong()) }
                            LabeledSlider("Fade in", m.fadeInMs / 1000f, 0f..5f, "%.1fs".format(m.fadeInMs / 1000f)) { vm.setFadeIn((it * 1000).toLong()) }
                            LabeledSlider("Fade out", m.fadeOutMs / 1000f, 0f..5f, "%.1fs".format(m.fadeOutMs / 1000f)) { vm.setFadeOut((it * 1000).toLong()) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(m.loop, vm::setMusicLoop); Spacer(Modifier.width(8.dp))
                                Text(if (m.loop) "Loop music to cover the slideshow" else "Stop music when it ends")
                            }
                            TextButton(vm::clearMusic) { Text("Remove music") }
                        }
                    }
                    4 -> {
                        Text("Canvas", style = MaterialTheme.typography.titleSmall)
                        ChipRow(CanvasPreset.entries, project.canvas, { it.label }, vm::setCanvas)
                        Text("Quality", style = MaterialTheme.typography.titleSmall)
                        ChipRow(ExportQuality.entries, project.quality, { "${it.label} · ${project.canvas.size(it).let { s -> minOf(s.first, s.second) }}p" }, vm::setQuality)
                    }
                }
            }
        }
    }
    ExportDialog(export, vm::cancelExport, vm::dismissExport, vm::export, onShare = { shareVideo(ctx, it) })
}

@Composable
private fun BoxScope.PreviewText(text: String, align: Alignment) {
    Text(text, Modifier.align(align).padding(24.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f)))
}

@Composable
private fun PhotoThumb(uri: Uri, i: Int, count: Int, selected: Boolean, onSelect: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onSelect)) {
        AsyncImage(uri, "Photo ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))))
        if (selected) Box(Modifier.fillMaxSize().background(Color.Transparent).then(Modifier.clip(RoundedCornerShape(14.dp))).background(Color(0x337C5CFF)))
        Text("${i + 1}", Modifier.align(Alignment.TopStart).padding(6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.align(Alignment.BottomCenter)) {
            MiniButton(Icons.Default.ChevronLeft, "Move earlier", i > 0, onLeft)
            MiniButton(Icons.Default.Close, "Remove photo", true, onRemove)
            MiniButton(Icons.Default.ChevronRight, "Move later", i < count - 1, onRight)
        }
    }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(30.dp)) { Icon(icon, desc, Modifier.size(18.dp), tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f)) }
}

@Composable
private fun TemplateCard(t: SlideshowTemplate, onClick: () -> Unit) {
    GradientCard(Studio.tile(t.from, t.to), Modifier.width(150.dp), onClick) {
        Icon(t.icon, null, tint = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(t.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(t.tagline, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
    }
}

fun fmtMs(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
