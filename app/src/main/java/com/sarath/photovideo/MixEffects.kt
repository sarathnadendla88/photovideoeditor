package com.sarath.photovideo

import android.graphics.*
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.nio.ByteBuffer

/** How the picture moves between clips / photos. Media3 has no cross-dissolve, so these are fades through black. */
enum class Transition(val label: String) { NONE("None"), EDGES("Fade in & out"), DIP("Dip to black") }

/** Black full-frame overlay whose opacity ramps to fade a clip in from / out to black. */
class FadeOverlay(private val w: Int, private val h: Int, private val clipUs: Long, private val inUs: Long, private val outUs: Long) : BitmapOverlay() {
    private val black = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
    override fun getBitmap(presentationTimeUs: Long): Bitmap = black
    override fun getTextureSize(presentationTimeUs: Long) = Size(w, h)
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val a = maxOf(
            if (inUs > 0) 1f - presentationTimeUs / inUs.toFloat() else 0f,
            if (outUs > 0) (presentationTimeUs - (clipUs - outUs)) / outUs.toFloat() else 0f,
        ).coerceIn(0f, 1f)
        return StaticOverlaySettings.Builder().setAlphaScale(a).build()
    }
}

/** Fade overlay for item [index] of [count], or null when no fade applies to it. */
fun fadeFor(t: Transition, index: Int, count: Int, w: Int, h: Int, clipMs: Long): FadeOverlay? {
    val fade = minOf(400L, clipMs / 3) * 1000
    val fadeIn = when (t) { Transition.NONE -> false; Transition.EDGES -> index == 0; Transition.DIP -> true }
    val fadeOut = when (t) { Transition.NONE -> false; Transition.EDGES -> index == count - 1; Transition.DIP -> true }
    if (!fadeIn && !fadeOut) return null
    return FadeOverlay(w, h, clipMs * 1000, if (fadeIn) fade else 0, if (fadeOut) fade else 0)
}

/** Small semi-transparent text tag in the bottom-right corner (brand / credit). */
fun watermarkOverlay(text: String, canvasWidth: Int): BitmapOverlay {
    val size = canvasWidth / 28f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setShadowLayer(size / 6, 0f, 0f, Color.BLACK) }
    val w = (p.measureText(text) + size).toInt().coerceAtLeast(8); val h = (size * 1.6f).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawText(text, size / 2, size * 1.15f, p)
    return BitmapOverlay.createStaticBitmapOverlay(bmp, StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0.94f, -0.94f).setOverlayFrameAnchor(1f, -1f).setAlphaScale(0.8f).build())
}

/** Linear gain ramp over the start and end of one audio item (16-bit PCM). */
class FadeAudioProcessor(private val fadeInMs: Long, private val fadeOutMs: Long, private val itemMs: Long) : BaseAudioProcessor() {
    private var frame = 0L
    private var inFrames = 0L; private var outFrames = 0L; private var totalFrames = 0L
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        inFrames = fadeInMs * f.sampleRate / 1000; outFrames = fadeOutMs * f.sampleRate / 1000; totalFrames = itemMs * f.sampleRate / 1000
        return f
    }
    override fun onReset() { frame = 0 }
    override fun onFlush() { frame = 0 }
    override fun queueInput(input: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        val out = replaceOutputBuffer(input.remaining())
        while (input.remaining() >= 2 * ch) {
            var g = 1f
            if (inFrames > 0 && frame < inFrames) g = frame / inFrames.toFloat()
            if (outFrames > 0 && totalFrames > 0 && frame > totalFrames - outFrames) g = minOf(g, ((totalFrames - frame) / outFrames.toFloat()).coerceIn(0f, 1f))
            repeat(ch) { out.putShort((input.short * g).toInt().coerceIn(-32768, 32767).toShort()) }
            frame++
        }
        out.flip()
    }
}

/**
 * Music as explicit repeated items so the fade-out lands exactly on the end of the video (a looping sequence would
 * restart its effects on every loop). Falls back to a plain looping item when the track length is unknown.
 */
fun musicSequence(m: MusicTrack, totalMs: Long): EditedMediaItemSequence {
    fun item(len: Long?, first: Boolean, last: Boolean): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(m.startMs).apply { if (len != null) setEndPositionMs(m.startMs + len) }.build()
        val fx = buildList<AudioProcessor> {
            add(SlideshowExporter.volumeProcessor(m.volume))
            if (len != null && ((first && m.fadeInMs > 0) || (last && m.fadeOutMs > 0))) add(FadeAudioProcessor(if (first) m.fadeInMs else 0, if (last) m.fadeOutMs else 0, len))
        }
        return EditedMediaItem.Builder(MediaItem.Builder().setUri(m.uri).setClippingConfiguration(clip).build()).setRemoveVideo(true).setEffects(Effects(fx, emptyList())).build()
    }
    val usable = m.durationMs - m.startMs
    if (m.durationMs <= 0 || usable < 500) return EditedMediaItemSequence.Builder(item(null, true, true)).setIsLooping(m.loop).build()
    val items = mutableListOf<EditedMediaItem>()
    var remaining = totalMs
    while (remaining > 0) {
        val len = minOf(usable, remaining)
        remaining -= len
        items += item(len, first = items.isEmpty(), last = remaining <= 0 || !m.loop)
        if (!m.loop) break
    }
    return EditedMediaItemSequence.Builder(items).build()
}


/**
 * Passes audio through but drops everything after [limitMs]. Used so a clip's audio can never outlast its picture:
 * some sources (for example AMR-NB from some cameras) decode to a little more audio than video, which would otherwise
 * leave sound playing after the last frame. Shorter audio is left alone (never padded).
 */
class LimitAudioProcessor(private val limitMs: Long) : BaseAudioProcessor() {
    private var framesLeft = 0L
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        framesLeft = limitMs * f.sampleRate / 1000
        return f
    }
    override fun onFlush() { framesLeft = limitMs * inputAudioFormat.sampleRate / 1000 }
    override fun queueInput(input: ByteBuffer) {
        val frameBytes = 2 * inputAudioFormat.channelCount
        val frames = input.remaining() / frameBytes
        val keep = minOf(frames.toLong(), framesLeft).toInt()
        if (keep > 0) {
            val out = replaceOutputBuffer(keep * frameBytes)
            val oldLimit = input.limit(); input.limit(input.position() + keep * frameBytes)
            out.put(input); input.limit(oldLimit); out.flip()
        }
        framesLeft -= keep
        input.position(input.limit())   // drop whatever is beyond the limit
    }
}
