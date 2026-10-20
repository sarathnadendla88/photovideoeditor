package com.sarath.photovideo

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs real exports on a device and measures the output files.
 * Input media is read from the app's external files dir (push it with adb, see README "Testing").
 */
class VideoPipelineTest {
    private lateinit var ctx: Context
    private lateinit var withAudio: Uri     // clip that has video + audio
    private lateinit var tone: Uri          // wav used as music
    private var withAudioMs = 0L

    data class Out(val videoMs: Long, val audioMs: Long, val hasVideo: Boolean, val hasAudio: Boolean, val w: Int, val h: Int)

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null)!!
        val v = File(dir, "test_av.mp4"); val t = File(dir, "test_tone.wav")
        assertTrue("push test_av.mp4 and test_tone.wav to ${dir.absolutePath}", v.exists() && t.exists())
        withAudio = Uri.fromFile(v); tone = Uri.fromFile(t)
        val r = android.media.MediaMetadataRetriever().also { it.setDataSource(v.absolutePath) }
        withAudioMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); r.release()
    }

    private fun clip(uri: Uri = withAudio, ms: Long = withAudioMs) = VideoClip(uri, "t", ms, width = 720, height = 1280)

    private fun export(p: VideoProject, name: String): Out {
        val out = File(ctx.cacheDir, "pt_$name.mp4").also { it.delete() }
        val latch = CountDownLatch(1); var err: ExportException? = null
        Handler(Looper.getMainLooper()).post {
            val comp = buildVideoComposition(ctx, p)
            val t = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) { latch.countDown() }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) { err = e; latch.countDown() }
                }).build()
            t.start(comp, out.absolutePath)
        }
        assertTrue("export $name timed out", latch.await(120, TimeUnit.SECONDS))
        err?.let { fail("export $name failed: ${it.errorCodeName} ${it.message}") }
        return measure(out)
    }

    /** Length of each track = timestamp of its last sample. */
    private fun measure(f: File): Out {
        val ex = MediaExtractor().also { it.setDataSource(f.absolutePath) }
        var v = -1L; var a = -1L; var w = 0; var h = 0
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i); val mime = fmt.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            ex.selectTrack(i); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var last = 0L
            while (ex.sampleTrackIndex == i) { last = maxOf(last, ex.sampleTime); if (!ex.advance()) break }
            ex.unselectTrack(i)
            if (mime.startsWith("video")) {
                v = last / 1000; w = fmt.getInteger(android.media.MediaFormat.KEY_WIDTH); h = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                val rot = if (fmt.containsKey(android.media.MediaFormat.KEY_ROTATION)) fmt.getInteger(android.media.MediaFormat.KEY_ROTATION) else 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            }
            if (mime.startsWith("audio")) a = last / 1000
        }
        ex.release()
        return Out(v, a, v >= 0, a >= 0, w, h)
    }

    private fun near(expected: Long, actual: Long, tol: Long = 350, what: String) = assertTrue("$what: expected ~$expected ms, got $actual ms", kotlin.math.abs(expected - actual) <= tol)

    @Test fun passthroughKeepsLengthAndAudio() {
        val o = export(VideoProject(clips = listOf(clip())), "plain")
        assertTrue(o.hasVideo && o.hasAudio); near(withAudioMs, o.videoMs, what = "video"); near(withAudioMs, o.audioMs, what = "audio")
    }

    @Test fun trimShortensBothTracks() {
        val c = clip().copy(trimStartMs = 500, trimEndMs = withAudioMs - 500)
        val o = export(VideoProject(clips = listOf(c)), "trim")
        near(withAudioMs - 1000, o.videoMs, what = "video"); near(withAudioMs - 1000, o.audioMs, what = "audio")
    }

    @Test fun splitKeepsTotalLengthAndAudioMatches() {
        val o = export(VideoProject(clips = listOf(clip())).split(0, withAudioMs / 2), "split")
        near(withAudioMs, o.videoMs, what = "video"); near(withAudioMs, o.audioMs, what = "audio vs video")
    }

    @Test fun freezeFrameAddsHoldAndAudioStaysInStep() {
        val img = File(ctx.getExternalFilesDir(null), "test_frame.jpg")
        android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF3366CC.toInt()) }
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, img.outputStream())
        val frame = VideoClip(Uri.fromFile(img), "Freeze frame", 2000, width = 720, height = 1280, freeze = true)
        val p = VideoProject(clips = listOf(clip())).freezeAt(0, withAudioMs / 2, frame)
        assertEquals(3, p.clips.size)
        val o = export(p, "freeze")
        near(withAudioMs + 2000, o.videoMs, what = "video"); near(withAudioMs + 2000, o.audioMs, what = "audio vs video")
    }

    @Test fun doubleSpeedHalvesLength() {
        val o = export(VideoProject(clips = listOf(clip().copy(speed = 2f))), "speed2")
        near(withAudioMs / 2, o.videoMs, what = "video"); near(withAudioMs / 2, o.audioMs, what = "audio")
    }

    @Test fun mutingRemovesAudioTrack() {
        val o = export(VideoProject(clips = listOf(clip()), muteOriginal = true), "mute")
        assertTrue(o.hasVideo); assertFalse("muted export still has audio", o.hasAudio)
    }

    @Test fun musicOnMutedVideoAddsAudioMatchingVideoLength() {
        val m = MusicTrack(tone, "tone", durationMs = 6000)
        val o = export(VideoProject(clips = listOf(clip()), muteOriginal = true, music = m), "music")
        assertTrue(o.hasAudio); near(withAudioMs, o.videoMs, what = "video"); near(withAudioMs, o.audioMs, what = "music cut to video length")
    }

    @Test fun canvasSizeFollowsPresetAndQuality() {
        val o = export(VideoProject(clips = listOf(clip()), canvas = CanvasPreset.SQUARE, quality = ExportQuality.SD), "square")
        val (w, h) = CanvasPreset.SQUARE.size(ExportQuality.SD)
        assertEquals(w, o.w); assertEquals(h, o.h)
    }

    @Test fun captionStickerWatermarkAndTransitionDoNotBreakExport() {
        val p = VideoProject(clips = listOf(clip()), captions = listOf(Caption("hi", 0, 1500)), stickers = listOf(VideoSticker("🔥", x = 0.9f, y = -0.9f)),
            watermark = "@me", transition = Transition.DIP, filter = PhotoFilter.SEPIA, originalVoice = VoiceFx.DEEP)
        val o = export(p, "overlays")
        near(withAudioMs, o.videoMs, what = "video"); assertTrue(o.hasAudio)
    }

    @Test fun amrAudioSourceDoesNotOutlastTheVideo() {
        val f = File(ctx.getExternalFilesDir(null), "test_amr.mp4")
        org.junit.Assume.assumeTrue("push test_amr.mp4 (a clip with AMR audio) to run this", f.exists())
        val r = android.media.MediaMetadataRetriever().also { it.setDataSource(f.absolutePath) }
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); r.release()
        val o = export(VideoProject(clips = listOf(clip(Uri.fromFile(f), ms))), "amr")
        assertTrue(o.hasAudio); // 8 kHz AAC frames are ~128 ms long, so a few frames of encoder tail are unavoidable; before the limiter this was ~740 ms over.
        assertTrue("audio ${o.audioMs} ms outlasts the clip ($ms ms) by too much", o.audioMs <= ms + 450)
    }

    @Test fun realPhoneClipIsReadAsPortraitWithCanvasMatched() {
        val f = File(ctx.getExternalFilesDir(null), "test_phone.mp4")
        org.junit.Assume.assumeTrue("push test_phone.mp4 (a clip recorded by a phone camera) to run this", f.exists())
        val vm = VideoViewModel(ctx.applicationContext as android.app.Application)
        Handler(Looper.getMainLooper()).post { vm.startWith(listOf(Uri.fromFile(f))) }
        val end = System.currentTimeMillis() + 20_000
        while (vm.project.value.clips.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(100)
        val c = vm.project.value.clips.firstOrNull() ?: fail("clip was not read") as Nothing
        assertTrue("expected a portrait clip, got ${c.width}x${c.height}", c.height > c.width)
        assertEquals(CanvasPreset.PORTRAIT, vm.project.value.canvas)
    }

    @Test fun twoClipsConcatenate() {
        val o = export(VideoProject(clips = listOf(clip(), clip())), "two")
        near(withAudioMs * 2, o.videoMs, 500, "video"); near(withAudioMs * 2, o.audioMs, 500, "audio")
    }

    @Test fun ringtoneCutHasRequestedLengthAndIsRegisteredAsRingtone() {
        val latch = CountDownLatch(1); var st: ToneState = ToneState.Idle
        Handler(Looper.getMainLooper()).post {
            RingtoneExporter(ctx).start(tone, 1000, 3000, 200, 200, "Test tone") { st = it; if (it is ToneState.Saved || it is ToneState.Failed) latch.countDown() }
        }
        assertTrue("ringtone export timed out", latch.await(60, TimeUnit.SECONDS))
        val saved = st as? ToneState.Saved ?: fail("ringtone export failed: $st") as Nothing
        val t = TestUtil.measure(saved.uri)
        assertTrue("audio only", t.audioMs > 0 && t.videoMs < 0)
        near(2000, t.audioMs, 300, "ringtone length")
        ctx.contentResolver.query(saved.uri, arrayOf(android.provider.MediaStore.Audio.Media.IS_RINGTONE, android.provider.MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertTrue(it.getString(1).startsWith("Ringtones/ReelMint"))
        }
    }
}
