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

class MainActivity : ComponentActivity() {
    private val vm: SlideshowViewModel by viewModels()
    private val videoVm: VideoViewModel by viewModels()
    /** Files shared into the app from another app; consumed once by the UI. */
    private var incoming by mutableStateOf<Incoming?>(null)

    private fun readIncoming(intent: Intent?): Incoming? {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return null
        val uris = if (intent.action == Intent.ACTION_SEND) listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
                   else androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        fun kind(u: android.net.Uri): String = runCatching { contentResolver.getType(u) }.getOrNull() ?: intent.type.orEmpty()
        val videos = uris.filter { kind(it).startsWith("video") }
        val images = uris.filter { kind(it).startsWith("image") }
        return if (videos.isEmpty() && images.isEmpty()) null else Incoming(videos, images)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = readIncoming(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming = readIncoming(intent)
        Ads.start(this)
        setContent {
            StudioTheme {
                val prefs = remember { getSharedPreferences("app", MODE_PRIVATE) }
                var showIntro by remember { mutableStateOf(!prefs.getBoolean("intro_done", false)) }
                var showSettings by remember { mutableStateOf(false) }
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var cameraMode by rememberSaveable { mutableStateOf(CameraMode.PHOTO) }
                var photoToEdit by remember { mutableStateOf<android.net.Uri?>(null) }
                var cameraFor by rememberSaveable { mutableStateOf<Screen?>(null) }       // set when the camera was opened from an editor
                var collagePhoto by remember { mutableStateOf<android.net.Uri?>(null) }
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
                when (screen) {
                    Screen.HOME -> HomeScreen(vm.project.collectAsStateWithLifecycle().value.photos.size, { screen = it }, vm::applyTemplate,
                        openCamera = { cameraFor = null; cameraMode = it; screen = Screen.CAMERA }, openSettings = { showSettings = true })
                    Screen.CAMERA -> {
                        val from = cameraFor
                        val back = { cameraFor = null; screen = from ?: Screen.HOME }
                        BackHandler { back() }
                        CameraScreen(cameraMode, onBack = back,
                            modes = when (from) { null -> CameraMode.entries; Screen.VIDEO -> listOf(CameraMode.VIDEO); else -> listOf(CameraMode.PHOTO) },
                            useLabel = when (from) { Screen.PHOTO -> "Edit this photo"; Screen.SLIDESHOW -> "Add to slideshow"; Screen.COLLAGE -> "Add to collage"; Screen.VIDEO -> "Add to video"; else -> null },
                            onEditPhoto = { uri ->
                                when (from) {
                                    Screen.SLIDESHOW -> { vm.addPhotos(listOf(uri)); screen = Screen.SLIDESHOW }
                                    Screen.COLLAGE -> { collagePhoto = uri; screen = Screen.COLLAGE }
                                    else -> { photoToEdit = uri; screen = Screen.PHOTO }
                                }
                                cameraFor = null
                            },
                            onEditVideo = { uris ->
                                if (from == Screen.VIDEO) videoVm.addVideos(uris) else videoVm.startWith(uris, CanvasPreset.PORTRAIT)
                                cameraFor = null; screen = Screen.VIDEO
                            })
                    }
                    Screen.RINGTONE -> RingtoneScreen { screen = Screen.HOME }
                    Screen.COLLAGE -> CollageScreen(collagePhoto, onConsumed = { collagePhoto = null }, onTakePhoto = { takeFrom(Screen.COLLAGE, CameraMode.PHOTO) }) { screen = Screen.HOME }
                    Screen.PHOTO -> PhotoEditorScreen(photoToEdit, onTakePhoto = { takeFrom(Screen.PHOTO, CameraMode.PHOTO) }) { photoToEdit = null; screen = Screen.HOME }
                    Screen.VIDEO -> VideoEditorScreen(videoVm, onTakeVideo = { takeFrom(Screen.VIDEO, CameraMode.VIDEO) }) { screen = Screen.HOME }
                    Screen.SLIDESHOW -> SlideshowScreen(vm, onTakePhoto = { takeFrom(Screen.SLIDESHOW, CameraMode.PHOTO) }) { screen = Screen.HOME }
                }
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
            text = { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Saved to Movies/PhotoVideoStudio") },
            text = {
                val ctx = LocalContext.current
                TextButton(onClick = { shareToYouTube(ctx, state.uri) }) { Icon(Icons.Default.OndemandVideo, null); Spacer(Modifier.width(8.dp)); Text("Upload to YouTube") }
            },
            confirmButton = { TextButton(onClick = { onShare(state.uri) }) { Text("Share") } },
            dismissButton = {
                val activity = LocalContext.current.findActivity()
                // The result is already saved here; an ad (capped, only if preloaded) may follow, never precede it.
                TextButton(onClick = { onDismiss(); activity?.let(Ads::maybeShowAfterExport) }) { Text("Done") }
            },
        )
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

enum class Screen { HOME, PHOTO, VIDEO, SLIDESHOW, COLLAGE, CAMERA, RINGTONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(draftPhotos: Int, go: (Screen) -> Unit, useTemplate: (SlideshowTemplate) -> Unit, openCamera: (CameraMode) -> Unit, openSettings: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { HomeBanner(Modifier.navigationBarsPadding()) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(Studio.Gutter), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("ReelMint", style = MaterialTheme.typography.headlineLarge)
                    Text("Create and edit on your device. Nothing is uploaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(openSettings) { Icon(Icons.Default.Settings, "Settings and privacy") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GradientCard(Studio.tile(Studio.Coral, Studio.Violet), Modifier.weight(1f).height(150.dp), { openCamera(CameraMode.REELS) }) {
                    Icon(Icons.Default.Videocam, null, Modifier.size(32.dp), tint = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text("Create a Reel", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("Record clips, add music & text", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                GradientCard(Studio.HeroGradient, Modifier.weight(1f).height(150.dp), { go(Screen.SLIDESHOW) }) {
                    Icon(Icons.Default.Movie, null, Modifier.size(32.dp), tint = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text("New slideshow", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("Photos + music → MP4", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (draftPhotos > 0) ElevatedCard({ go(Screen.SLIDESHOW) }, Modifier.fillMaxWidth(), shape = Studio.CardShape) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Column { Text("Continue your draft", style = MaterialTheme.typography.titleSmall); Text("$draftPhotos photos saved on this device", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Text("Tools", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Default.CameraAlt, "Camera", "Photo & video", Studio.Sky, Studio.Indigo, Modifier.weight(1f)) { openCamera(CameraMode.PHOTO) }
                ToolTile(Icons.Default.Tune, "Photo", "Edit & retouch", Studio.Violet, Studio.Indigo, Modifier.weight(1f)) { go(Screen.PHOTO) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Default.VideoLibrary, "Video", "Trim, speed, text", Studio.Coral, Studio.Violet, Modifier.weight(1f)) { go(Screen.VIDEO) }
                ToolTile(Icons.Default.GridView, "Collage", "Grids & layouts", Studio.Teal, Studio.Sky, Modifier.weight(1f)) { go(Screen.COLLAGE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Default.Face, "Portrait", "Blur / remove bg", Studio.Amber, Studio.Coral, Modifier.weight(1f)) { go(Screen.PHOTO) }
                ToolTile(Icons.Default.RecordVoiceOver, "Voiceover", "Record or type", Studio.Teal, Studio.Violet, Modifier.weight(1f)) { go(Screen.VIDEO) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Default.NotificationsActive, "Ringtone", "Cut a song", Studio.Violet, Studio.Coral, Modifier.weight(1f)) { go(Screen.RINGTONE) }
                Spacer(Modifier.weight(1f))
            }
            RecentCreations()
            Text("Slideshow templates", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(SlideshowTemplates) { t ->
                    GradientCard(Studio.tile(t.from, t.to), Modifier.width(160.dp).height(120.dp), { useTemplate(t); go(Screen.SLIDESHOW) }) {
                        Icon(t.icon, null, tint = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(t.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(t.tagline, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, a: Color, b: Color, modifier: Modifier, onClick: () -> Unit) {
    GradientCard(Studio.tile(a, b), modifier.height(110.dp), onClick) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.weight(1f))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
    }
}
