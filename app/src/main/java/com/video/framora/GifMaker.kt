package com.video.framora

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class GifSize(val label: String, val width: Int) { SMALL("Small", 240), MEDIUM("Medium", 360), LARGE("Large", 480) }
enum class GifStyle(val label: String, val pro: Boolean = false) { NORMAL("Normal"), BOOMERANG("Boomerang"), REVERSE("Reverse", true) }

data class GifSettings(
    val startMs: Long, val endMs: Long, val fps: Int = 12, val size: GifSize = GifSize.MEDIUM,
    val style: GifStyle = GifStyle.NORMAL, val caption: String = "",
    /** Playback speed: 1 = as filmed. 0.5× and 2× are Pro. */ val speed: Float = 1f,
    /** "Made with Framora" tag on the frames; set when the GIF is made, for free users. */ val brandTag: Boolean = false,
    /** Pro: your own logo (a picture in app storage) in a corner of every frame. */ val logoUri: String? = null, val logoCorner: LogoCorner = LogoCorner.BOTTOM_RIGHT,
) {
    val lengthMs get() = (endMs - startMs).coerceAtLeast(0)
    val sourceFrames get() = max(1, (lengthMs / 1000f * fps).roundToInt())
    /** A boomerang plays forward then back, so it has about twice as many frames. */
    val outputFrames get() = if (style == GifStyle.BOOMERANG) max(1, sourceFrames * 2 - 2) else sourceFrames
    companion object { const val MAX_MS = 10_000L; const val MIN_MS = 500L; val FPS_CHOICES = listOf(8, 12, 15, Entitlements.PRO_GIF_FPS); val SPEED_CHOICES = listOf(0.5f, 1f, 2f)
        /** GIF frame delay in 1/100 s for [fps] at [speed]; browsers and apps treat anything under 2 as 10, so never go lower. */
        fun delayCs(fps: Int, speed: Float) = maxOf(2, Math.round(100f / (fps * speed))) }
}

/** Turns part of a video into an animated GIF saved to the gallery. Everything stays on the device. */
object GifMaker {
    /** Frame order for a boomerang: forward, then back without repeating the end frames (so the loop has no stutter). */
    fun <T> boomerang(frames: List<T>): List<T> = if (frames.size < 3) frames else frames + frames.subList(1, frames.size - 1).reversed()

    fun outputSize(srcW: Int, srcH: Int, width: Int): Pair<Int, Int> {
        val w = min(width, srcW).let { if (it % 2 == 1) it - 1 else it }.coerceAtLeast(2)
        return w to max(2, (w.toFloat() * srcH / srcW).roundToInt())
    }

    /** Clamp the chosen range to the clip and to the maximum GIF length. */
    fun clampRange(startMs: Long, endMs: Long, durationMs: Long, maxMs: Long = GifSettings.MAX_MS): Pair<Long, Long> {
        val s = startMs.coerceIn(0, max(0, durationMs - GifSettings.MIN_MS))
        val e = endMs.coerceIn(min(durationMs, s + GifSettings.MIN_MS), min(durationMs, s + maxMs))
        return s to e
    }

    class Result(val uri: Uri, val bytes: Long, val width: Int, val height: Int, val frames: Int)

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { it.setDataSource(ctx, uri); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
    }.getOrDefault(0L)

    /** [onProgress] gets 0..1 (extraction counts for 70%, encoding for 30%). Call off the main thread. */
    fun make(ctx: Context, video: Uri, s: GifSettings, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): Result {
        val retriever = MediaMetadataRetriever().also { it.setDataSource(ctx, video) }
        try {
            var sw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            var sh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = sw; sw = sh; sh = t }       // frames come back upright
            val (w, h) = outputSize(sw, sh, s.size.width)
            val n = s.sourceFrames
            val frames = ArrayList<IntArray>(n)
            val logo = s.logoUri?.let { PhotoProcessor.decodeToWidth(ctx, Uri.parse(it), (w * 0.2f).toInt().coerceAtLeast(12)) }
            for (i in 0 until n) {
                if (isCancelled()) throw java.util.concurrent.CancellationException()
                val t = (s.startMs + (s.lengthMs * i.toLong() / n)) * 1000
                val raw = (if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h) else null)
                    ?: retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: frames.lastOrNull()?.let { null } ?: continue
                val bmp = if (raw.width == w && raw.height == h) raw.copy(Bitmap.Config.ARGB_8888, true) else Bitmap.createScaledBitmap(raw, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
                if (s.caption.isNotBlank()) drawCaption(bmp, s.caption.trim())
                if (s.brandTag) drawTag(bmp)
                logo?.let { drawLogo(bmp, it, s.logoCorner) }
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle(); if (raw !== bmp) raw.recycle()
                frames += px
                onProgress(0.7f * (i + 1) / n)
            }
            check(frames.isNotEmpty()) { "Could not read any frames from this video" }
            val ordered = when (s.style) { GifStyle.BOOMERANG -> boomerang(frames); GifStyle.REVERSE -> frames.reversed(); GifStyle.NORMAL -> frames }
            val bytes = java.io.ByteArrayOutputStream()
            GifEncoder.encode(ordered, w, h, delayCs = GifSettings.delayCs(s.fps, s.speed), out = bytes) { onProgress(0.7f + 0.3f * it) }
            val data = bytes.toByteArray()
            return Result(save(ctx, data), data.size.toLong(), w, h, ordered.size)
        } finally { retriever.release() }
    }

    /** Classic meme caption: bold white text with a black outline, at the bottom, wrapped to the width. */
    fun drawCaption(bmp: Bitmap, text: String) {
        val c = Canvas(bmp)
        val size = bmp.width / 9f
        fun paint(stroke: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            if (stroke) { style = Paint.Style.STROKE; strokeWidth = size / 5f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND } else { style = Paint.Style.FILL; color = Color.WHITE }
        }
        val width = (bmp.width * 0.92f).toInt()
        val line = text.uppercase()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(line, 0, line.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val outline = layout(paint(true)); val fill = layout(paint(false))
        c.save(); c.translate((bmp.width - width) / 2f, bmp.height - outline.height - bmp.height * 0.04f)
        outline.draw(c); fill.draw(c); c.restore()
    }

    /** Draws the creator's logo in a corner with a small margin. */
    fun drawLogo(bmp: Bitmap, logo: Bitmap, corner: LogoCorner) {
        val m = bmp.width * 0.03f
        val x = if (corner.ax < 0) m else bmp.width - logo.width - m
        val y = if (corner.ay > 0) m else bmp.height - logo.height - m
        Canvas(bmp).drawBitmap(logo, x, y, Paint().apply { alpha = 242 })
    }

    /** The small "Made with Framora" tag, bottom-left, on free GIFs. */
    fun drawTag(bmp: Bitmap) {
        val size = max(9f, bmp.width / 20f)
        val c = Canvas(bmp)
        fun paint(stroke: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            if (stroke) { style = Paint.Style.STROKE; strokeWidth = size / 4f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND } else { style = Paint.Style.FILL; color = Color.WHITE }
        }
        val x = size * 0.5f; val y = bmp.height - size * 0.5f
        c.drawText(Entitlements.TAG_TEXT, x, y, paint(true)); c.drawText(Entitlements.TAG_TEXT, x, y, paint(false))
    }

    /** Saves to Pictures/PhotoVideoStudio like every other result, so it shows under "Your creations". */
    fun save(ctx: Context, gif: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Framora_GIF_${System.currentTimeMillis()}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoVideoStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the file in your gallery")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(gif) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return uri
    }

    /** True when the saved GIF can be opened and is a real GIF (used by tests and as a post-save sanity check). */
    fun loadable(uri: Uri, ctx: Context): Boolean = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { val h = ByteArray(6); it.read(h) == 6 && String(h, Charsets.US_ASCII) == "GIF89a" } }.getOrDefault(false)

    fun shareIntent(uri: Uri) = android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/gif").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share GIF")
}
