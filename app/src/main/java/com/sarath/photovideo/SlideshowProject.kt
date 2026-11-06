package com.sarath.photovideo

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

enum class CanvasPreset(val label: String, val width: Int, val height: Int) {
    PORTRAIT("9:16", 720, 1280),
    SQUARE("1:1", 1080, 1080),
    LANDSCAPE("16:9", 1280, 720);

    companion object {
        /** Closest preset for a clip of the given size (unknown size → vertical). */
        fun matching(width: Int, height: Int): CanvasPreset {
            if (width <= 0 || height <= 0) return PORTRAIT
            val r = width.toFloat() / height
            return when { r >= 1.2f -> LANDSCAPE; r <= 0.85f -> PORTRAIT; else -> SQUARE }
        }
    }
}

/** Output size multiplier relative to the preset's base resolution. */
enum class ExportQuality(val label: String, val scale: Float) { SD("Small", 0.67f), HD("Standard", 1f), FHD("High", 1.5f) }

fun CanvasPreset.size(q: ExportQuality): Pair<Int, Int> =
    (width * q.scale).toInt().let { it - it % 2 } to (height * q.scale).toInt().let { it - it % 2 }

enum class Motion(val label: String, val pro: Boolean = false) {
    NONE("None"), KEN_BURNS("Zoom drift"), SLIDE("Slide in"), POP("Pop in"),
    ZOOM_OUT("Zoom out", true), PAN("Pan across", true), DROP("Drop in", true), PULSE("Beat pulse", true),
}

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
    /** Included with ReelMint Pro. */ val pro: Boolean = false, val transition: Transition = Transition.NONE,
)

val SlideshowTemplates = listOf(
    SlideshowTemplate("Birthday", "Confetti-fast pops", Icons.Default.Cake, Color(0xFFFF6B8A), Color(0xFFFFB84D), 2.5f, CanvasPreset.PORTRAIT, Motion.POP, "Happy Birthday!", "Make a wish ✨"),
    SlideshowTemplate("Travel", "Slow cinematic drift", Icons.Default.Flight, Color(0xFF38BDF8), Color(0xFF2DD4BF), 3.5f, CanvasPreset.LANDSCAPE, Motion.KEN_BURNS, "Our Adventure", "Until next time"),
    SlideshowTemplate("Wedding", "Soft and timeless", Icons.Default.Favorite, Color(0xFF7C5CFF), Color(0xFFFF6B8A), 4f, CanvasPreset.SQUARE, Motion.KEN_BURNS, "Forever Begins", "With love"),
    SlideshowTemplate("Reels", "Quick vertical cuts", Icons.Default.Bolt, Color(0xFF4B3BDB), Color(0xFFFF6B8A), 1.5f, CanvasPreset.PORTRAIT, Motion.SLIDE, ""),
    SlideshowTemplate("Baby", "Little milestones", Icons.Default.ChildCare, Color(0xFFFFB84D), Color(0xFFFF6B8A), 3f, CanvasPreset.SQUARE, Motion.KEN_BURNS, "Our Little Star", "Growing every day"),
    // ---- ReelMint Pro templates: each has its own pace, canvas, motion and transition
    SlideshowTemplate("Cinematic", "Wide, slow and dramatic", Icons.Default.Theaters, Color(0xFF0F2027), Color(0xFF2C5364), 5f, CanvasPreset.LANDSCAPE, Motion.KEN_BURNS, "A Story", "The End", pro = true, transition = Transition.DIP),
    SlideshowTemplate("Vlog", "Snappy daily highlights", Icons.Default.Videocam, Color(0xFFFF512F), Color(0xFFDD2476), 2f, CanvasPreset.PORTRAIT, Motion.SLIDE, "My Day", "", pro = true),
    SlideshowTemplate("Retro", "Square, warm and playful", Icons.Default.PhotoCamera, Color(0xFFF7971E), Color(0xFFFFD200), 2.5f, CanvasPreset.SQUARE, Motion.POP, "Throwback", "Good times", pro = true),
    SlideshowTemplate("Neon night", "Fast, bold and bright", Icons.Default.AutoAwesome, Color(0xFF7F00FF), Color(0xFF00C6FF), 1.8f, CanvasPreset.PORTRAIT, Motion.POP, "Tonight", "", pro = true),
    SlideshowTemplate("Anniversary", "Slow, warm, together", Icons.Default.Favorite, Color(0xFFB24592), Color(0xFFF15F79), 4.5f, CanvasPreset.SQUARE, Motion.ZOOM_OUT, "Together", "Always", pro = true, transition = Transition.EDGES),
    SlideshowTemplate("Graduation", "Proud and bright", Icons.Default.School, Color(0xFF1D976C), Color(0xFF93F9B9), 3f, CanvasPreset.LANDSCAPE, Motion.SLIDE, "We Did It!", "On to what's next", pro = true),
    SlideshowTemplate("Festival", "Colour and energy", Icons.Default.Celebration, Color(0xFFFF416C), Color(0xFFFF4B2B), 1.6f, CanvasPreset.PORTRAIT, Motion.DROP, "Celebrate", "", pro = true),
    SlideshowTemplate("Fitness", "Quick and punchy", Icons.Default.FitnessCenter, Color(0xFF232526), Color(0xFFFF512F), 1.2f, CanvasPreset.PORTRAIT, Motion.PULSE, "No Excuses", "", pro = true),
    SlideshowTemplate("Food diary", "Close, bright, tasty", Icons.Default.Restaurant, Color(0xFFF2994A), Color(0xFFF2C94C), 2.4f, CanvasPreset.SQUARE, Motion.ZOOM_OUT, "Yum", "Bon appétit", pro = true),
    SlideshowTemplate("Pet love", "Soft and playful", Icons.Default.Pets, Color(0xFF56CCF2), Color(0xFF2F80ED), 2.2f, CanvasPreset.SQUARE, Motion.POP, "My Best Friend", "Forever", pro = true),
    SlideshowTemplate("Road trip", "Wide open miles", Icons.Default.DirectionsCar, Color(0xFF396AFC), Color(0xFF2948FF), 3f, CanvasPreset.LANDSCAPE, Motion.PAN, "On the Road", "Until next time", pro = true),
    SlideshowTemplate("Memories", "Gentle, fading, nostalgic", Icons.Default.PhotoLibrary, Color(0xFF8E9EAB), Color(0xFFEEF2F3), 3.8f, CanvasPreset.PORTRAIT, Motion.ZOOM_OUT, "Remember When", "", pro = true, transition = Transition.DIP),
    SlideshowTemplate("Party night", "Beat-driven and loud", Icons.Default.Nightlife, Color(0xFFDA22FF), Color(0xFF9733EE), 1.5f, CanvasPreset.PORTRAIT, Motion.PULSE, "Tonight's The Night", "", pro = true),
    SlideshowTemplate("Showcase", "Clean product reveal", Icons.Default.ShoppingBag, Color(0xFF304352), Color(0xFFD7D2CC), 2.6f, CanvasPreset.SQUARE, Motion.PAN, "New Arrival", "Available now", pro = true, transition = Transition.EDGES),
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
    val beatSync: Boolean = false,          // cut photos on the beat of the music
    val beatsPerPhoto: Int = 2,
    val beats: List<Long> = emptyList(),    // beat times in ms from the music start, found by BeatAnalyzer
    val brandTag: Boolean = false,          // "Made with ReelMint" tag; set at export time for free users
    val logoUri: String? = null, val logoCorner: LogoCorner = LogoCorner.BOTTOM_RIGHT,   // Pro: your own logo
) {
    val perPhotoMs: Long get() = (secondsPerPhoto * 1000).toLong().coerceAtLeast(MIN_PHOTO_MS)
    /** Length of every photo: on the beat grid when beat sync is on and a beat was found, otherwise the same for all. */
    fun photoDurationsMs(): List<Long> =
        if (beatSync && music != null && beats.size >= 4) BeatMath.snapDurations(beats, photos.size, beatsPerPhoto, MIN_PHOTO_MS.coerceAtLeast(600L))
        else List(photos.size) { perPhotoMs }
    val totalDurationMs: Long get() = photoDurationsMs().sum()
    val canExport: Boolean get() = photos.isNotEmpty()

    fun movePhoto(from: Int, to: Int): SlideshowProject {
        if (from !in photos.indices || to !in photos.indices || from == to) return this
        val list = photos.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(photos = list)
    }

    fun removePhoto(index: Int): SlideshowProject =
        if (index in photos.indices) copy(photos = photos.toMutableList().also { it.removeAt(index) }, slideCaptions = slideCaptions - photos[index].toString()) else this

    fun applyTemplate(t: SlideshowTemplate) = copy(secondsPerPhoto = t.seconds, canvas = t.canvas, motion = t.motion, title = t.title, endText = t.endText, transition = t.transition)

    companion object {
        const val MIN_PHOTO_MS = 500L
        const val MAX_PHOTOS = 100
    }
}
