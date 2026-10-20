package com.sarath.photovideo

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {
    private val drafts = DraftStore(app)
    private val _project = MutableStateFlow(drafts.load() ?: SlideshowProject())
    val project: StateFlow<SlideshowProject> = _project.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private val exporter = SlideshowExporter(app)

    init {
        viewModelScope.launch(Dispatchers.IO) { _project.drop(1).debounce(400).collect { drafts.save(it) } }
    }

    fun addPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val room = SlideshowProject.MAX_PHOTOS - _project.value.photos.size
            val copies = withContext(Dispatchers.IO) { uris.take(room).mapNotNull(drafts::importPhoto) }
            _project.update { it.copy(photos = it.photos + copies) }
        }
    }

    fun removePhoto(i: Int) = _project.update { it.removePhoto(i) }
    fun movePhoto(from: Int, to: Int) = _project.update { it.movePhoto(from, to) }
    fun setSeconds(s: Float) = _project.update { it.copy(secondsPerPhoto = s) }
    fun setCanvas(c: CanvasPreset) = _project.update { it.copy(canvas = c) }
    fun setQuality(q: ExportQuality) = _project.update { it.copy(quality = q) }
    fun setMotion(m: Motion) = _project.update { it.copy(motion = m) }
    fun setTitle(t: String) = _project.update { it.copy(title = t) }
    fun setEndText(t: String) = _project.update { it.copy(endText = t) }
    fun applyTemplate(t: SlideshowTemplate) = _project.update { it.applyTemplate(t) }
    fun setSlideCaption(uri: Uri, text: String) = _project.update { p -> p.copy(slideCaptions = if (text.isBlank()) p.slideCaptions - uri.toString() else p.slideCaptions + (uri.toString() to text.take(80))) }
    /** Copies the picture into app storage first so the draft keeps working after a restart. */
    suspend fun addPicture(uri: Uri): Boolean {
        val copy = withContext(Dispatchers.IO) { drafts.importOverlay(uri) } ?: return false
        addSticker(VideoSticker(imageUri = copy.toString(), size = 0.3f, y = 0f))
        return true
    }
    fun addSticker(s: VideoSticker) = _project.update { it.copy(stickers = it.stickers + s) }
    fun updateSticker(i: Int, f: (VideoSticker) -> VideoSticker) = _project.update { p -> p.copy(stickers = p.stickers.mapIndexed { idx, s -> if (idx == i) f(s) else s }) }
    fun removeSticker(i: Int) = _project.update { p -> p.copy(stickers = p.stickers.filterIndexed { idx, _ -> idx != i }) }
    fun setTransition(t: Transition) = _project.update { it.copy(transition = t) }
    fun setWatermark(t: String) = _project.update { it.copy(watermark = t.take(24)) }
    fun setFadeIn(ms: Long) = _project.update { p -> p.copy(music = p.music?.copy(fadeInMs = ms)) }
    fun setFadeOut(ms: Long) = _project.update { p -> p.copy(music = p.music?.copy(fadeOutMs = ms)) }
    fun setMusicStart(ms: Long) = _project.update { p -> p.copy(music = p.music?.copy(startMs = ms)) }

    fun setMusic(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { audioDurationMs(uri) }
            _project.update { it.copy(music = MusicTrack(uri, displayName(uri), d)) }
        }
    }

    fun clearMusic() = _project.update { it.copy(music = null) }
    fun setMusicVolume(v: Float) = _project.update { p -> p.copy(music = p.music?.copy(volume = v)) }
    fun setMusicLoop(l: Boolean) = _project.update { p -> p.copy(music = p.music?.copy(loop = l)) }

    fun export() = exporter.start(_project.value) { _export.value = it }
    fun cancelExport() { exporter.cancel(); _export.value = ExportState.Idle }
    fun dismissExport() { _export.value = ExportState.Idle }

    override fun onCleared() = exporter.cancel()

    private fun audioDurationMs(uri: Uri): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(getApplication(), uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { r.release() }
    }.getOrDefault(0L)

    private fun displayName(uri: Uri): String =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Selected audio"
}
