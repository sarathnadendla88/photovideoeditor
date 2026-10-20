package com.sarath.photovideo

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.aspectRatio
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TextPosition(val label: String, val y: Float) { TOP("Top", 0.7f), CENTER("Centre", 0f), BOTTOM("Bottom", -0.7f) }

data class VideoClip(
    val uri: Uri, val name: String, val durationMs: Long,
    val trimStartMs: Long = 0, val trimEndMs: Long = durationMs, val speed: Float = 1f, val ramp: SpeedRamp = SpeedRamp.NONE,
    val width: Int = 0, val height: Int = 0,
    /** A still frame held for [durationMs] (freeze frame). [uri] is a JPEG in app storage. */
    val freeze: Boolean = false,
) {
    val lengthMs get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
    /** Length in the exported video after the speed change. */
    val outputMs get() = rampedOutputMs(lengthMs, ramp, speed)
}

/** What the preview player depends on: it only reloads when one of these changes (not on speed, captions, etc.). */
private data class PreviewKey(val uri: Uri, val start: Long, val end: Long, val freeze: Boolean, val durationMs: Long)

data class Voiceover(val uri: Uri, val durationMs: Long, val fx: VoiceFx = VoiceFx.NORMAL)

data class VideoProject(
    val clips: List<VideoClip> = emptyList(),
    val canvas: CanvasPreset = CanvasPreset.PORTRAIT,
    val quality: ExportQuality = ExportQuality.HD,
    val muteOriginal: Boolean = false,
    val originalVolume: Float = 1f,
    val originalVoice: VoiceFx = VoiceFx.NORMAL,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val brightness: Float = 0f,   // -0.5..0.5
    val contrast: Float = 0f,     // -0.5..0.5
    val saturation: Float = 0f,   // -1..1
    val captions: List<Caption> = emptyList(),
    val captionStyle: CaptionStyle = CaptionStyle.BOXED,
    val captionAnim: CaptionAnim = CaptionAnim.POP,
    val captionSize: Float = 1f,
    val textPosition: TextPosition = TextPosition.BOTTOM,
    val stickers: List<VideoSticker> = emptyList(),
    val transition: Transition = Transition.NONE,
    val watermark: String = "",
    val music: MusicTrack? = null,
    val voiceover: Voiceover? = null,
) {
    val totalMs get() = clips.sumOf { it.outputMs }
    val canExport get() = clips.isNotEmpty() && clips.all { it.lengthMs >= MIN_CLIP_MS }
    fun trim(i: Int, start: Long, end: Long) = copy(clips = clips.mapIndexed { idx, c ->
        if (idx == i) c.copy(trimStartMs = start.coerceIn(0, c.durationMs), trimEndMs = end.coerceIn(0, c.durationMs)) else c })
    fun speed(i: Int, s: Float) = copy(clips = clips.mapIndexed { idx, c -> if (idx == i) c.copy(speed = s) else c })
    fun ramp(i: Int, r: SpeedRamp) = copy(clips = clips.mapIndexed { idx, c -> if (idx == i) c.copy(ramp = r) else c })
    fun moveOverlay(sel: OverlaySel, dx: Float, dy: Float): VideoProject = when (sel.kind) {
        0 -> copy(captions = captions.mapIndexed { i, c -> if (i == sel.index) c.copy(x = (c.x + dx).coerceIn(-1f, 1f), y = (c.y + dy).coerceIn(-1f, 1f)) else c })
        else -> copy(stickers = stickers.mapIndexed { i, c -> if (i == sel.index) c.copy(x = (c.x + dx).coerceIn(-1f, 1f), y = (c.y + dy).coerceIn(-1f, 1f)) else c })
    }
    /** Splits clip [i] at [atMs] and inserts a [holdMs] freeze of [frame] between the halves. Returns this if the split point is too close to an edge. */
    fun freezeAt(i: Int, atMs: Long, frame: VideoClip): VideoProject {
        val split = split(i, atMs)
        if (split === this) return this
        return split.copy(clips = split.clips.toMutableList().also { it.add(i + 1, frame) })
    }
    fun applyLook(l: LookPreset) = copy(filter = l.filter, brightness = l.brightness, contrast = l.contrast, saturation = l.saturation)
    fun applyPlatform(pl: Platform) = copy(canvas = pl.canvas, quality = pl.quality)
    /** Splits clip [i] at [atMs] (absolute position in the source) into two adjacent clips. */
    fun split(i: Int, atMs: Long): VideoProject {
        val c = clips.getOrNull(i) ?: return this
        if (atMs <= c.trimStartMs + MIN_CLIP_MS || atMs >= c.trimEndMs - MIN_CLIP_MS) return this
        val l = c.copy(trimEndMs = atMs); val r = c.copy(trimStartMs = atMs)
        return copy(clips = clips.toMutableList().also { it.removeAt(i); it.addAll(i, listOf(l, r)) })
    }
    fun move(from: Int, to: Int): VideoProject {
        if (from !in clips.indices || to !in clips.indices || from == to) return this
        return copy(clips = clips.toMutableList().also { it.add(to, it.removeAt(from)) })
    }
    fun remove(i: Int) = if (i in clips.indices) copy(clips = clips.toMutableList().also { it.removeAt(i) }) else this
    companion object { const val MIN_CLIP_MS = 300L }
}

private fun constantSpeed(s: Float) = object : SpeedProvider {
    override fun getSpeed(timeUs: Long) = s
    override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
}

/** Colour effects shared by preview and export. Filter presets use their linear part (offsets can't be expressed in an RGB matrix). */
fun videoColorEffects(p: VideoProject): List<Effect> = buildList {
    PhotoProcessor.filterMatrix(p.filter)?.array?.let { a ->
        val m = FloatArray(16)
        for (r in 0..3) for (c in 0..3) m[c * 4 + r] = a[r * 5 + c]
        add(RgbMatrix { _, _ -> m })
    }
    if (p.brightness != 0f) add(Brightness(p.brightness))
    if (p.contrast != 0f) add(Contrast(p.contrast))
    if (p.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(p.saturation * 100f).build())
}

fun buildVideoComposition(ctx: android.content.Context, p: VideoProject): Composition {
    val (cw, ch) = p.canvas.size(p.quality)
    var offsetMs = 0L
    val items = p.clips.mapIndexed { idx, c ->
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.trimStartMs).setEndPositionMs(c.trimEndMs).build()
        val provider = if (!c.freeze && (c.speed != 1f || c.ramp != SpeedRamp.NONE)) RampSpeedProvider(c.lengthMs * 1000, c.ramp, c.speed) else null
        val audioFx = buildList<androidx.media3.common.audio.AudioProcessor> {
            if (provider != null) add(SpeedChangingAudioProcessor(provider))
            if (p.originalVolume != 1f) add(SlideshowExporter.volumeProcessor(p.originalVolume))
            if (p.originalVoice != VoiceFx.NORMAL) add(androidx.media3.common.audio.SonicAudioProcessor().apply { setPitch(p.originalVoice.pitch) })
        }
        val videoFx = buildList<Effect> {
            if (provider != null) add(SpeedChangeEffect(provider))
            add(Presentation.createForWidthAndHeight(cw, ch, Presentation.LAYOUT_SCALE_TO_FIT))
            addAll(videoColorEffects(p))
            val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
            if (p.captions.isNotEmpty()) overlays += CaptionOverlay(p.captions, offsetMs, cw, ch, p.captionStyle, p.captionAnim, p.textPosition.y, p.captionSize)
            p.stickers.forEach { st ->
                val px = (cw * st.size).toInt().coerceAtLeast(32)
                val bmp = if (st.imageUri != null) PhotoProcessor.decodeToWidth(ctx, Uri.parse(st.imageUri), px) else emojiBitmap(st.emoji, px)
                if (bmp != null) overlays += TimedBitmapOverlay(bmp, offsetMs, st, cw, ch)
            }
            if (p.watermark.isNotBlank()) overlays += watermarkOverlay(p.watermark.trim(), cw)
            fadeFor(p.transition, idx, p.clips.size, cw, ch, c.outputMs)?.let { overlays += it }
            if (overlays.isNotEmpty()) add(OverlayEffect(overlays))
        }
        offsetMs += c.outputMs
        if (c.freeze) EditedMediaItem.Builder(MediaItem.Builder().setUri(c.uri).setImageDurationMs(c.durationMs).build())
            .setFrameRate(30).setDurationUs(c.durationMs * 1000)
            .setEffects(Effects(emptyList(), videoFx))
            .build()
        else EditedMediaItem.Builder(MediaItem.Builder().setUri(c.uri).setClippingConfiguration(clip).build())
            .setRemoveAudio(p.muteOriginal)
            .setEffects(Effects(audioFx, videoFx))
            .build()
    }
    val seqs = mutableListOf(EditedMediaItemSequence.Builder(items).build())
    p.music?.let { m -> seqs += musicSequence(m, p.totalMs) }
    p.voiceover?.let { v ->
        // Clip to the video length so a long recording can't extend the output.
        val end = minOf(v.durationMs, p.totalMs)
        val clip = MediaItem.ClippingConfiguration.Builder().setEndPositionMs(end).build()
        val fx = if (v.fx == VoiceFx.NORMAL) emptyList() else listOf(androidx.media3.common.audio.SonicAudioProcessor().apply { setPitch(v.fx.pitch) })
        val a = EditedMediaItem.Builder(MediaItem.Builder().setUri(v.uri).setClippingConfiguration(clip).build()).setRemoveVideo(true).setEffects(Effects(fx, emptyList())).build()
        seqs += EditedMediaItemSequence.Builder(a).build()
    }
    return Composition.Builder(seqs).build()
}

class VideoViewModel(app: Application) : AndroidViewModel(app) {
    private val _project = MutableStateFlow(VideoProject())
    val project = _project.asStateFlow()
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export = _export.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()
    private val exporter = SlideshowExporter(app)
    private val recorder = VoiceRecorder(app)

    fun addVideos(uris: List<Uri>, autoCanvas: Boolean = false) = viewModelScope.launch {
        val clips = withContext(Dispatchers.IO) { uris.mapNotNull { probe(it) } }
        if (clips.size < uris.size) _error.value = "Some videos couldn't be read and were skipped."
        _project.update { p ->
            // For imported videos, match the canvas to the first clip so it isn't letterboxed into a vertical frame.
            val canvas = if (autoCanvas) clips.firstOrNull()?.let { CanvasPreset.matching(it.width, it.height) } ?: p.canvas else p.canvas
            p.copy(clips = p.clips + clips, canvas = canvas)
        }
    }
    private fun probe(uri: Uri): VideoClip? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(getApplication(), uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: return null
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swap = rot == 90 || rot == 270
            VideoClip(uri, name(uri), d, width = if (swap) h else w, height = if (swap) w else h)
        } finally { r.release() }
    }.getOrNull()
    private fun name(uri: Uri) = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Video"

    fun update(f: (VideoProject) -> VideoProject) = _project.update(f)

    /** Grabs the frame at [atMs] of clip [i] and holds it for [holdMs] (default 2 s) in the timeline. */
    fun freezeFrame(i: Int, atMs: Long, holdMs: Long = 2000L) = viewModelScope.launch {
        val clip = _project.value.clips.getOrNull(i) ?: return@launch
        if (clip.freeze) return@launch
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(getApplication(), clip.uri)
                    val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("no frame")
                    val dir = File(getApplication<Application>().filesDir, "freeze").apply { mkdirs() }
                    File(dir, "freeze_${System.nanoTime()}.jpg").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) } }
                } finally { r.release() }
            }.getOrNull()
        }
        if (file == null) { _error.value = "Couldn't capture that frame."; return@launch }
        val frame = VideoClip(Uri.fromFile(file), "Freeze frame", holdMs, width = clip.width, height = clip.height, freeze = true)
        val before = _project.value
        val after = before.freezeAt(i, atMs, frame)
        if (after === before) { file.delete(); _error.value = "Pick a point a little away from the clip's start or end." } else _project.value = after
    }

    suspend fun addPicture(uri: Uri): Boolean {
        val copy = withContext(Dispatchers.IO) { OverlayFiles.import(getApplication(), uri) } ?: return false
        _project.update { it.copy(stickers = it.stickers + VideoSticker(imageUri = copy.toString(), size = 0.3f, y = 0f)) }
        return true
    }

    /** Replaces the project with freshly captured/picked clips (used by the camera hand-off). */
    fun startWith(uris: List<Uri>, canvas: CanvasPreset? = null) {
        _project.value = VideoProject(canvas = canvas ?: CanvasPreset.PORTRAIT)
        addVideos(uris, autoCanvas = canvas == null)
    }

    private val _speaking = MutableStateFlow(false)
    val speaking = _speaking.asStateFlow()

    /** Text-to-speech voiceover using the device's own TTS engine (offline if the voice is installed). */
    fun generateVoice(text: String) {
        if (text.isBlank() || _speaking.value) return
        viewModelScope.launch {
            _speaking.value = true
            val v = synthesize(text.trim())
            _speaking.value = false
            if (v == null) _error.value = "Text-to-speech isn't available on this device." else _project.update { it.copy(voiceover = v) }
        }
    }

    private suspend fun synthesize(text: String): Voiceover? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val file = File(getApplication<Application>().filesDir, "tts_${System.nanoTime()}.wav")
        var tts: android.speech.tts.TextToSpeech? = null
        fun finish(v: Voiceover?) { if (cont.isActive) cont.resume(v) {}; tts?.shutdown() }
        tts = android.speech.tts.TextToSpeech(getApplication()) { status ->
            val engine = tts
            if (status != android.speech.tts.TextToSpeech.SUCCESS || engine == null) { finish(null); return@TextToSpeech }
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { finish(null) }
                override fun onDone(id: String?) {
                    val d = runCatching { MediaMetadataRetriever().run { try { setDataSource(file.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } finally { release() } } }.getOrNull()
                    finish(if (d != null && d > 300) Voiceover(Uri.fromFile(file), d) else null)
                }
            })
            if (engine.synthesizeToFile(text, android.os.Bundle(), file, "tts") != android.speech.tts.TextToSpeech.SUCCESS) finish(null)
        }
        cont.invokeOnCancellation { tts?.shutdown() }
    }
    fun setMusic(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { MediaMetadataRetriever().run { try { setDataSource(getApplication(), uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { release() } } }.getOrDefault(0L) }
            _project.update { it.copy(music = MusicTrack(uri, name(uri), d)) }
        }
    }
    fun startRecording() { if (recorder.start()) _recording.value = true else _error.value = "Couldn't start the microphone." }
    fun stopRecording() {
        _recording.value = false
        recorder.stop()?.let { (uri, d) -> _project.update { it.copy(voiceover = Voiceover(uri, d)) } } ?: run { _error.value = "Recording was too short." }
    }
    fun export() { val p = _project.value; if (p.canExport) exporter.start(buildVideoComposition(getApplication(), p)) { _export.value = it } }
    fun cancelExport() { exporter.cancel(); _export.value = ExportState.Idle }
    fun dismissExport() { _export.value = ExportState.Idle }
    fun clearError() { _error.value = null }
    override fun onCleared() { exporter.cancel(); recorder.cancel() }
}

private val tabs = listOf(
    ToolTab("Clips", Icons.Default.ContentCut), ToolTab("Look", Icons.Default.Palette), ToolTab("Captions", Icons.Default.ClosedCaption),
    ToolTab("Audio", Icons.Default.GraphicEq), ToolTab("Output", Icons.Default.AspectRatio),
)
private val speeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)

@Composable
fun VideoEditorScreen(vm: VideoViewModel, onTakeVideo: () -> Unit = {}, onBack: () -> Unit) {
    val p by vm.project.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    var speech by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var overlaySel by remember { mutableStateOf<OverlaySel?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); vm.clearError() } }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { vm.addVideos(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else vm.update { it }.also { /* denied: nothing recorded */ }
    }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (vm.addPicture(uri)) overlaySel = OverlaySel(1, vm.project.value.stickers.lastIndex) }
    }
    fun pickVideos() = videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    // Preview player: plays trimmed clips in order with the colour look. Speed, text and canvas apply on export.
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val musicPlayer = remember { ExoPlayer.Builder(ctx).build() }
    val voicePlayer = remember { ExoPlayer.Builder(ctx).build() }
    val latest by rememberUpdatedState(p)

    // Keep the music / voiceover players in step with the video player, so the preview sounds like the export.
    fun globalMs(): Long = latest.clips.take(player.currentMediaItemIndex).sumOf { it.lengthMs } + player.currentPosition
    fun syncLayers() {
        val m = latest.music
        if (m != null && musicPlayer.playbackState != Player.STATE_IDLE) {
            var pos = m.startMs + globalMs()
            val dur = musicPlayer.duration
            if (dur != C.TIME_UNSET && dur > 0) pos = if (m.loop) m.startMs + (globalMs() % (dur - m.startMs).coerceAtLeast(1)) else pos.coerceAtMost(dur)
            musicPlayer.seekTo(pos)
        }
        latest.voiceover?.let { voicePlayer.seekTo(globalMs()) }
        val playing = player.isPlaying
        musicPlayer.playWhenReady = playing && m != null
        voicePlayer.playWhenReady = playing && latest.voiceover != null
    }
    LaunchedEffect(Unit) { while (true) { posMs = globalMs(); kotlinx.coroutines.delay(100) } }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = syncLayers()
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = syncLayers()
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release(); musicPlayer.release(); voicePlayer.release() }
    }
    // The original audio follows Mute / Original volume in the preview as well.
    LaunchedEffect(p.muteOriginal, p.originalVolume) { player.volume = if (p.muteOriginal) 0f else p.originalVolume }
    LaunchedEffect(p.music?.uri, p.music?.loop, p.music?.volume) {
        val m = p.music
        if (m == null) { musicPlayer.stop(); musicPlayer.clearMediaItems() } else {
            if (musicPlayer.currentMediaItem?.localConfiguration?.uri != m.uri) { musicPlayer.setMediaItem(MediaItem.fromUri(m.uri)); musicPlayer.prepare() }
            musicPlayer.repeatMode = if (m.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            musicPlayer.volume = m.volume
            syncLayers()
        }
    }
    LaunchedEffect(p.music?.startMs) { if (p.music != null) syncLayers() }
    LaunchedEffect(p.originalVoice) { player.playbackParameters = androidx.media3.common.PlaybackParameters(1f, p.originalVoice.pitch) }
    LaunchedEffect(p.voiceover?.fx) { voicePlayer.playbackParameters = androidx.media3.common.PlaybackParameters(1f, p.voiceover?.fx?.pitch ?: 1f) }
    LaunchedEffect(p.voiceover?.uri) {
        val v = p.voiceover
        if (v == null) { voicePlayer.stop(); voicePlayer.clearMediaItems() } else { voicePlayer.setMediaItem(MediaItem.fromUri(v.uri)); voicePlayer.prepare(); syncLayers() }
    }
    LaunchedEffect(p.clips.map { PreviewKey(it.uri, it.trimStartMs, it.trimEndMs, it.freeze, it.durationMs) }, p.filter, p.brightness, p.contrast, p.saturation) {
        delay(300)
        player.setVideoEffects(videoColorEffects(p))
        player.setMediaItems(p.clips.map { c ->
            if (c.freeze) MediaItem.Builder().setUri(c.uri).setImageDurationMs(c.durationMs).build()
            else MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.trimStartMs).setEndPositionMs(c.trimEndMs).build()).build()
        })
        player.prepare()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            StudioTopBar("Video", onBack) {
                Button({ vm.export() }, enabled = p.canExport && export !is ExportState.Running, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export")
                }
            }
        },
        bottomBar = { ToolTabBar(tabs, tab) { tab = it } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(Studio.CardShape).background(if (p.clips.isEmpty()) MaterialTheme.colorScheme.surfaceContainer else Color.Black), contentAlignment = Alignment.Center) {
                if (p.clips.isEmpty()) EmptyState(Icons.Default.VideoLibrary, "Add your videos", "Trim, split, change speed, add text, music and a voiceover, then export an MP4. Tip: in Gallery or Files, Share a video to ReelMint to open it here.", "Add videos", ::pickVideos, "Record a video", onTakeVideo)
                else {
                    // The frame has the export canvas shape, so captions/stickers sit exactly where they will in the file.
                    Box(Modifier.fillMaxHeight().aspectRatio(p.canvas.width.toFloat() / p.canvas.height).background(Color.Black)) {
                        AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxSize())
                        OverlayLayer(p, posMs, overlaySel, tab == 2, { overlaySel = it }) { s, dx, dy -> vm.update { it.moveOverlay(s, dx, dy) } }
                    }
                    Text("Preview: trim, order, look, audio & overlays", Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = Studio.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (p.clips.isEmpty()) "No clips yet" else "${p.clips.size} clips · ${fmtMs(p.totalMs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            OutlinedButton(onTakeVideo, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Record") }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(::pickVideos, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Add") }
                        }
                        if (p.clips.isNotEmpty()) {
                            val i = sel.coerceIn(p.clips.indices); val c = p.clips[i]
                            ChipRow(p.clips.indices.toList(), i, { "Clip ${it + 1}" }) { sel = it }
                            Text("${c.name} · keeping ${fmtMs(c.lengthMs)} of ${fmtMs(c.durationMs)}", style = MaterialTheme.typography.labelMedium)
                            RangeSlider(c.trimStartMs.toFloat()..c.trimEndMs.toFloat(), { r -> vm.update { it.trim(i, r.start.toLong(), r.endInclusive.toLong()) } }, valueRange = 0f..c.durationMs.toFloat())
                            Text("Speed", style = MaterialTheme.typography.labelMedium)
                            ChipRow(speeds, c.speed, { if (it == 1f) "1×" else "${it}×" }) { s -> vm.update { it.speed(i, s) } }
                            Text("Speed ramp (slow-mo / fast moments)", style = MaterialTheme.typography.labelMedium)
                            ChipRow(SpeedRamp.entries, c.ramp, { it.label }) { r -> vm.update { it.ramp(i, r) } }
                            var splitAt by remember(c) { mutableFloatStateOf((c.trimStartMs + c.trimEndMs) / 2f) }
                            LabeledSlider("Split point", splitAt, c.trimStartMs.toFloat()..c.trimEndMs.toFloat().coerceAtLeast(c.trimStartMs + 1f), fmtMs(splitAt.toLong())) { splitAt = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ vm.update { it.split(i, splitAt.toLong()) } }) { Icon(Icons.Default.ContentCut, null); Text(" Split") }
                                if (!c.freeze) OutlinedButton({ vm.freezeFrame(i, splitAt.toLong()) }) { Icon(Icons.Default.AcUnit, null); Text(" Freeze") }
                                IconButton({ vm.update { it.move(i, i - 1) }; sel = i - 1 }, enabled = i > 0) { Icon(Icons.Default.ArrowBack, "Move earlier") }
                                IconButton({ vm.update { it.move(i, i + 1) }; sel = i + 1 }, enabled = i < p.clips.lastIndex) { Icon(Icons.Default.ArrowForward, "Move later") }
                                IconButton({ vm.update { it.remove(i) } }) { Icon(Icons.Default.Delete, "Remove clip") }
                            }
                        }
                    }
                    1 -> {
                        Text("One-tap looks", style = MaterialTheme.typography.labelMedium)
                        ChipRow(LookPresets, LookPresets.firstOrNull { it.filter == p.filter && it.brightness == p.brightness && it.contrast == p.contrast && it.saturation == p.saturation }, { it.name }) { l -> vm.update { it.applyLook(l) } }
                        Text("Filter", style = MaterialTheme.typography.labelMedium)
                        ChipRow(PhotoFilter.entries, p.filter, { it.label }) { f -> vm.update { it.copy(filter = f) } }
                        LabeledSlider("Brightness", p.brightness, -0.5f..0.5f) { v -> vm.update { it.copy(brightness = v) } }
                        LabeledSlider("Contrast", p.contrast, -0.5f..0.5f) { v -> vm.update { it.copy(contrast = v) } }
                        LabeledSlider("Saturation", p.saturation, -1f..1f) { v -> vm.update { it.copy(saturation = v) } }
                    }
                    2 -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({
                                val start = posMs.coerceIn(0L, (p.totalMs - 500).coerceAtLeast(0))
                                vm.update { it.copy(captions = it.captions + Caption("Your text", start, minOf(start + 2500, it.totalMs.coerceAtLeast(start + 500)), 0f, it.textPosition.y)) }
                                overlaySel = OverlaySel(0, p.captions.size)
                            }, enabled = p.clips.isNotEmpty(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add text") }
                            OutlinedButton({ overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = p.clips.isNotEmpty()) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Picture") }
                        }
                        Text("Tap a layer to select it, then drag it on the preview. Text appears at the playback position you are on.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val total = p.totalMs.coerceAtLeast(1000).toFloat()
                        p.captions.forEachIndexed { i, c ->
                            val selected = overlaySel == OverlaySel(0, i)
                            ElevatedCard({ overlaySel = OverlaySel(0, i) }, Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(c.text, { t -> vm.update { it.copy(captions = it.captions.toMutableList().also { l -> l[i] = c.copy(text = t) }) } }, label = { Text("Text ${i + 1}") }, modifier = Modifier.weight(1f))
                                        IconButton({ vm.update { it.copy(captions = it.captions.toMutableList().also { l -> l.removeAt(i) }) }; overlaySel = null }) { Icon(Icons.Default.Delete, "Remove text") }
                                    }
                                    Text("Shown ${fmtMs(c.startMs)} – ${fmtMs(c.endMs.coerceAtMost(p.totalMs.coerceAtLeast(c.startMs + 300)))}", style = MaterialTheme.typography.labelMedium)
                                    RangeSlider(c.startMs.toFloat()..c.endMs.toFloat().coerceAtMost(total), { r -> vm.update { it.copy(captions = it.captions.toMutableList().also { l -> l[i] = c.copy(startMs = r.start.toLong(), endMs = r.endInclusive.toLong().coerceAtLeast(r.start.toLong() + 300)) }) } }, valueRange = 0f..total)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextPosition.entries.forEach { tp -> AssistChip({ vm.update { it.copy(captions = it.captions.toMutableList().also { l -> l[i] = c.copy(x = 0f, y = tp.y) }) } }, { Text(tp.label) }) }
                                    }
                                }
                            }
                        }
                        if (p.captions.isNotEmpty()) {
                            Text("Style", style = MaterialTheme.typography.labelMedium)
                            ChipRow(CaptionStyle.entries, p.captionStyle, { it.label }) { v -> vm.update { it.copy(captionStyle = v) } }
                            Text("Animation", style = MaterialTheme.typography.labelMedium)
                            ChipRow(CaptionAnim.entries, p.captionAnim, { it.label }) { v -> vm.update { it.copy(captionAnim = v) } }
                            LabeledSlider("Text size", p.captionSize, 0.6f..1.8f) { v -> vm.update { it.copy(captionSize = v) } }
                        }
                        HorizontalDivider()
                        Text("Stickers & pictures", style = MaterialTheme.typography.titleSmall)
                        ChipRow(listOf("😀", "😍", "🔥", "🎉", "❤️", "⭐", "👑", "🎵"), "", { it }) { e -> vm.update { it.copy(stickers = it.stickers + VideoSticker(e)) }; overlaySel = OverlaySel(1, p.stickers.size) }
                        p.stickers.forEachIndexed { i, st ->
                            val selected = overlaySel == OverlaySel(1, i)
                            ElevatedCard({ overlaySel = OverlaySel(1, i) }, Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (st.imageUri != null) coil.compose.AsyncImage(st.imageUri, "Picture ${i + 1}", Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                        else Text(st.emoji, style = MaterialTheme.typography.headlineSmall)
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (st.imageUri != null) "Picture ${i + 1}" else "Sticker ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        IconButton({ vm.update { it.copy(stickers = it.stickers.toMutableList().also { l -> l.removeAt(i) }) }; overlaySel = null }) { Icon(Icons.Default.Delete, "Remove") }
                                    }
                                    if (selected) {
                                        LabeledSlider("Size", st.size, 0.05f..1f) { v -> vm.update { it.copy(stickers = it.stickers.toMutableList().also { l -> l[i] = st.copy(size = v) }) } }
                                        LabeledSlider("Rotate", st.rotation, -180f..180f, "${st.rotation.toInt()}°") { v -> vm.update { it.copy(stickers = it.stickers.toMutableList().also { l -> l[i] = st.copy(rotation = v) }) } }
                                        val end = st.endMs.coerceAtMost(total.toLong())
                                        Text("Shown ${fmtMs(st.startMs)} – ${fmtMs(end)}", style = MaterialTheme.typography.labelMedium)
                                        RangeSlider(st.startMs.toFloat()..end.toFloat(), { r -> vm.update { it.copy(stickers = it.stickers.toMutableList().also { l -> l[i] = st.copy(startMs = r.start.toLong(), endMs = if (r.endInclusive >= total - 50) Long.MAX_VALUE else r.endInclusive.toLong().coerceAtLeast(r.start.toLong() + 300)) }) } }, valueRange = 0f..total)
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(p.muteOriginal, { m -> vm.update { it.copy(muteOriginal = m) } }); Spacer(Modifier.width(8.dp)); Text("Mute original audio") }
                        if (!p.muteOriginal) {
                            LabeledSlider("Original volume", p.originalVolume, 0f..1f, "${(p.originalVolume * 100).toInt()}%") { v -> vm.update { it.copy(originalVolume = v) } }
                            Text("Voice changer for the original sound", style = MaterialTheme.typography.labelMedium)
                            ChipRow(VoiceFx.entries, p.originalVoice, { it.label }) { f -> vm.update { it.copy(originalVoice = f) } }
                        }
                        HorizontalDivider()
                        FilledTonalButton({ audioPicker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(8.dp)); Text(if (p.music == null) "Add music" else "Change music") }
                        p.music?.let { m ->
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall)
                            LabeledSlider("Music volume", m.volume, 0f..1f, "${(m.volume * 100).toInt()}%") { v -> vm.update { s -> s.copy(music = s.music?.copy(volume = v)) } }
                            if (m.durationMs > 2000) LabeledSlider("Start at", m.startMs.toFloat(), 0f..(m.durationMs - 1000).toFloat(), fmtMs(m.startMs)) { v -> vm.update { s -> s.copy(music = s.music?.copy(startMs = v.toLong())) } }
                            LabeledSlider("Fade in", m.fadeInMs / 1000f, 0f..5f, "%.1fs".format(m.fadeInMs / 1000f)) { v -> vm.update { s -> s.copy(music = s.music?.copy(fadeInMs = (v * 1000).toLong())) } }
                            LabeledSlider("Fade out", m.fadeOutMs / 1000f, 0f..5f, "%.1fs".format(m.fadeOutMs / 1000f)) { v -> vm.update { s -> s.copy(music = s.music?.copy(fadeOutMs = (v * 1000).toLong())) } }
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(m.loop, { l -> vm.update { s -> s.copy(music = s.music?.copy(loop = l)) } }); Spacer(Modifier.width(8.dp)); Text(if (m.loop) "Loop music" else "Play music once") }
                            TextButton({ vm.update { it.copy(music = null) } }) { Text("Remove music") }
                        }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (recording) Button({ vm.stopRecording() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Stop, null); Text(" Stop recording") }
                            else FilledTonalButton({
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (p.voiceover == null) "Record voiceover" else "Re-record voiceover") }
                            p.voiceover?.let { v -> Text("${fmtMs(v.durationMs)} voiceover ready", style = MaterialTheme.typography.labelMedium); TextButton({ vm.update { it.copy(voiceover = null) } }) { Text("Remove") } }
                        }
                        p.voiceover?.let { v ->
                            Text("Voice effect", style = MaterialTheme.typography.labelMedium)
                            ChipRow(VoiceFx.entries, v.fx, { it.label }) { f -> vm.update { it.copy(voiceover = it.voiceover?.copy(fx = f)) } }
                        }
                        Text("The microphone is only used while recording. Audio stays on this device.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(speech, { speech = it }, label = { Text("Or type text to turn into a voiceover") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        FilledTonalButton({ vm.generateVoice(speech) }, enabled = speech.isNotBlank() && !speaking, shape = RoundedCornerShape(14.dp)) {
                            if (speaking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.RecordVoiceOver, null)
                            Spacer(Modifier.width(8.dp)); Text(if (speaking) "Generating…" else "Generate voice")
                        }
                    }
                    4 -> {
                        Text("Export for", style = MaterialTheme.typography.titleSmall)
                        ChipRow(Platform.entries, Platform.entries.firstOrNull { it.canvas == p.canvas && it.quality == p.quality }, { it.label }) { pl -> vm.update { it.applyPlatform(pl) } }
                        Text("Sets the canvas and quality that suit each platform. Apps may re-compress uploads.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Transition between clips", style = MaterialTheme.typography.titleSmall)
                        ChipRow(Transition.entries, p.transition, { it.label }) { t -> vm.update { it.copy(transition = t) } }
                        OutlinedTextField(p.watermark, { w -> vm.update { it.copy(watermark = w.take(24)) } }, label = { Text("Watermark / credit (bottom-right)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Canvas", style = MaterialTheme.typography.titleSmall)
                        ChipRow(CanvasPreset.entries, p.canvas, { it.label }) { c -> vm.update { it.copy(canvas = c) } }
                        Text("Quality", style = MaterialTheme.typography.titleSmall)
                        ChipRow(ExportQuality.entries, p.quality, { "${it.label} · ${p.canvas.size(it).let { s -> minOf(s.first, s.second) }}p" }) { q -> vm.update { it.copy(quality = q) } }
                    }
                }
            }
        }
    }
    ExportDialog(export, onCancel = vm::cancelExport, onDismiss = vm::dismissExport, onRetry = vm::export, onShare = { shareVideo(ctx, it) })
}
