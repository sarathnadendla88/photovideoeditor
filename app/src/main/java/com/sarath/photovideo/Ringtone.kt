package com.sarath.photovideo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Range rules for a ringtone. You can pick any part of the song; only a minimum of one second is enforced.
 * [LONG_MS] is just a hint threshold: many phones stop ringing after roughly 30–40 s, so a longer tone is allowed but flagged.
 */
object RingtoneRange {
    const val MIN_MS = 1_000L
    const val LONG_MS = 40_000L

    /** Keeps the selection inside the song and at least [MIN_MS] long. [movedStart] says which end the user is dragging, so the other end stays put. */
    fun clamp(startMs: Long, endMs: Long, durationMs: Long, movedStart: Boolean): Pair<Long, Long> {
        var s = startMs.coerceIn(0, durationMs); var e = endMs.coerceIn(0, durationMs)
        if (e - s < MIN_MS) { if (movedStart) s = (e - MIN_MS).coerceAtLeast(0) else e = (s + MIN_MS).coerceAtMost(durationMs) }
        return s to e
    }

    /** First selection: up to 30 s from the start of the song (the user can then drag either end anywhere). */
    fun initial(durationMs: Long): Pair<Long, Long> = 0L to minOf(30_000L, durationMs)

    fun isLong(startMs: Long, endMs: Long) = endMs - startMs > LONG_MS
}

enum class TonePurpose(val label: String, val type: Int) { RINGTONE("Phone ringtone", RingtoneManager.TYPE_RINGTONE), NOTIFICATION("Notification", RingtoneManager.TYPE_NOTIFICATION), ALARM("Alarm", RingtoneManager.TYPE_ALARM) }

sealed interface ToneState {
    data object Idle : ToneState
    data class Working(val progress: Float) : ToneState
    data class Saved(val uri: Uri, val name: String) : ToneState
    data class Failed(val message: String) : ToneState
}

/** Cuts a segment of a song to an AAC .m4a on-device and registers it with MediaStore as a ringtone. Main thread only. */
class RingtoneExporter(private val context: Context) {
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    fun start(source: Uri, startMs: Long, endMs: Long, fadeInMs: Long, fadeOutMs: Long, title: String, onState: (ToneState) -> Unit) {
        cancel()
        val out = File(context.cacheDir, "export_tone_${System.currentTimeMillis()}.m4a")
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        val len = endMs - startMs
        val fx = if (fadeInMs > 0 || fadeOutMs > 0) listOf(FadeAudioProcessor(fadeInMs, fadeOutMs, len)) else emptyList()
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build())
            .setRemoveVideo(true).setEffects(Effects(fx, emptyList())).build()
        val t = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
            override fun onCompleted(c: Composition, r: ExportResult) {
                stopPoll()
                val saved = runCatching { saveToRingtones(out, title) }
                out.delete()
                onState(saved.fold({ ToneState.Saved(it, title) }, { ToneState.Failed("Could not save the ringtone: ${it.message}") }))
            }
            override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                stopPoll(); out.delete()
                android.util.Log.e("RingtoneExporter", "failed ${e.errorCodeName}", e)
                onState(ToneState.Failed(if (e.errorCode == ExportException.ERROR_CODE_IO_FILE_NOT_FOUND || e.errorCode == ExportException.ERROR_CODE_IO_NO_PERMISSION) "That song is no longer accessible. Pick it again." else "This song can't be read on this device (${e.errorCodeName})."))
            }
        }).build()
        transformer = t
        onState(ToneState.Working(0f))
        t.start(Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build(), out.absolutePath)
        val holder = ProgressHolder()
        poll = object : Runnable { override fun run() { if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onState(ToneState.Working(holder.progress / 100f)); handler.postDelayed(this, 200) } }.also { handler.post(it) }
    }

    fun cancel() { stopPoll(); transformer?.cancel(); transformer = null }
    private fun stopPoll() { poll?.let(handler::removeCallbacks); poll = null }

    private fun saveToRingtones(file: File, title: String): Uri {
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Ringtone" }.take(40)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe ${System.currentTimeMillis() % 100000}.m4a")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/ReelMint")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1); put(MediaStore.Audio.Media.IS_NOTIFICATION, 1); put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MediaStore insert failed")
        try {
            r.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            r.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { r.delete(uri, null, null); throw e }
        return uri
    }
}

private fun fmtClock(ms: Long): String { val s = ms / 1000; val t = (ms % 1000) / 100; return "%d:%02d.%d".format(s / 60, s % 60, t) }

@Composable
fun RingtoneScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var duration by remember { mutableLongStateOf(0L) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(0L) }
    var committed by remember { mutableStateOf(0L to 0L) }
    var fadeIn by remember { mutableFloatStateOf(0f) }
    var fadeOut by remember { mutableFloatStateOf(0f) }
    var state by remember { mutableStateOf<ToneState>(ToneState.Idle) }
    var playing by remember { mutableStateOf(false) }
    var needsPermissionFor by remember { mutableStateOf<TonePurpose?>(null) }
    val exporter = remember { RingtoneExporter(ctx) }
    val player = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(Unit) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { player.seekTo(0); player.pause() } } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release(); exporter.cancel() }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(uri, committed) {
        val u = uri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().setUri(u).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(committed.first).setEndPositionMs(committed.second).build()).build())
        player.prepare()
    }
    LaunchedEffect(Unit) { /* keep volume at full for an honest preview */ player.volume = 1f }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) scope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(ctx, picked); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } finally { r.release() }
                }.getOrNull()
            }
            if (meta == null || meta < 1000) { snack.showSnackbar("That file couldn't be read as audio."); return@launch }
            runCatching { ctx.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            name = withContext(Dispatchers.IO) { ctx.contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }
                ?.substringBeforeLast('.') ?: "Ringtone"
            duration = meta; uri = picked
            val (s, e) = RingtoneRange.initial(meta); start = s; end = e; committed = s to e; state = ToneState.Idle
        }
    }

    fun applyTone(p: TonePurpose, tone: Uri) {
        if (!Settings.System.canWrite(ctx)) { needsPermissionFor = p; return }
        runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, p.type, tone) }
            .onSuccess { scope.launch { snack.showSnackbar("Set as your ${p.label.lowercase()}") } }
            .onFailure { scope.launch { snack.showSnackbar("Couldn't change the ${p.label.lowercase()}. You can pick it in Sound settings.") } }
    }

    needsPermissionFor?.let { p ->
        AlertDialog(onDismissRequest = { needsPermissionFor = null }, title = { Text("Allow changing sounds") },
            text = { Text("Android needs your OK before an app can set your ${p.label.lowercase()}. Turn on \"Allow modifying system settings\" for ReelMint, then come back and tap the button again.") },
            confirmButton = { TextButton({ needsPermissionFor = null; ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open settings") } },
            dismissButton = { TextButton({ needsPermissionFor = null }) { Text("Not now") } })
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { StudioTopBar("Ringtone maker", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(Studio.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (uri == null) {
                EmptyState(Icons.Default.MusicNote, "Make your own ringtone", "Pick a song from your phone, choose the part you love, and set it as your ringtone, notification or alarm. Only use music you have the right to use.", "Choose a song", { picker.launch(arrayOf("audio/*")) })
            } else {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("Song length ${fmtClock(duration)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Selected part: ${fmtClock(start)} – ${fmtClock(end)}  (${"%.1f".format((end - start) / 1000f)} s)", style = MaterialTheme.typography.titleSmall)
                Text("Drag the left handle for the start and the right handle for the end.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (RingtoneRange.isLong(start, end)) Text("Longer than 40 s: many phones stop ringing before the end, so only the first part may be heard on a call.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                RangeSlider(start.toFloat()..end.toFloat(), { r ->
                    val movedStart = kotlin.math.abs(r.start - start) >= kotlin.math.abs(r.endInclusive - end)
                    val (s, e) = RingtoneRange.clamp(r.start.toLong(), r.endInclusive.toLong(), duration, movedStart); start = s; end = e
                }, valueRange = 0f..duration.toFloat(), onValueChangeFinished = { committed = start to end })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton({ if (playing) player.pause() else { player.seekTo(0); player.play() } }, shape = RoundedCornerShape(14.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Preview")
                    }
                    OutlinedButton({ picker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(14.dp)) { Text("Change song") }
                }
                LabeledSlider("Fade in", fadeIn, 0f..3f, "%.1fs".format(fadeIn)) { fadeIn = it }
                LabeledSlider("Fade out", fadeOut, 0f..3f, "%.1fs".format(fadeOut)) { fadeOut = it }
                Text("The preview plays the selected part. Fades are applied when you save.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                when (val st = state) {
                    ToneState.Idle, is ToneState.Failed -> {
                        if (st is ToneState.Failed) Text(st.message, color = MaterialTheme.colorScheme.error)
                        Button({ player.pause(); exporter.start(uri!!, start, end, (fadeIn * 1000).toLong().coerceAtMost((end - start) / 2), (fadeOut * 1000).toLong().coerceAtMost((end - start) / 2), name) { state = it } },
                            Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(8.dp)); Text("Cut & save ringtone") }
                    }
                    is ToneState.Working -> {
                        Text("Cutting…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth())
                        TextButton({ exporter.cancel(); state = ToneState.Idle }) { Text("Cancel") }
                    }
                    is ToneState.Saved -> {
                        Text("Saved to Ringtones/ReelMint", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TonePurpose.entries.forEach { p ->
                            Button({ applyTone(p, st.uri) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("Set as ${p.label.lowercase()}") }
                        }
                        OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth()) { Text("Open Sound settings (choose it yourself)") }
                        OutlinedButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, st.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ringtone")) }, Modifier.fillMaxWidth()) { Text("Share") }
                        TextButton({ state = ToneState.Idle }) { Text("Make another cut") }
                    }
                }
            }
        }
    }
}
