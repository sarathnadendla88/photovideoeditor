package com.sarath.photovideo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Wraps Media3 Transformer so the rest of the app does not depend on it.
 * Must be used from the main thread (Transformer requirement).
 */
class SlideshowExporter(private val context: Context) {
    companion object {
        fun volumeProcessor(v: Float) = ChannelMixingAudioProcessor().apply {
            for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(v))
        }
    }

    private var transformer: Transformer? = null
    private var poller: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tempFile: File? = null

    fun start(project: SlideshowProject, onState: (ExportState) -> Unit) {
        if (!project.canExport) {
            onState(ExportState.Failed("Add at least one photo."))
            return
        }
        start(buildComposition(project), onState)
    }

    fun start(composition: Composition, onState: (ExportState) -> Unit) {
        cancel()
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4").also { tempFile = it }

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    stopPolling()
                    val saved = runCatching { saveToGallery(out) }
                    out.delete()
                    onState(
                        saved.fold(
                            { ExportState.Done(it) },
                            { ExportState.Failed("Could not save the video: ${it.message}") },
                        )
                    )
                }

                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    android.util.Log.e("SlideshowExporter", "Export failed: ${e.errorCodeName}", e)
                    stopPolling()
                    out.delete()
                    onState(ExportState.Failed(describe(e)))
                }
            })
            .build()
        transformer = t
        onState(ExportState.Running(0f))
        t.start(composition, out.absolutePath)

        val holder = ProgressHolder()
        poller = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onState(ExportState.Running(holder.progress / 100f))
                }
                handler.postDelayed(this, 250)
            }
        }.also { handler.post(it) }
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
        tempFile?.delete()
    }

    private fun stopPolling() {
        poller?.let(handler::removeCallbacks)
        poller = null
    }

    private fun buildComposition(p: SlideshowProject): Composition {
        val (cw, ch) = p.canvas.size(p.quality)
        val fill = Presentation.createForWidthAndHeight(cw, ch, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val perUs = p.perPhotoMs * 1000f
        val last = p.photos.lastIndex
        val photoItems = p.photos.mapIndexed { i, uri ->
            val video = mutableListOf<Effect>(fill)
            motionEffect(p.motion, i, perUs)?.let { video += it }
            overlayFor(if (i == 0) p.title else "", if (i == last) p.endText else "", cw)?.let { video += it }
            val extra = mutableListOf<androidx.media3.effect.TextureOverlay>()
            if (p.watermark.isNotBlank()) extra += watermarkOverlay(p.watermark.trim(), cw)
            p.slideCaptions[uri.toString()]?.takeIf { it.isNotBlank() }?.let { extra += staticText(it, -0.4f, cw) }
            p.stickers.forEach { st ->
                val px = (cw * st.size).toInt().coerceAtLeast(32)
                val bmp = if (st.imageUri != null) PhotoProcessor.decodeToWidth(context, android.net.Uri.parse(st.imageUri), px) else emojiBitmap(st.emoji, px)
                if (bmp != null) extra += TimedBitmapOverlay(bmp, 0L, st, cw, ch)
            }
            fadeFor(p.transition, i, p.photos.size, cw, ch, p.perPhotoMs)?.let { extra += it }
            if (extra.isNotEmpty()) video += OverlayEffect(extra)
            EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(p.perPhotoMs).build())
                .setFrameRate(30)
                .setDurationUs(p.perPhotoMs * 1000)
                .setEffects(Effects(emptyList(), video))
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(photoItems).build())

        p.music?.let { m -> sequences += musicSequence(m, p.totalDurationMs) }
        return Composition.Builder(sequences).build()
    }

    /** Time-varying transform within one photo; presentationTimeUs is relative to the item. */
    private fun motionEffect(m: Motion, index: Int, perUs: Float): Effect? = when (m) {
        Motion.NONE -> null
        Motion.KEN_BURNS -> MatrixTransformation { t ->
            val f = (t / perUs).coerceIn(0f, 1f)
            val s = if (index % 2 == 0) 1f + 0.18f * f else 1.18f - 0.18f * f
            Matrix().apply { postScale(s, s) }
        }
        Motion.SLIDE -> MatrixTransformation { t ->
            val f = (t / 400_000f).coerceIn(0f, 1f); val e = 1f - (1f - f) * (1f - f)
            Matrix().apply { postTranslate(2f * (1f - e), 0f) }
        }
        Motion.POP -> MatrixTransformation { t ->
            val f = (t / 400_000f).coerceIn(0f, 1f); val e = 1f - (1f - f) * (1f - f)
            val s = 0.85f + 0.15f * e
            Matrix().apply { postScale(s, s) }
        }
    }

    private fun staticText(text: String, y: Float, canvasWidth: Int): androidx.media3.effect.TextureOverlay {
        val sp = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
            setSpan(AbsoluteSizeSpan(canvasWidth / 14), 0, length, 0)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
        }
        return TextOverlay.createStaticTextOverlay(sp, StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build())
    }

    private fun overlayFor(top: String, bottom: String, canvasWidth: Int): Effect? {
        val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        fun text(t: String, y: Float) {
            if (t.isBlank()) return
            val sp = SpannableString(t).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
                setSpan(AbsoluteSizeSpan(canvasWidth / 11), 0, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
            }
            overlays += TextOverlay.createStaticTextOverlay(sp, StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build())
        }
        text(top, 0.6f); text(bottom, -0.6f)
        return if (overlays.isEmpty()) null else OverlayEffect(overlays)
    }

    private fun saveToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Slideshow_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PhotoVideoStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> "A selected file is no longer accessible. Re-select it and try again."
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "One of the selected files uses a format this device can't read."
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "This device can't encode the chosen video settings. Try a smaller canvas."
        else -> "Export failed (${e.errorCodeName})."
    }
}
