package com.sarath.photovideo

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Anchor for an overlay of [itemPx] inside a frame of [framePx], matching Compose's BiasAlignment used by the preview:
 * bias ±1 puts the element's *edge* flush with the frame edge (Media3 would put its centre there and cut it in half).
 */
fun fitAnchor(bias: Float, itemPx: Int, framePx: Int): Float = bias * (1f - itemPx.toFloat() / framePx).coerceAtLeast(0f)

/** A burned-in caption shown between [startMs] and [endMs] on the exported timeline. */
data class Caption(val text: String, val startMs: Long, val endMs: Long, val x: Float = 0f, val y: Float = -0.7f)

enum class CaptionStyle(val label: String) { CLASSIC("Classic"), BOXED("Boxed"), HIGHLIGHT("Highlight"), NEON("Neon"), BOLD("Bold") }
enum class CaptionAnim(val label: String) { NONE("None"), FADE("Fade"), POP("Pop"), SLIDE("Slide up") }

/**
 * Emoji or user picture ([imageUri]) on the video between [startMs] and [endMs] of the exported timeline.
 * x,y are anchor offsets in -1..1 (0,0 = centre, +y up); size is a fraction of the canvas width.
 */
data class VideoSticker(
    val emoji: String = "", val x: Float = 0f, val y: Float = 0.4f, val size: Float = 0.2f,
    val startMs: Long = 0L, val endMs: Long = Long.MAX_VALUE, val imageUri: String? = null, val rotation: Float = 0f,
)

enum class SpeedRamp(val label: String, val segments: List<Pair<Float, Float>>) {
    NONE("Off", emptyList()),
    HERO("Hero time", listOf(0.35f to 1f, 0.65f to 0.3f, 1f to 1f)),
    PULSE("Pulse", listOf(0.3f to 1f, 0.5f to 2.5f, 0.7f to 1f, 1f to 2.5f)),
    MONTAGE("Montage", listOf(0.25f to 2f, 0.5f to 0.5f, 0.75f to 2f, 1f to 0.5f)),
}

enum class VoiceFx(val label: String, val pitch: Float) { NORMAL("Normal", 1f), HIGH("High", 1.35f), DEEP("Deep", 0.75f), CHIPMUNK("Chipmunk", 1.7f), GIANT("Giant", 0.6f) }

data class LookPreset(val name: String, val filter: PhotoFilter, val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f)
val LookPresets = listOf(
    LookPreset("Clean", PhotoFilter.NONE, 0.04f, 0.1f, 0.15f),
    LookPreset("Cinematic", PhotoFilter.SUNSET, -0.03f, 0.25f, -0.1f),
    LookPreset("Vlog", PhotoFilter.VIVID, 0.05f, 0.08f, 0.1f),
    LookPreset("Moody", PhotoFilter.DRAMATIC, -0.05f, 0.15f, -0.2f),
    LookPreset("Retro", PhotoFilter.RETRO, 0f, 0.05f, 0f),
    LookPreset("Noir", PhotoFilter.NOIR, 0f, 0.1f, 0f),
    LookPreset("Fresh", PhotoFilter.MINT, 0.05f, 0.05f, 0.15f),
)

enum class Platform(val label: String, val canvas: CanvasPreset, val quality: ExportQuality) {
    REELS("Instagram Reels", CanvasPreset.PORTRAIT, ExportQuality.FHD),
    TIKTOK("TikTok", CanvasPreset.PORTRAIT, ExportQuality.FHD),
    SHORTS("YouTube Shorts", CanvasPreset.PORTRAIT, ExportQuality.FHD),
    STATUS("WhatsApp Status", CanvasPreset.PORTRAIT, ExportQuality.HD),
    POST("Instagram post", CanvasPreset.SQUARE, ExportQuality.FHD),
    YOUTUBE("YouTube", CanvasPreset.LANDSCAPE, ExportQuality.FHD),
}

/** Piecewise speed over a clip: [segments] are (end fraction of the clip, speed), multiplied by [base]. */
class RampSpeedProvider(private val clipUs: Long, private val ramp: SpeedRamp, private val base: Float) : SpeedProvider {
    private val bounds = ramp.segments.map { (it.first * clipUs).toLong() }
    override fun getSpeed(timeUs: Long): Float {
        if (ramp.segments.isEmpty()) return base
        val i = bounds.indexOfFirst { timeUs < it }.let { if (it < 0) ramp.segments.lastIndex else it }
        return base * ramp.segments[i].second
    }
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = bounds.firstOrNull { it > timeUs && it < clipUs } ?: C.TIME_UNSET
}

/** Output duration of a clip with a ramp, in ms: integral of 1/speed over the source length. */
fun rampedOutputMs(lengthMs: Long, ramp: SpeedRamp, base: Float): Long {
    if (ramp.segments.isEmpty()) return (lengthMs / base).toLong()
    var prev = 0f; var total = 0.0
    for ((end, sp) in ramp.segments) { total += (end - prev) * lengthMs / (base * sp); prev = end }
    return total.toLong()
}

/** Renders timed captions for one clip. [offsetMs] is where the clip starts on the exported timeline. */
class CaptionOverlay(
    private val captions: List<Caption>, private val offsetMs: Long, private val canvasWidth: Int, private val canvasHeight: Int,
    private val capStyle: CaptionStyle, private val anim: CaptionAnim, private val anchorY: Float, private val sizeScale: Float,
) : BitmapOverlay() {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val cache = HashMap<Caption, Bitmap>()
    private fun active(timeUs: Long): Caption? { val g = offsetMs + timeUs / 1000; return captions.firstOrNull { g >= it.startMs && g < it.endMs } }
    override fun getBitmap(presentationTimeUs: Long): Bitmap = active(presentationTimeUs)?.let { c -> cache.getOrPut(c) { render(c.text) } } ?: empty

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val c = active(presentationTimeUs)
        val bmp = getBitmap(presentationTimeUs)
        val ax = fitAnchor(c?.x ?: 0f, bmp.width, canvasWidth); val ay = fitAnchor(c?.y ?: anchorY, bmp.height, canvasHeight)
        val p = if (c == null) 1f else (((offsetMs + presentationTimeUs / 1000) - c.startMs) / 300f).coerceIn(0f, 1f)
        val e = 1f - (1f - p) * (1f - p)
        val b = StaticOverlaySettings.Builder()
        when (anim) {
            CaptionAnim.NONE -> b.setBackgroundFrameAnchor(ax, ay)
            CaptionAnim.FADE -> b.setBackgroundFrameAnchor(ax, ay).setAlphaScale(e)
            CaptionAnim.POP -> b.setBackgroundFrameAnchor(ax, ay).setScale(0.6f + 0.4f * e, 0.6f + 0.4f * e).setAlphaScale(e)
            CaptionAnim.SLIDE -> b.setBackgroundFrameAnchor(ax, ay - 0.15f * (1f - e)).setAlphaScale(e)
        }
        return b.build()
    }

    private fun render(text: String): Bitmap {
        val maxW = (canvasWidth * 0.88f).toInt()
        val size = canvasWidth / 13f * sizeScale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
            color = when (capStyle) { CaptionStyle.HIGHLIGHT -> Color.BLACK; CaptionStyle.NEON -> 0xFF2DD4BF.toInt(); CaptionStyle.BOLD -> 0xFFFFD43B.toInt(); else -> Color.WHITE }
            if (capStyle == CaptionStyle.CLASSIC || capStyle == CaptionStyle.BOLD) setShadowLayer(size / 7, 0f, 0f, Color.BLACK)
            if (capStyle == CaptionStyle.NEON) setShadowLayer(size / 3, 0f, 0f, 0xFF2DD4BF.toInt())
        }
        // Wrap to the text itself (up to maxW) so boxes and highlights hug the words like the preview does.
        val textW = Math.ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt().coerceIn(1, maxW)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textW).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val pad = (size * 0.45f).toInt()
        val bmp = Bitmap.createBitmap(layout.width + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        when (capStyle) {
            CaptionStyle.BOXED -> cv.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), size / 2, size / 2, Paint().apply { color = 0xCC000000.toInt() })
            CaptionStyle.HIGHLIGHT -> cv.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), size / 3, size / 3, Paint().apply { color = 0xFFFFD43B.toInt() })
            else -> Unit
        }
        cv.translate(pad.toFloat(), pad.toFloat()); layout.draw(cv)
        return bmp
    }
    override fun getTextureSize(presentationTimeUs: Long): Size = getBitmap(presentationTimeUs).let { Size(it.width, it.height) }
}

fun emojiBitmap(emoji: String, px: Int): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.85f; textAlign = Paint.Align.CENTER }
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawText(emoji, px / 2f, px * 0.8f, p)
    return bmp
}


/** Sticker / picture that is only visible inside its time window (opacity 0 outside it). */
class TimedBitmapOverlay(private val bmp: Bitmap, private val offsetMs: Long, private val st: VideoSticker, private val frameW: Int, private val frameH: Int) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val g = offsetMs + presentationTimeUs / 1000
        val on = g >= st.startMs && g < st.endMs
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(fitAnchor(st.x, bmp.width, frameW), fitAnchor(st.y, bmp.height, frameH))
            .setRotationDegrees(-st.rotation) // Media3 rotates counter-clockwise, the preview clockwise
            .setAlphaScale(if (on) 1f else 0f).build()
    }
}
