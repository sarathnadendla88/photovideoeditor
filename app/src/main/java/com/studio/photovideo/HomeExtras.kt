package com.studio.photovideo

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A photo or video this app saved to the gallery. */
data class Creation(val uri: Uri, val name: String, val isVideo: Boolean, val addedSec: Long)

/** Lists files this app created (own files need no storage permission). */
fun loadCreations(ctx: Context, limit: Int = 16): List<Creation> {
    fun query(base: Uri, isVideo: Boolean, path: String): List<Creation> = runCatching {
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            buildList { while (c.moveToNext() && size < limit) add(Creation(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: "", isVideo, c.getLong(2))) }
        }.orEmpty()
    }.onFailure { android.util.Log.e("Creations", "query failed", it) }.getOrDefault(emptyList())
    val all = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures/PhotoVideoStudio") + query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies/PhotoVideoStudio")
    android.util.Log.d("Creations", "found ${all.size}")
    return all.sortedByDescending { it.addedSec }.take(limit)
}

@Composable
fun RecentCreations() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Creation>>(emptyList()) }
    var selected by remember { mutableStateOf<Creation?>(null) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { loadCreations(ctx) } }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your creations", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.uri.toString() }) { c ->
                Box(Modifier.size(110.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { selected = c }) {
                    AsyncImage(ImageRequest.Builder(ctx).data(c.uri).decoderFactory(VideoFrameDecoder.Factory()).build(), "Open ${c.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (c.isVideo) Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(32.dp), tint = Color.White.copy(alpha = 0.9f))
                }
            }
        }
    }
    selected?.let { c ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(c.name, maxLines = 1) },
            text = { Text("Saved by Photo Video Studio on this device.") },
            confirmButton = {
                Row {
                    TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, if (c.isVideo) "video/*" else "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); selected = null }) { Text("Open") }
                    TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (c.isVideo) "video/*" else "image/*").putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")); selected = null }) { Text("Share") }
                }
            },
            dismissButton = {
                Row {
                    TextButton({ scope.launch { withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.delete(c.uri, null, null) } }; items = items.filter { it.uri != c.uri }; selected = null } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton({ selected = null }) { Text("Close") }
                }
            })
    }
}

/** Temporary files the app owns (exports in progress, camera segments, generated voices). Never touches originals or saved results. */
fun tempFiles(ctx: Context): List<File> =
    (ctx.cacheDir.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("export_") || it.name.startsWith("reel_")) } +
        ctx.filesDir.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("tts_") || it.name == "voiceover.m4a") } +
        OverlayFiles.dir(ctx).listFiles().orEmpty().toList())

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onReplayIntro: () -> Unit) {
    val ctx = LocalContext.current
    var bytes by remember { mutableLongStateOf(tempFiles(ctx).sumOf { it.length() }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings & privacy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleSmall)
                Text("Your photos, videos and audio never leave this device. There is no account, no analytics and no ads. The camera and microphone are used only while you record.", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Text("Temporary files: ${"%.1f".format(bytes / 1_048_576f)} MB (camera clips, voiceovers, unfinished exports). Your originals and saved results are not affected.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton({ tempFiles(ctx).forEach { it.delete() }; bytes = tempFiles(ctx).sumOf { it.length() } }, enabled = bytes > 0) { Icon(Icons.Default.CleaningServices, null); Text(" Clear temporary files") }
                HorizontalDivider()
                TextButton(onReplayIntro) { Text("Show the intro again") }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

private data class IntroPage(val icon: ImageVector, val title: String, val body: String, val from: Color, val to: Color)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pages = listOf(
        IntroPage(Icons.Default.AutoAwesome, "Create anything", "Edit photos, cut videos, record reels and turn pictures into slideshows — all in one place.", Studio.Indigo, Studio.Coral),
        IntroPage(Icons.Default.Lock, "Private by design", "Everything happens on your device. No account, no uploads. We ask for the camera or microphone only when you use them.", Studio.Teal, Studio.Indigo),
        IntroPage(Icons.Default.MusicNote, "Make it yours", "Add music, captions, looks and voiceovers. Your originals are never changed — edits are saved as new files.", Studio.Coral, Studio.Amber),
    )
    val state = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state, Modifier.fillMaxSize()) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(160.dp).clip(CircleShape).background(Studio.tile(p.from, p.to)), contentAlignment = Alignment.Center) { Icon(p.icon, null, Modifier.size(72.dp), tint = Color.White) }
                Spacer(Modifier.height(32.dp))
                Text(p.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onDone) { Text("Skip") }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { Box(Modifier.size(if (it == state.currentPage) 10.dp else 8.dp).clip(CircleShape).background(if (it == state.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) }
            }
            Spacer(Modifier.weight(1f))
            Button({ if (state.currentPage == pages.lastIndex) onDone() else scope.launch { state.animateScrollToPage(state.currentPage + 1) } }, shape = RoundedCornerShape(14.dp)) {
                Text(if (state.currentPage == pages.lastIndex) "Get started" else "Next")
            }
        }
    }
}
