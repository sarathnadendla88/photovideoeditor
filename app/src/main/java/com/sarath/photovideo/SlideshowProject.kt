package com.sarath.photovideo

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

enum class CanvasPreset(val label: String, val width: Int, val height: Int) {
    PORTRAIT("9:16", 720, 1280),
    SQUARE("1:1", 1080, 1080),
    LANDSCAPE("16:9", 1280, 720),
}

/** Output size multiplier relative to the preset's base resolution. */
enum class ExportQuality(val label: String, val scale: Float) { SD("Small", 0.67f), HD("Standard", 1f), FHD("High", 1.5f) }

fun CanvasPreset.size(q: ExportQuality): Pair<Int, Int> =
    (width * q.scale).toInt().let { it - it % 2 } to (height * q.scale).toInt().let { it - it % 2 }

enum class Motion(val label: String) { NONE("None"), KEN_BURNS("Zoom drift"), SLIDE("Slide in"), POP("Pop in") }

/** Music settings. Times are in milliseconds within the source audio file. */
data class MusicTrack(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long = 0L,
    val startMs: Long = 0L,
    val volume: Float = 1f,
    val loop: Boolean = true,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
)

data class SlideshowTemplate(
    val name: String, val tagline: String, val icon: ImageVector, val from: Color, val to: Color,
    val seconds: Float, val canvas: CanvasPreset, val motion: Motion, val title: String, val endText: String = "",
)

val SlideshowTemplates = listOf(
    SlideshowTemplate("Birthday", "Confetti-fast pops", Icons.Default.Cake, Color(0xFFFF6B8A), Color(0xFFFFB84D), 2.5f, CanvasPreset.PORTRAIT, Motion.POP, "Happy Birthday!", "Make a wish ✨"),
    SlideshowTemplate("Travel", "Slow cinematic drift", Icons.Default.Flight, Color(0xFF38BDF8), Color(0xFF2DD4BF), 3.5f, CanvasPreset.LANDSCAPE, Motion.KEN_BURNS, "Our Adventure", "Until next time"),
    SlideshowTemplate("Wedding", "Soft and timeless", Icons.Default.Favorite, Color(0xFF7C5CFF), Color(0xFFFF6B8A), 4f, CanvasPreset.SQUARE, Motion.KEN_BURNS, "Forever Begins", "With love"),
    SlideshowTemplate("Reels", "Quick vertical cuts", Icons.Default.Bolt, Color(0xFF4B3BDB), Color(0xFFFF6B8A), 1.5f, CanvasPreset.PORTRAIT, Motion.SLIDE, ""),
    SlideshowTemplate("Baby", "Little milestones", Icons.Default.ChildCare, Color(0xFFFFB84D), Color(0xFFFF6B8A), 3f, CanvasPreset.SQUARE, Motion.KEN_BURNS, "Our Little Star", "Growing every day"),
)

/** Serializable-in-spirit editing model; UI never touches the media engine directly. */
data class SlideshowProject(
    val photos: List<Uri> = emptyList(),
    val secondsPerPhoto: Float = 3f,
    val canvas: CanvasPreset = CanvasPreset.PORTRAIT,
    val quality: ExportQuality = ExportQuality.HD,
    val motion: Motion = Motion.KEN_BURNS,
    val title: String = "",
    val endText: String = "",
    val transition: Transition = Transition.NONE,
    val watermark: String = "",
    val slideCaptions: Map<String, String> = emptyMap(),   // photo uri -> caption shown on that slide
    val stickers: List<VideoSticker> = emptyList(),        // emoji / pictures shown on every slide
    val music: MusicTrack? = null,
) {
    val perPhotoMs: Long get() = (secondsPerPhoto * 1000).toLong().coerceAtLeast(MIN_PHOTO_MS)
    val totalDurationMs: Long get() = perPhotoMs * photos.size
    val canExport: Boolean get() = photos.isNotEmpty()

    fun movePhoto(from: Int, to: Int): SlideshowProject {
        if (from !in photos.indices || to !in photos.indices || from == to) return this
        val list = photos.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(photos = list)
    }

    fun removePhoto(index: Int): SlideshowProject =
        if (index in photos.indices) copy(photos = photos.toMutableList().also { it.removeAt(index) }, slideCaptions = slideCaptions - photos[index].toString()) else this

    fun applyTemplate(t: SlideshowTemplate) = copy(secondsPerPhoto = t.seconds, canvas = t.canvas, motion = t.motion, title = t.title, endText = t.endText)

    companion object {
        const val MIN_PHOTO_MS = 500L
        const val MAX_PHOTOS = 100
    }
}
