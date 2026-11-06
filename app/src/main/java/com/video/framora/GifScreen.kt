package com.video.framora

import android.app.Application
import android.net.Uri
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GifState {
    data object Empty : GifState
    data class Ready(val video: Uri, val durationMs: Long, val settings: GifSettings) : GifState
    data class Working(val progress: Float) : GifState
    data class Done(val result: GifMaker.Result) : GifState
    data class Failed(val message: String) : GifState
}

class GifViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<GifState>(GifState.Empty)
    val state: StateFlow<GifState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var last: GifState.Ready? = null

    fun load(uri: Uri) {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { GifMaker.durationMs(getApplication(), uri) }
            if (d <= 0) { _state.value = GifState.Failed("This video couldn't be opened"); return@launch }
            val (s, e) = GifMaker.clampRange(0, d, d, Entitlements.gifMaxMs(Pro.isPro))
            GifState.Ready(uri, d, GifSettings(s, e)).also { last = it; _state.value = it }
        }
    }

    fun update(f: (GifSettings) -> GifSettings) {
        val r = _state.value as? GifState.Ready ?: return
        val raw = f(r.settings)
        val (s, e) = GifMaker.clampRange(raw.startMs, raw.endMs, r.durationMs, Entitlements.gifMaxMs(Pro.isPro))
        GifState.Ready(r.video, r.durationMs, raw.copy(startMs = s, endMs = e)).also { last = it; _state.value = it }
    }

    fun make() {
        val ready = _state.value as? GifState.Ready ?: return
        val pro = Pro.isPro
        // Free limits are enforced here too, so a stale setting (say after a test reset) can never produce a Pro-only GIF.
        val (cs, ce) = GifMaker.clampRange(ready.settings.startMs, ready.settings.endMs, ready.durationMs, Entitlements.gifMaxMs(pro))
        val r = ready.copy(settings = ready.settings.copy(startMs = cs, endMs = ce,
            size = if (Entitlements.gifSize(ready.settings.size, pro)) ready.settings.size else GifSize.MEDIUM,
            fps = if (Entitlements.gifFps(ready.settings.fps, pro)) ready.settings.fps else 15, brandTag = Entitlements.brandTag(pro),
            logoUri = ready.settings.logoUri.takeIf { pro },
            style = if (Entitlements.gifStyle(ready.settings.style, pro)) ready.settings.style else GifStyle.NORMAL,
            speed = if (Entitlements.gifSpeed(ready.settings.speed, pro)) ready.settings.speed else 1f))
        cancelled = false; _state.value = GifState.Working(0f)
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { GifMaker.make(getApplication(), r.video, r.settings, { p -> _state.update { if (it is GifState.Working) GifState.Working(p) else it } }, { cancelled }) }
                _state.value = GifState.Done(res)
            } catch (e: java.util.concurrent.CancellationException) { _state.value = r }
            catch (e: Throwable) { _state.value = GifState.Failed(e.message ?: "Couldn't make the GIF") }
        }
    }

    /** Picks the creator's logo for the GIF (copied to app storage so it survives the picker link). */
    fun setLogo(uri: Uri?) { viewModelScope.launch { val copy = if (uri == null) null else withContext(Dispatchers.IO) { OverlayFiles.import(getApplication(), uri) }; update { it.copy(logoUri = copy?.toString()) } } }

    fun cancel() { cancelled = true }
    fun back() { _state.value = last ?: GifState.Empty }
    fun reset() { last = null; _state.value = GifState.Empty }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifScreen(initial: Uri?, onConsumed: () -> Unit, onTakeVideo: () -> Unit, vm: GifViewModel = viewModel(), onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::load) }
    LaunchedEffect(initial) { initial?.let { vm.load(it); onConsumed() } }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()); if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { StudioTopBar("GIF maker", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(Studio.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = state) {
                GifState.Empty -> EmptyState(Icons.Default.Gif, "Turn a video into a GIF", "Pick a short moment, add a caption, loop it forwards or as a boomerang. It's made on your device.",
                    "Pick a video", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, "Record a video", onTakeVideo)
                is GifState.Ready -> Editor(s, loader, vm, onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) })
                is GifState.Working -> Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProgressRing(s.progress, Modifier.size(132.dp)) { Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium) }
                    Text("Making your GIF…", style = MaterialTheme.typography.titleMedium)
                    TextButton(vm::cancel) { Text("Cancel") }
                }
                is GifState.Done -> Result(s.result, loader, onShare = { ctx.startActivity(GifMaker.shareIntent(s.result.uri)) }, onAgain = vm::back, onNew = vm::reset)
                is GifState.Failed -> EmptyState(Icons.Default.ErrorOutline, "Couldn't make the GIF", s.message, "Try again", vm::back)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(s: GifState.Ready, loader: ImageLoader, vm: GifViewModel, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val st = s.settings
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        AsyncImage(model = ImageRequest.Builder(ctx).data(s.video).videoFrameMillis(st.startMs).build(), contentDescription = "Start of your GIF", imageLoader = loader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (st.caption.isNotBlank()) Text(st.caption.uppercase(), Modifier.align(Alignment.BottomCenter).padding(12.dp), style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, textAlign = TextAlign.Center)
    }
    Text("Pick the moment", style = MaterialTheme.typography.titleSmall)
    var range by remember(s.video) { mutableStateOf(st.startMs.toFloat()..st.endMs.toFloat()) }
    RangeSlider(range, { range = it }, valueRange = 0f..s.durationMs.toFloat(), onValueChangeFinished = { vm.update { it.copy(startMs = range.start.toLong(), endMs = range.endInclusive.toLong()) } })
    Text("${fmt(st.startMs)} – ${fmt(st.endMs)}  ·  ${"%.1f".format(st.lengthMs / 1000f)} s (max ${Entitlements.gifMaxMs(Pro.isPro) / 1000} s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    if (!Pro.isPro && s.durationMs > Entitlements.FREE_GIF_MS) {
        val gate = LocalProGate.current
        TextButton({ gate("GIFs up to ${Entitlements.PRO_GIF_MS / 1000} seconds") }, contentPadding = PaddingValues(0.dp)) { ProBadge(); Text("  Free GIFs are up to ${Entitlements.FREE_GIF_MS / 1000} s. Get ${Entitlements.PRO_GIF_MS / 1000} s with Pro", style = MaterialTheme.typography.labelMedium) }
    }
    Text("Size", style = MaterialTheme.typography.titleSmall)
    ProChipRow(GifSize.entries, st.size, { "${it.label} ${it.width}" }, { !Entitlements.gifSize(it, Pro.isPro) }, "Large GIFs") { v -> vm.update { it.copy(size = v) } }
    Text("Smoothness", style = MaterialTheme.typography.titleSmall)
    ProChipRow(GifSettings.FPS_CHOICES, st.fps, { "$it fps" }, { !Entitlements.gifFps(it, Pro.isPro) }, "Smoother 20 fps GIFs") { v -> vm.update { it.copy(fps = v) } }
    if (!Pro.isPro) ProTagNote(forGif = true)
    Text("Loop style", style = MaterialTheme.typography.titleSmall)
    ProChipRow(GifStyle.entries, st.style, { it.label }, { !Entitlements.gifStyle(it, Pro.isPro) }, "Reverse GIFs") { v -> vm.update { it.copy(style = v) } }
    Text("Speed", style = MaterialTheme.typography.titleSmall)
    ProChipRow(GifSettings.SPEED_CHOICES, st.speed, { if (it == 1f) "1× normal" else "$it×".replace(".0×", "×") }, { !Entitlements.gifSpeed(it, Pro.isPro) }, "Slow-motion and fast GIFs") { v -> vm.update { it.copy(speed = v) } }
    OutlinedTextField(st.caption, { v -> vm.update { it.copy(caption = v.take(60)) } }, Modifier.fillMaxWidth(), label = { Text("Caption (optional)") }, singleLine = true, shape = RoundedCornerShape(16.dp))
    Text("About ${st.outputFrames} frames at ${st.size.width} px wide", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LogoSetting(st.logoUri, st.logoCorner, vm::setLogo) { c -> vm.update { it.copy(logoCorner = c) } }
    Button(vm::make, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Gif, null); Text(" Make GIF") }
    TextButton(onPick, Modifier.fillMaxWidth()) { Text("Choose a different video") }
}

@Composable
private fun Result(r: GifMaker.Result, loader: ImageLoader, onShare: () -> Unit, onAgain: () -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        AsyncImage(model = ImageRequest.Builder(ctx).data(r.uri).build(), contentDescription = "Your GIF, playing", imageLoader = loader, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), contentScale = ContentScale.Fit)
        Confetti(true, Modifier.matchParentSize(), seed = r.uri.hashCode())
    }
    Text("GIF saved to Pictures/PhotoVideoStudio", style = MaterialTheme.typography.titleMedium)
    Text("${r.width}×${r.height} · ${r.frames} frames · ${"%.1f".format(r.bytes / 1024f / 1024f)} MB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A GIF is an image, so video-only destinations (YouTube) are left out.
    val targets = remember { ShareTargets.installed(ctx).filter { it.pkg != "com.google.android.youtube" } }
    if (targets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { targets.take(3).forEach { t -> AssistChip({ ShareTargets.share(ctx, r.uri, t, "image/gif") }, { Text(t.label) }) } }
    Button(onShare, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Share, null); Text(" Share GIF") }
    OutlinedButton(onAgain, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Change settings") }
    TextButton(onNew, Modifier.fillMaxWidth()) { Text("Make another from a new video") }
}

private fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)
