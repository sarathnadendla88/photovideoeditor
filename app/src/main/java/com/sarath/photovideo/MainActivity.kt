package com.sarath.photovideo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

class MainActivity : ComponentActivity(), com.razorpay.PaymentResultWithDataListener {
    private val vm: SlideshowViewModel by viewModels()
    private val videoVm: VideoViewModel by viewModels()
    /** Files shared into the app from another app; consumed once by the UI. */
    private var incoming by mutableStateOf<Incoming?>(null)
    /** Launcher shortcut that opened the app (an intent action), consumed once by the UI. */
    private var shortcut by mutableStateOf<String?>(null)

    // Razorpay reports the result of a checkout opened from this activity.
    override fun onPaymentSuccess(razorpayPaymentId: String?, data: com.razorpay.PaymentData?) = Payments.coordinator.onSuccess(razorpayPaymentId, data?.orderId, data?.signature)
    override fun onPaymentError(code: Int, description: String?, data: com.razorpay.PaymentData?) = Payments.coordinator.onError(code, description)

    private fun readIncoming(intent: Intent?): Incoming? {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return null
        val uris = if (intent.action == Intent.ACTION_SEND) listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
                   else androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        fun kind(u: android.net.Uri): String = runCatching { contentResolver.getType(u) }.getOrNull() ?: intent.type.orEmpty()
        val videos = uris.filter { kind(it).startsWith("video") }
        val images = uris.filter { kind(it).startsWith("image") }
        return if (videos.isEmpty() && images.isEmpty()) null else Incoming(videos, images)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = readIncoming(intent); shortcut = intent.action?.takeIf { Shortcuts.screenFor(it) != null } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { incoming = readIncoming(intent); shortcut = intent.action?.takeIf { Shortcuts.screenFor(it) != null } }
        Pro.init(this)
        MotionPrefs.load(getSharedPreferences("app", MODE_PRIVATE)); ThemePrefs.load(getSharedPreferences("app", MODE_PRIVATE))
        if (!Pro.isPro) Ads.start(this)   // Pro people never load the ads SDK at all
        setContent {
            StudioTheme {
                val prefs = remember { getSharedPreferences("app", MODE_PRIVATE).also { MotionPrefs.load(it) } }
                var showIntro by remember { mutableStateOf(!prefs.getBoolean("intro_done", false)) }
                var showSettings by remember { mutableStateOf(false) }
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var cameraMode by rememberSaveable { mutableStateOf(CameraMode.PHOTO) }
                var photoToEdit by remember { mutableStateOf<android.net.Uri?>(null) }
                var cameraFor by rememberSaveable { mutableStateOf<Screen?>(null) }       // set when the camera was opened from an editor
                var collagePhoto by remember { mutableStateOf<android.net.Uri?>(null) }
                var gifVideo by remember { mutableStateOf<android.net.Uri?>(null) }
                LaunchedEffect(shortcut) {
                    val act = shortcut ?: return@LaunchedEffect
                    shortcut = null; showIntro = false
                    cameraFor = null
                    if (act == Shortcuts.REEL) cameraMode = CameraMode.REELS
                    Shortcuts.screenFor(act)?.let { screen = it }
                }
                LaunchedEffect(incoming) {
                    val inc = incoming ?: return@LaunchedEffect
                    incoming = null; showIntro = false
                    when {
                        inc.videos.isNotEmpty() -> { videoVm.startWith(inc.videos); cameraFor = null; screen = Screen.VIDEO }
                        inc.images.size > 1 -> { vm.addPhotos(inc.images); cameraFor = null; screen = Screen.SLIDESHOW }
                        inc.images.isNotEmpty() -> { photoToEdit = inc.images.first(); cameraFor = null; screen = Screen.PHOTO }
                    }
                }
                fun takeFrom(from: Screen, mode: CameraMode) { cameraFor = from; cameraMode = mode; screen = Screen.CAMERA }
                BackHandler(screen != Screen.HOME) { screen = Screen.HOME }
                if (showIntro) { Onboarding { prefs.edit().putBoolean("intro_done", true).apply(); showIntro = false }; return@StudioTheme }
                if (showSettings) SettingsDialog({ showSettings = false }, { showSettings = false; showIntro = true })
                androidx.compose.animation.AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val deeper = targetState != Screen.HOME
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, 60)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300)) { if (deeper) it / 8 else -it / 8 }) togetherWith
                            (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) + androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(300)) { if (deeper) -it / 8 else it / 8 })
                    },
                    label = "screen",
                ) { shown -> when (shown) {
                    Screen.HOME -> HomeScreen(vm.project.collectAsStateWithLifecycle().value.photos.size, { screen = it }, vm::applyTemplate,
                        openCamera = { cameraFor = null; cameraMode = it; screen = Screen.CAMERA }, openSettings = { showSettings = true })
                    Screen.CAMERA -> {
                        val from = cameraFor
                        val back = { cameraFor = null; screen = from ?: Screen.HOME }
                        BackHandler { back() }
                        CameraScreen(cameraMode, onBack = back,
                            modes = when (from) { null -> CameraMode.entries; Screen.VIDEO, Screen.GIF -> listOf(CameraMode.VIDEO); else -> listOf(CameraMode.PHOTO) },
                            useLabel = when (from) { Screen.PHOTO -> "Edit this photo"; Screen.SLIDESHOW -> "Add to slideshow"; Screen.COLLAGE -> "Add to collage"; Screen.VIDEO -> "Add to video"; Screen.GIF -> "Make a GIF"; else -> null },
                            onEditPhoto = { uri ->
                                when (from) {
                                    Screen.SLIDESHOW -> { vm.addPhotos(listOf(uri)); screen = Screen.SLIDESHOW }
                                    Screen.COLLAGE -> { collagePhoto = uri; screen = Screen.COLLAGE }
                                    else -> { photoToEdit = uri; screen = Screen.PHOTO }
                                }
                                cameraFor = null
                            },
                            onEditVideo = { uris ->
                                when (from) {
                                    Screen.GIF -> { gifVideo = uris.firstOrNull(); screen = Screen.GIF }
                                    Screen.VIDEO -> { videoVm.addVideos(uris); screen = Screen.VIDEO }
                                    else -> { videoVm.startWith(uris, CanvasPreset.PORTRAIT); screen = Screen.VIDEO }
                                }
                                cameraFor = null
                            })
                    }
                    Screen.RINGTONE -> RingtoneScreen { screen = Screen.HOME }
                    Screen.GIF -> GifScreen(gifVideo, onConsumed = { gifVideo = null }, onTakeVideo = { takeFrom(Screen.GIF, CameraMode.VIDEO) }) { screen = Screen.HOME }
                    Screen.COLLAGE -> CollageScreen(collagePhoto, onConsumed = { collagePhoto = null }, onTakePhoto = { takeFrom(Screen.COLLAGE, CameraMode.PHOTO) }) { screen = Screen.HOME }
                    Screen.PHOTO -> PhotoEditorScreen(photoToEdit, onTakePhoto = { takeFrom(Screen.PHOTO, CameraMode.PHOTO) }) { photoToEdit = null; screen = Screen.HOME }
                    Screen.VIDEO -> VideoEditorScreen(videoVm, onTakeVideo = { takeFrom(Screen.VIDEO, CameraMode.VIDEO) }) { screen = Screen.HOME }
                    Screen.SLIDESHOW -> SlideshowScreen(vm, onTakePhoto = { takeFrom(Screen.SLIDESHOW, CameraMode.PHOTO) }) { screen = Screen.HOME }
                } }
            }
        }
    }
}

@Composable
fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit, onShare: (android.net.Uri) -> Unit) {
    when (state) {
        ExportState.Idle -> Unit
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProgressRing(state.progress, Modifier.size(104.dp)) { Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium) }
                    Text("Rendering on your device. Keep the app open.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ExportState.Done -> androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val ctx = LocalContext.current
            val targets = remember { ShareTargets.installed(ctx) }
            val activity = ctx.findActivity()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(Modifier.padding(horizontal = 28.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedCheck(Modifier.size(84.dp))
                        Text("Saved to Movies/PhotoVideoStudio", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        if (targets.isNotEmpty()) Text("Post it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            targets.forEach { t -> AssistChip({ ShareTargets.share(ctx, state.uri, t) }, { Text(t.label) }) }
                        }
                        if (targets.isEmpty()) Text("Use Share to send it to any app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val gate = LocalProGate.current
                        // Shown only after the file is saved, never blocks anything, and only while payments are set up.
                        if (!Pro.isPro && Payments.config.enabled) TextButton({ onDismiss(); gate("No ads and no tag on your exports") }) { ProBadge(); Text("  Remove ads and the tag · ${Payments.config.displayPrice} once", style = MaterialTheme.typography.labelMedium) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            // The result is already saved here; an ad (capped, only if preloaded) may follow, never precede it.
                            TextButton(onClick = { onDismiss(); activity?.let(Ads::maybeShowAfterExport) }) { Text("Done") }
                            Button(onClick = { onShare(state.uri) }) { Text("Share") }
                        }
                    }
                }
                Confetti(true, Modifier.fillMaxSize(), seed = state.uri.hashCode())
            }
        }
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Export failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onRetry) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

/** Hands the saved video to the YouTube app's own upload screen (you finish and publish it there); falls back to the share sheet. */
fun shareToYouTube(ctx: android.content.Context, uri: android.net.Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent(send).setPackage("com.google.android.youtube")) }
    catch (e: android.content.ActivityNotFoundException) { ctx.startActivity(Intent.createChooser(send, "Share video")) }
}

fun shareVideo(ctx: android.content.Context, uri: android.net.Uri) {
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video"))
}

data class Incoming(val videos: List<android.net.Uri>, val images: List<android.net.Uri>)

enum class Screen { HOME, PHOTO, VIDEO, SLIDESHOW, COLLAGE, CAMERA, RINGTONE, GIF }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(draftPhotos: Int, go: (Screen) -> Unit, useTemplate: (SlideshowTemplate) -> Unit, openCamera: (CameraMode) -> Unit, openSettings: () -> Unit) {
    val gate = LocalProGate.current
    AuroraBackground(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(containerColor = Color.Transparent, bottomBar = { HomeBanner(Modifier.navigationBarsPadding()) }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(Studio.Gutter), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.entrance(0), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ReelMint", style = MaterialTheme.typography.headlineLarge.copy(brush = Brush.linearGradient(listOf(Color.White.copy(alpha = if (isSystemInDarkTheme()) 1f else 0.2f), Studio.Violet, Studio.Coral).let { if (isSystemInDarkTheme()) listOf(Color(0xFFEDE9FF), Color(0xFFB7A6FF), Color(0xFFFF9DB4)) else listOf(Studio.Indigo, Studio.Violet, Studio.Coral) })))
                        if (Pro.isPro) { Spacer(Modifier.width(10.dp)); ProBadge() }
                        }
                        Text("Create and edit on your device. Nothing is uploaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(openSettings, Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))) { Icon(Icons.Default.Settings, "Settings and privacy") }
                }
                Row(Modifier.entrance(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroCard(ThemePrefs.active.hero, Icons.Default.Videocam, "Create a Reel", "Record clips, add music & text", Modifier.weight(1f).height(158.dp)) { openCamera(CameraMode.REELS) }
                    HeroCard(ThemePrefs.active.hero.reversed(), Icons.Default.Movie, "New slideshow", "Photos + music → MP4", Modifier.weight(1f).height(158.dp), phase = 0.5f) { go(Screen.SLIDESHOW) }
                }
                if (!Pro.isPro && Payments.config.enabled) GoProCard(Modifier.entrance(2)) { gate("") }
                if (draftPhotos > 0) ElevatedCard({ go(Screen.SLIDESHOW) }, Modifier.fillMaxWidth().entrance(2), shape = Studio.CardShape) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column { Text("Continue your draft", style = MaterialTheme.typography.titleSmall); Text("$draftPhotos photos saved on this device", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                SectionTitle("Tools", Modifier.entrance(2))
                val tools = listOf(
                    Triple(Icons.Default.CameraAlt, "Camera" to "Photo & video", Studio.Sky to Studio.Indigo) to { openCamera(CameraMode.PHOTO) },
                    Triple(Icons.Default.Tune, "Photo" to "Edit & retouch", Studio.Violet to Studio.Indigo) to { go(Screen.PHOTO) },
                    Triple(Icons.Default.VideoLibrary, "Video" to "Trim, speed, text", Studio.Coral to Studio.Violet) to { go(Screen.VIDEO) },
                    Triple(Icons.Default.GridView, "Collage" to "Grids & layouts", Studio.Teal to Studio.Sky) to { go(Screen.COLLAGE) },
                    Triple(Icons.Default.Face, "Portrait" to "Blur / remove bg", Studio.Amber to Studio.Coral) to { go(Screen.PHOTO) },
                    Triple(Icons.Default.RecordVoiceOver, "Voiceover" to "Record or type", Studio.Teal to Studio.Violet) to { go(Screen.VIDEO) },
                    Triple(Icons.Default.NotificationsActive, "Ringtone" to "Cut a song", Studio.Violet to Studio.Coral) to { go(Screen.RINGTONE) },
                    Triple(Icons.Default.Gif, "GIF maker" to "Video → GIF", Studio.Amber to Studio.Violet) to { go(Screen.GIF) },
                )
                tools.chunked(2).forEachIndexed { row, pair ->
                    Row(Modifier.entrance(3 + row), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (spec, click) ->
                            val (icon, text, colors) = spec
                            GlassTile(icon, text.first, text.second, colors.first, colors.second, Modifier.weight(1f).height(78.dp), click)
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                RecentCreations()
                SectionTitle("Slideshow templates")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(SlideshowTemplates) { t ->
                        GradientCard(Studio.tile(t.from, t.to), Modifier.width(160.dp).height(120.dp), { if (Entitlements.template(t, Pro.isPro)) { useTemplate(t); go(Screen.SLIDESHOW) } else gate("The ${t.name} template") }) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(t.icon, null, tint = Color.White); Spacer(Modifier.weight(1f)); if (!Entitlements.template(t, Pro.isPro)) ProBadge() }
                            Spacer(Modifier.weight(1f))
                            Text(t.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(t.tagline, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
