package com.studio.photovideo

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

enum class CameraMode(val label: String) { PHOTO("Photo"), VIDEO("Video"), REELS("Reel") }

private const val REEL_MAX_MS = 60_000L

/**
 * Capture screen (CameraX). Photo/Video save to the gallery; Reel mode records short segments into app storage and
 * hands them to the video editor, so nothing is written to the gallery until the user exports.
 */
@Composable
fun CameraScreen(
    initialMode: CameraMode, onBack: () -> Unit,
    onEditPhoto: (Uri) -> Unit, onEditVideo: (List<Uri>) -> Unit,
    modes: List<CameraMode> = CameraMode.entries, useLabel: String? = null,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var mode by remember { mutableStateOf(initialMode) }
    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPermissions(ctx) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!granted) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.CameraAlt, null, Modifier.size(56.dp), tint = Color.White)
                Text("Camera and microphone", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Needed to take photos and record videos. Nothing is uploaded; your recordings stay on this device.", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodyMedium)
                Button({ perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Allow access") }
                TextButton(onBack) { Text("Not now") }
            }
        } else CameraContent(mode, { mode = it }, owner, onBack, onEditPhoto, onEditVideo, modes, useLabel)
    }
}

private fun hasPermissions(ctx: android.content.Context) =
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun CameraContent(
    mode: CameraMode, setMode: (CameraMode) -> Unit, owner: androidx.lifecycle.LifecycleOwner,
    onBack: () -> Unit, onEditPhoto: (Uri) -> Unit, onEditVideo: (List<Uri>) -> Unit,
    modes: List<CameraMode>, useLabel: String?,
) {
    val ctx = LocalContext.current
    val executor = remember { ContextCompat.getMainExecutor(ctx) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var timerSec by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var prompter by rememberSaveable { mutableStateOf(false) }
    var script by rememberSaveable { mutableStateOf("") }
    var promptSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var editScript by remember { mutableStateOf(false) }
    val promptScroll = rememberScrollState()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var savedPhoto by remember { mutableStateOf<Uri?>(null) }
    var savedVideo by remember { mutableStateOf<Uri?>(null) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    // Reel segments (app-private files) and the active recording.
    val segments = remember { mutableStateListOf<Pair<File, Long>>() }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordMs by remember { mutableLongStateOf(0L) }
    val reelTotalMs = segments.sumOf { it.second } + if (recording != null && mode == CameraMode.REELS) recordMs else 0L

    DisposableEffect(front) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, videoCapture)
            }.onFailure { message = "Couldn't open the camera." }
        }, executor)
        onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }
    }
    LaunchedEffect(flash, camera, mode) {
        camera?.cameraControl?.enableTorch(flash && mode != CameraMode.PHOTO)
        imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
    LaunchedEffect(recording) {
        val started = System.currentTimeMillis(); recordMs = 0
        while (recording != null) { recordMs = System.currentTimeMillis() - started; delay(100) }
    }

    // Teleprompter: the script scrolls only while recording, at the chosen speed.
    LaunchedEffect(recording != null, prompter, promptSpeed) {
        if (recording != null && prompter) while (true) { promptScroll.scrollBy(promptSpeed * 1.4f); delay(16) }
    }

    fun stopRecording() { recording?.stop() }

    fun startRecording() {
        val isReel = mode == CameraMode.REELS
        val file = File(ctx.cacheDir, "reel_${System.nanoTime()}.mp4")
        val pending = if (isReel) {
            recorder.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Video_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PhotoVideoStudio")
            }
            recorder.prepareRecording(ctx, MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build())
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending.withAudioEnabled()
        val startedAt = System.currentTimeMillis()
        recording = pending.start(executor) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA && file.length() == 0L && ev.outputResults.outputUri == Uri.EMPTY) message = "Recording failed."
                else if (isReel) { if (file.length() > 0) segments.add(file to (System.currentTimeMillis() - startedAt)) else message = "That clip was too short." }
                else if (ev.outputResults.outputUri != Uri.EMPTY) savedVideo = ev.outputResults.outputUri
            }
        }
    }

    fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoVideoStudio")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        imageCapture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { savedPhoto = r.savedUri }
            override fun onError(e: ImageCaptureException) { message = "Couldn't take the photo." }
        })
    }

    fun capture() {
        if (recording != null) { stopRecording(); return }
        if (mode == CameraMode.REELS && reelTotalMs >= REEL_MAX_MS) { message = "Reels are limited to 60 seconds."; return }
        val action = { if (mode == CameraMode.PHOTO) takePhoto() else startRecording() }
        if (timerSec == 0) action() else countdown = timerSec
        pendingAction = if (timerSec == 0) null else action
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown -= 1; if (countdown == 0) { pendingAction?.invoke(); pendingAction = null } }
    }
    // Auto-stop a reel at the limit.
    LaunchedEffect(reelTotalMs, recording) { if (mode == CameraMode.REELS && recording != null && reelTotalMs >= REEL_MAX_MS) stopRecording() }
    LaunchedEffect(message) { message?.let { delay(2500); message = null } }

    Box(Modifier.fillMaxSize()) {
    AndroidView({ previewView }, Modifier.fillMaxSize())

    // Top bar
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { recording?.stop(); onBack() }
        Spacer(Modifier.weight(1f))
        if (recording != null) Text("● ${fmtMs(if (mode == CameraMode.REELS) reelTotalMs else recordMs)}", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
        Spacer(Modifier.weight(1f))
        if (mode != CameraMode.PHOTO) { RoundIcon(Icons.Default.Notes, if (prompter) "Hide teleprompter" else "Show teleprompter") { prompter = !prompter; if (prompter && script.isBlank()) editScript = true }; Spacer(Modifier.width(8.dp)) }
        RoundIcon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, if (flash) "Flash on" else "Flash off") { flash = !flash }
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Default.Timer, "Timer ${if (timerSec == 0) "off" else "$timerSec seconds"}", label = if (timerSec == 0) null else "${timerSec}s") { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } }
    }

    if (prompter && mode != CameraMode.PHOTO) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp).fillMaxWidth().heightIn(max = 260.dp).background(Color(0x99000000), RoundedCornerShape(16.dp))) {
        Text(if (script.isBlank()) "Tap Edit to add your script" else script, Modifier.verticalScroll(promptScroll, enabled = recording == null).padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = 200.dp),
            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
        if (recording == null) TextButton({ editScript = true }, Modifier.align(Alignment.TopEnd)) { Text("Edit", color = Studio.Amber) }
    }
    if (editScript) AlertDialog(onDismissRequest = { editScript = false }, title = { Text("Teleprompter") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(script, { script = it }, label = { Text("Your script") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            Text("Scroll speed", style = MaterialTheme.typography.labelMedium)
            Slider(promptSpeed, { promptSpeed = it }, valueRange = 0.4f..3f)
            Text("The script scrolls while you record and is not part of the video.", style = MaterialTheme.typography.labelMedium)
        } },
        confirmButton = { TextButton({ editScript = false }) { Text("Done") } })
    if (countdown > 0) Text("$countdown", Modifier.align(Alignment.Center), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)

    // Bottom controls
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        message?.let { Text(it, color = Color.White, modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) }
        if (mode == CameraMode.REELS) {
            LinearProgressIndicator(progress = { (reelTotalMs / REEL_MAX_MS.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(3.dp)), color = Studio.Coral, trackColor = Color(0x55FFFFFF))
            Text("${segments.size} clips · ${fmtMs(reelTotalMs)} of 1:00", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (mode == CameraMode.REELS && segments.isNotEmpty() && recording == null)
                RoundIcon(Icons.Default.Backspace, "Delete last clip") { segments.removeLastOrNull()?.first?.delete() }
            else Spacer(Modifier.size(44.dp))
            // Shutter
            val rec = recording != null
            Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                .background(if (mode == CameraMode.PHOTO) Color.White else Color(0xFFFF3B5C)).clickable(onClickLabel = "Capture") { capture() }, contentAlignment = Alignment.Center) {
                if (rec) Box(Modifier.size(26.dp).background(Color.White, RoundedCornerShape(6.dp)))
            }
            if (mode == CameraMode.REELS && segments.isNotEmpty() && recording == null)
                FilledIconButton({ onEditVideo(segments.map { Uri.fromFile(it.first) }) }, Modifier.size(44.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Studio.Violet)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next: edit reel") }
            else RoundIcon(Icons.Default.FlipCameraAndroid, "Switch camera") { if (recording == null) front = !front }
        }
        // Always laid out (just hidden while recording) so the shutter button never jumps.
        Row(Modifier.alpha(if (recording == null) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (modes.size > 1) modes.forEach { m ->
                Text(m.label, Modifier.clip(RoundedCornerShape(50)).background(if (m == mode) Color.White else Color(0x66000000)).clickable(enabled = recording == null) { setMode(m) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (m == mode) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    }
    savedPhoto?.let { uri -> CaptureDialog("Photo saved", "Saved to Pictures/PhotoVideoStudio.", useLabel ?: "Edit photo", { savedPhoto = null; onEditPhoto(uri) }, { savedPhoto = null }) }
    savedVideo?.let { uri -> CaptureDialog("Video saved", "Saved to Movies/PhotoVideoStudio.", useLabel ?: "Edit video", { savedVideo = null; onEditVideo(listOf(uri)) }, { savedVideo = null }) }
}

@Composable
private fun CaptureDialog(title: String, body: String, editLabel: String, onEdit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onEdit) { Text(editLabel) } }, dismissButton = { TextButton(onDismiss) { Text("Keep shooting") } })
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, label: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White)
        if (label != null) Text(label, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp), color = Studio.Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
