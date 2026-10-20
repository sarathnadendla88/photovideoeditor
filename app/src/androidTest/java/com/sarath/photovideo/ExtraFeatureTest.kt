package com.sarath.photovideo

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Slideshow styles and saved photo looks, exercised for real. */
class ExtraFeatureTest {
    private val ctx get() = TestUtil.ctx

    private fun exportSlideshow(p: SlideshowProject): ExportState {
        val latch = CountDownLatch(1); var last: ExportState = ExportState.Idle
        Handler(Looper.getMainLooper()).post {
            SlideshowExporter(ctx).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() }
        }
        assertTrue("slideshow export timed out", latch.await(150, TimeUnit.SECONDS))
        return last
    }

    private fun base() = SlideshowProject(photos = TestUtil.photos, secondsPerPhoto = 1.5f, canvas = CanvasPreset.SQUARE, quality = ExportQuality.SD)

    @Test fun everyMotionExportsWithTheRightLength() {
        for (m in Motion.entries) {
            val st = exportSlideshow(base().copy(motion = m))
            val done = st as? ExportState.Done ?: fail("motion $m failed: $st") as Nothing
            val t = TestUtil.measure(done.uri)
            assertTrue("$m: expected ~4.5 s, got ${t.videoMs}", kotlin.math.abs(t.videoMs - 4500) < 600)
        }
    }

    @Test fun everyTransitionExports() {
        for (tr in Transition.entries) {
            val st = exportSlideshow(base().copy(transition = tr))
            assertTrue("transition $tr failed: $st", st is ExportState.Done)
        }
    }

    @Test fun titlesCaptionsStickersWatermarkAndMusicTogether() {
        val p = base().copy(
            motion = Motion.KEN_BURNS, transition = Transition.DIP, title = "Hello", endText = "Bye", watermark = "@reelmint",
            slideCaptions = mapOf(TestUtil.photos[0].toString() to "First"),
            stickers = listOf(VideoSticker("🔥", x = 0.9f, y = -0.9f)),
            music = MusicTrack(TestUtil.tone, "tone", durationMs = 6000, volume = 0.8f, loop = true, fadeInMs = 500, fadeOutMs = 800),
        )
        val st = exportSlideshow(p) as? ExportState.Done ?: fail("combined export failed") as Nothing
        val t = TestUtil.measure(st.uri)
        assertTrue("has audio", t.audioMs > 0)
        assertTrue("music ends with the video: audio ${t.audioMs} video ${t.videoMs}", kotlin.math.abs(t.audioMs - t.videoMs) < 600)
    }

    @Test fun savedLooksRoundTripAndDelete() {
        val store = LookStore(ctx)
        store.all().forEach { store.delete(it.name) }
        val look = PhotoEdit(filter = PhotoFilter.SEPIA, brightness = 0.1f, contrast = 1.2f, saturation = 1.3f, warmth = 0.2f, vignette = 0.4f, sharpen = 0.3f, fade = 0.1f).toLook("Test look")
        store.save(look)
        assertEquals(listOf(look), LookStore(ctx).all())          // survives a new instance (persisted)
        store.save(look.copy(brightness = 0.3f))                   // same name replaces
        assertEquals(1, store.all().size); assertEquals(0.3f, store.all()[0].brightness, 0f)
        val applied = PhotoEdit(texts = listOf(TextLayer("keep me"))).withLook(look)
        assertEquals(PhotoFilter.SEPIA, applied.filter); assertEquals("keep me", applied.texts[0].text)  // look keeps text layers
        store.delete("Test look"); assertTrue(store.all().isEmpty())
    }

    @Test fun everyPhotoFilterAndCropRendersWithoutError() {
        val src = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF88AA66.toInt()) }
        for (f in PhotoFilter.entries) PhotoProcessor.apply(src, null, PhotoEdit(filter = f, sharpen = 0.5f, vignette = 0.3f, fade = 0.2f)).recycle()
        for (c in CropRatio.entries) {
            val out = PhotoProcessor.apply(src, null, PhotoEdit(cropRect = CropRect.forRatio(c.ratio, 1.5f)))
            if (c.ratio != null) assertEquals(c.ratio, out.width.toFloat() / out.height, 0.03f)
        }
        val framed = PhotoProcessor.apply(src, null, PhotoEdit(border = 0.05f))
        assertTrue(framed.width > 300 && framed.height > 200)
    }
}
