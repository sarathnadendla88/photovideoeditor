package com.video.framora

import android.content.Intent
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
            motion = Motion.KEN_BURNS, transition = Transition.DIP, title = "Hello", endText = "Bye", watermark = "@framora",
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

/** Each creative photo effect is checked for a property that proves it really did its job. */
class PhotoEffectsTest {
    private fun scene(): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(240, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); c.drawColor(0xFF3366CC.toInt())
        c.drawCircle(120f, 80f, 50f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() })
        c.drawRect(10f, 10f, 70f, 50f, android.graphics.Paint().apply { color = 0xFF111111.toInt() })
        return b
    }
    private fun px(b: android.graphics.Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test fun everyEffectKeepsTheSizeAndNoneIsIdentity() {
        val s = scene()
        assertSame(s, PhotoEffects.apply(s, PhotoEffect.NONE, 0.5f))
        for (e in PhotoEffect.entries) { val o = PhotoEffects.apply(s, e, 0.5f); assertEquals("$e width", 240, o.width); assertEquals("$e height", 160, o.height) }
    }

    @Test fun pixelateCreatesFlatBlocks() {
        val o = px(PhotoEffects.apply(scene(), PhotoEffect.PIXELATE, 0.8f))
        var equalNeighbours = 0; for (x in 0 until 239) if (o[80 * 240 + x] == o[80 * 240 + x + 1]) equalNeighbours++
        assertTrue("blocks expected, equal neighbours = $equalNeighbours", equalNeighbours > 150)
    }

    @Test fun mirrorIsSymmetric() {
        val b = PhotoEffects.apply(scene(), PhotoEffect.MIRROR, 0.5f); val o = px(b)
        for (y in 0 until 160 step 7) for (x in 0 until 120) assertEquals(o[y * 240 + x], o[y * 240 + (239 - x)])
    }

    @Test fun sketchIsGreyscaleAndMostlyPaper() {
        val o = px(PhotoEffects.apply(scene(), PhotoEffect.SKETCH, 0.5f))
        assertTrue(o.all { android.graphics.Color.red(it) == android.graphics.Color.green(it) && android.graphics.Color.green(it) == android.graphics.Color.blue(it) })
        assertTrue("flat areas should be near white", android.graphics.Color.red(o[140 * 240 + 200]) > 225)
    }

    @Test fun duotoneMapsBlackAndWhiteToTheTwoColours() {
        val b = android.graphics.Bitmap.createBitmap(2, 1, android.graphics.Bitmap.Config.ARGB_8888); b.setPixel(0, 0, 0xFF000000.toInt()); b.setPixel(1, 0, 0xFFFFFFFF.toInt())
        val o = PhotoEffects.apply(b, PhotoEffect.SUNSET_DUO, 1f)
        assertEquals(0xFF2B1055.toInt(), o.getPixel(0, 0)); assertEquals(0xFFFFB84D.toInt(), o.getPixel(1, 0))
    }

    @Test fun cartoonPosterisesToFewColours() {
        val distinct = px(PhotoEffects.apply(scene(), PhotoEffect.CARTOON, 0.5f)).toSet().size
        assertTrue("posterised image should have few colours, had $distinct", distinct <= 5 * 5 * 5 + 1)
    }

    @Test fun grainAddsNoiseToAFlatImageButIsRepeatable() {
        val flat = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF808080.toInt()) }
        val a = px(PhotoEffects.apply(flat, PhotoEffect.GRAIN, 0.6f)); val b2 = px(PhotoEffects.apply(flat, PhotoEffect.GRAIN, 0.6f))
        assertTrue("noise expected", a.toSet().size > 20); assertArrayEquals(a, b2)
    }

    @Test fun glitchAndBlurChangeTheImage() {
        val s = scene(); val src = px(s)
        assertFalse(px(PhotoEffects.apply(s, PhotoEffect.GLITCH, 0.7f)).contentEquals(src))
        val blurred = px(PhotoEffects.apply(s, PhotoEffect.BLUR, 0.8f))
        // the hard edge of the circle (x = 70 at y = 80) must be softer after blurring
        val edgeStep = { a: IntArray -> kotlin.math.abs(android.graphics.Color.red(a[80 * 240 + 71]) - android.graphics.Color.red(a[80 * 240 + 68])) }
        assertTrue("blur should soften edges", edgeStep(blurred) < edgeStep(src))
    }

    @Test fun effectIsAppliedByTheFullPhotoPipeline() {
        val s = scene()
        val plain = PhotoProcessor.apply(s, null, PhotoEdit()); val fx = PhotoProcessor.apply(s, null, PhotoEdit(effect = PhotoEffect.SKETCH))
        assertFalse(px(plain).contentEquals(px(fx)))
    }
}

/** Beat-synced slideshow, end to end: real decoder, real analysis, real export. */
class BeatSyncDeviceTest {
    private val ctx get() = TestUtil.ctx
    private val beats get() = android.net.Uri.fromFile(java.io.File(ctx.getExternalFilesDir(null), "test_beats.wav"))

    @Test fun analyserFindsTheTempoOfARealAudioFile() {
        val info = BeatAnalyzer.analyse(ctx, beats, 0) ?: fail("no beats found") as Nothing
        assertEquals("bpm", 124.0, info.bpm.toDouble(), 3.0)
        val period = 60000.0 / 124
        for (b in info.beatsMs.take(20)) {            // clicks are at 180 ms + k * period
            val k = Math.round((b - 180) / period); val err = kotlin.math.abs(b - (180 + k * period))
            assertTrue("beat $b is ${err.toInt()} ms from a click", err < 60)
        }
    }

    @Test fun analysisFromALaterStartStillFindsTheTempo() {
        val info = BeatAnalyzer.analyse(ctx, beats, 5000) ?: fail("no beats") as Nothing
        assertEquals(124.0, info.bpm.toDouble(), 3.0)
    }

    @Test fun viewModelAnalysesAndPhotoLengthsFollowTheBeat() {
        val vm = SlideshowViewModel(ctx.applicationContext as android.app.Application)
        Handler(Looper.getMainLooper()).post { vm.addPhotos(TestUtil.photos); vm.setMusic(beats) }
        val end = System.currentTimeMillis() + 30_000
        while ((vm.project.value.photos.size < 3 || vm.project.value.music == null) && System.currentTimeMillis() < end) Thread.sleep(100)
        Handler(Looper.getMainLooper()).post { vm.setBeatSync(true); vm.setBeatsPerPhoto(4) }
        val end2 = System.currentTimeMillis() + 30_000
        while (vm.beatStatus.value !is SlideshowViewModel.BeatStatus.Found && System.currentTimeMillis() < end2) Thread.sleep(100)
        assertTrue("status ${vm.beatStatus.value}", vm.beatStatus.value is SlideshowViewModel.BeatStatus.Found)
        val durs = vm.project.value.photoDurationsMs()
        val fourBeats = 60000.0 / 124 * 4
        assertEquals(3, durs.size)
        for (d in durs.drop(1)) assertEquals("photo length = 4 beats", fourBeats, d.toDouble(), 40.0)
        assertEquals(durs.sum(), vm.project.value.totalDurationMs)
    }

    @Test fun beatSyncedSlideshowExportsWithLengthsOnTheBeat() {
        val b = BeatAnalyzer.analyse(ctx, beats, 0)!!
        val p = SlideshowProject(photos = TestUtil.photos, canvas = CanvasPreset.SQUARE, quality = ExportQuality.SD,
            music = MusicTrack(beats, "beats", durationMs = 24000), beatSync = true, beatsPerPhoto = 2, beats = b.beatsMs)
        val expected = p.totalDurationMs
        val latch = java.util.concurrent.CountDownLatch(1); var last: ExportState = ExportState.Idle
        Handler(Looper.getMainLooper()).post { SlideshowExporter(ctx).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() } }
        assertTrue("export timed out", latch.await(150, java.util.concurrent.TimeUnit.SECONDS))
        val done = last as? ExportState.Done ?: fail("export failed: $last") as Nothing
        val t = TestUtil.measure(done.uri)
        assertTrue("video ${t.videoMs} ms vs planned $expected ms", kotlin.math.abs(t.videoMs - expected) < 700)
        assertTrue("music present", t.audioMs > 0)
    }
}


class GrowthFeatureTest {
    private val ctx get() = TestUtil.ctx

    @Test fun shareIntentGoesStraightToTheChosenApp() {
        val uri = android.net.Uri.parse("content://media/external/video/media/1")
        val i = ShareTargets.intentFor(uri, "com.whatsapp")
        assertEquals(Intent.ACTION_SEND, i.action); assertEquals("com.whatsapp", i.`package`); assertEquals("video/mp4", i.type)
        assertEquals(uri, i.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun onlyInstalledAppsAreOffered() {
        val installed = ShareTargets.installed(ctx)
        installed.forEach { t -> assertTrue("${t.pkg} should be installed", runCatching { ctx.packageManager.getPackageInfo(t.pkg, 0) }.isSuccess) }
        assertTrue(installed.size <= ShareTargets.known.size)
        // an app that is certainly not installed must not appear
        assertFalse(installed.any { it.pkg == "com.example.not.installed" })
    }

    @Test fun promoLinksPointAtThisAppOnPlay() {
        assertEquals("https://play.google.com/store/apps/details?id=com.video.framora", AppPromo.playUrl(ctx))
        val send = AppPromo.shareIntent(ctx).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/plain", send.type); assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("id=com.video.framora"))
        assertEquals("market://details?id=com.video.framora", AppPromo.rateIntent(ctx).dataString)
    }

    @Test fun shortcutActionsMapToScreens() {
        assertEquals(Screen.CAMERA, Shortcuts.screenFor(Shortcuts.REEL)); assertEquals(Screen.SLIDESHOW, Shortcuts.screenFor(Shortcuts.SLIDESHOW))
        assertNull(Shortcuts.screenFor("android.intent.action.MAIN")); assertNull(Shortcuts.screenFor(null))
    }
}

/** Rotate and mirror, verified by finding a red block in real exported frames. */
class RotateFlipTest {
    private val ctx get() = TestUtil.ctx

    /** Builds a short video from one image that has a red block in its top-left corner and grey elsewhere. */
    private fun asymmetricClip(): VideoClip {
        val f = java.io.File(ctx.getExternalFilesDir(null), "asym.jpg")
        android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            val c = android.graphics.Canvas(b); c.drawColor(0xFF808080.toInt())
            c.drawRect(0f, 0f, 220f, 140f, android.graphics.Paint().apply { color = 0xFFFF0000.toInt() })
        }.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, f.outputStream())
        val p = SlideshowProject(photos = listOf(android.net.Uri.fromFile(f)), secondsPerPhoto = 2f, canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, motion = Motion.NONE)
        val latch = java.util.concurrent.CountDownLatch(1); var last: ExportState = ExportState.Idle
        Handler(Looper.getMainLooper()).post { SlideshowExporter(ctx).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() } }
        assertTrue(latch.await(120, java.util.concurrent.TimeUnit.SECONDS))
        val uri = (last as? ExportState.Done ?: fail("could not build the test clip: $last") as Nothing).uri
        val copy = java.io.File(ctx.getExternalFilesDir(null), "asym.mp4")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
        return VideoClip(android.net.Uri.fromFile(copy), "asym", 2000, width = 856, height = 482)
    }

    /** Centre of the red pixels in a mid-clip frame as fractions of the frame (x, y), or null if none. */
    private fun redCentre(video: android.net.Uri): Pair<Double, Double>? {
        val r = android.media.MediaMetadataRetriever().also { it.setDataSource(ctx, video) }
        val bmp = r.getFrameAtTime(900_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!; r.release()
        var sx = 0L; var sy = 0L; var n = 0L
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 170 && android.graphics.Color.green(c) < 90 && android.graphics.Color.blue(c) < 90) { sx += x; sy += y; n++ }
        }
        return if (n < 20) null else (sx.toDouble() / n / bmp.width) to (sy.toDouble() / n / bmp.height)
    }

    private fun exported(clip: VideoClip): android.net.Uri {
        val latch = java.util.concurrent.CountDownLatch(1); var last: ExportState = ExportState.Idle
        Handler(Looper.getMainLooper()).post {
            val comp = buildVideoComposition(ctx, VideoProject(clips = listOf(clip), canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD))
            val out = java.io.File(ctx.cacheDir, "rf_${System.nanoTime()}.mp4")
            androidx.media3.transformer.Transformer.Builder(ctx).setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .addListener(object : androidx.media3.transformer.Transformer.Listener {
                    override fun onCompleted(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult) { last = ExportState.Done(android.net.Uri.fromFile(out)); latch.countDown() }
                    override fun onError(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult, e: androidx.media3.transformer.ExportException) { last = ExportState.Failed(e.errorCodeName); latch.countDown() }
                }).build().start(comp, out.absolutePath)
        }
        assertTrue(latch.await(120, java.util.concurrent.TimeUnit.SECONDS))
        return (last as? ExportState.Done ?: fail("export failed: $last") as Nothing).uri
    }

    @Test fun rotateAndMirrorMoveTheRedBlockToTheRightCorner() {
        val base = asymmetricClip()
        val none = redCentre(exported(base))!!
        assertTrue("untouched: top-left, got $none", none.first < 0.4 && none.second < 0.4)
        val mirrored = redCentre(exported(base.copy(flipH = true)))!!
        assertTrue("mirror: top-right, got $mirrored", mirrored.first > 0.6 && mirrored.second < 0.4)
        val r180 = redCentre(exported(base.copy(rotation = 180)))!!
        assertTrue("180°: bottom-right, got $r180", r180.first > 0.6 && r180.second > 0.6)
        val r90 = redCentre(exported(base.copy(rotation = 90)))!!      // clockwise: top-left goes to top-right
        assertTrue("90° clockwise: right half, upper, got $r90", r90.first > 0.5 && r90.second < 0.5)
        val r270 = redCentre(exported(base.copy(rotation = 270)))!!    // counter-clockwise: top-left goes to bottom-left
        assertTrue("270°: left half, lower, got $r270", r270.first < 0.5 && r270.second > 0.5)
    }

    @Test fun rotationAndMirrorCycleInTheModel() {
        val p = VideoProject(clips = listOf(VideoClip(android.net.Uri.parse("content://x"), "c", 1000)))
        var q = p; repeat(4) { q = q.rotate(0) }
        assertEquals(0, q.clips[0].rotation)
        assertEquals(90, p.rotate(0).clips[0].rotation); assertTrue(p.flip(0).clips[0].flipH); assertFalse(p.flip(0).flip(0).clips[0].flipH)
        assertNull(transformEffect(p.clips[0])); assertNotNull(transformEffect(p.rotate(0).clips[0]))
    }
}

/** Run first by tools/runtest.sh when no video fixture exists: builds a ~4.5 s H.264 + AAC clip from the photos and the tone. */
class FixtureBuilderTest {
    @Test fun buildVideoFixture() {
        val ctx = TestUtil.ctx
        val p = SlideshowProject(photos = TestUtil.photos, secondsPerPhoto = 1.5f, canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD,
            music = MusicTrack(TestUtil.tone, "tone", durationMs = 6000))
        val latch = CountDownLatch(1); var last: ExportState = ExportState.Idle
        Handler(Looper.getMainLooper()).post { SlideshowExporter(ctx).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() } }
        assertTrue(latch.await(150, TimeUnit.SECONDS))
        val uri = (last as? ExportState.Done ?: fail("fixture export failed: $last") as Nothing).uri
        java.io.File(ctx.getExternalFilesDir(null), "test_av.mp4").outputStream().use { o -> ctx.contentResolver.openInputStream(uri)!!.use { it.copyTo(o) } }
    }
}

/** GIF maker end to end: real video in, GIF file out, checked by parsing it and by Android's own GIF decoder. */
class GifMakerDeviceTest {
    private val ctx get() = TestUtil.ctx
    data class Info(val w: Int, val h: Int, val frames: Int, val loops: Boolean, val delays: List<Int>)

    private fun parse(b: ByteArray): Info {
        var p = 6
        fun u8() = b[p++].toInt() and 255
        fun u16() = u8() or (u8() shl 8)
        assertEquals("GIF89a", String(b, 0, 6)); val w = u16(); val h = u16(); val packed = u8(); u8(); u8()
        if (packed and 0x80 != 0) p += 3 * (1 shl ((packed and 7) + 1))
        var frames = 0; var loops = false; val delays = ArrayList<Int>()
        while (true) when (u8()) {
            0x21 -> { val label = u8()
                if (label == 0xF9) { u8(); u8(); delays += u16(); u8(); u8() }
                else { if (label == 0xFF) { val n = u8(); if (String(b, p, n) == "NETSCAPE2.0") loops = true; p += n }
                    while (true) { val n = u8(); if (n == 0) break; p += n } } }
            0x2C -> { frames++; p += 8; val ip = u8(); if (ip and 0x80 != 0) p += 3 * (1 shl ((ip and 7) + 1)); u8(); while (true) { val n = u8(); if (n == 0) break; p += n } }
            0x3B -> return Info(w, h, frames, loops, delays)
            else -> fail("corrupt GIF near byte $p")
        }
    }

    private fun bytes(uri: android.net.Uri) = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun make(s: GifSettings) = GifMaker.make(ctx, TestUtil.video, s)

    @Test fun normalGifHasTheRightSizeFramesLoopAndDelay() {
        val d = GifMaker.durationMs(ctx, TestUtil.video); assertTrue("fixture should be a few seconds, was $d", d in 3000..8000)
        val r = make(GifSettings(0, 3000, fps = 10, size = GifSize.SMALL))
        val i = parse(bytes(r.uri))
        assertEquals(30, i.frames); assertEquals(30, r.frames); assertTrue(i.loops); assertTrue(i.delays.all { it == 10 })
        assertEquals(240, i.w); assertEquals(i.w, r.width); assertEquals(i.h, r.height)
        assertEquals("16:9 source", 240 * 9 / 16.0, i.h.toDouble(), 3.0)
    }

    @Test fun androidsOwnDecoderReadsTheGifAndColoursAreRight() {
        val r = make(GifSettings(0, 1000, fps = 8, size = GifSize.SMALL))
        val bmp = android.graphics.BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(r.uri))!!   // the system decoder must accept our file
        assertEquals(r.width, bmp.width); assertEquals(r.height, bmp.height)
        val c = bmp.getPixel(bmp.width / 2, bmp.height / 2); val corner = bmp.getPixel(4, 4)
        assertTrue("centre of the first photo is the white disc, got #${Integer.toHexString(c)}", android.graphics.Color.red(c) > 215 && android.graphics.Color.green(c) > 215 && android.graphics.Color.blue(c) > 215)
        assertTrue("corner is the first photo's red, got #${Integer.toHexString(corner)}", android.graphics.Color.red(corner) > 170 && android.graphics.Color.green(corner) < 110 && android.graphics.Color.blue(corner) < 110)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val dr = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(ctx.contentResolver, r.uri))
            assertTrue("animated", dr is android.graphics.drawable.AnimatedImageDrawable)
        }
    }

    @Test fun laterPartsOfTheVideoAreCaptured() {
        val d = GifMaker.durationMs(ctx, TestUtil.video)
        val r = make(GifSettings(d - 1200, d - 100, fps = 8, size = GifSize.SMALL))   // last photo: green background
        val bmp = android.graphics.BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(r.uri))!!
        val corner = bmp.getPixel(4, 4)
        assertTrue("last photo is green, got #${Integer.toHexString(corner)}", android.graphics.Color.green(corner) > 150 && android.graphics.Color.red(corner) < 130)
    }

    @Test fun boomerangPlaysForwardThenBackWithoutRepeatingTheEnds() {
        val r = make(GifSettings(0, 2000, fps = 10, size = GifSize.SMALL, style = GifStyle.BOOMERANG))
        assertEquals(20 * 2 - 2, parse(bytes(r.uri)).frames)
        assertEquals(listOf(1, 2, 3, 2), GifMaker.boomerang(listOf(1, 2, 3)).let { it })   // forward, back, no duplicated end
    }

    @Test fun captionIsDrawnOnTheFrames() {
        val plain = android.graphics.BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(make(GifSettings(0, 1000, 8, GifSize.SMALL)).uri))!!
        val cap = android.graphics.BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(make(GifSettings(0, 1000, 8, GifSize.SMALL, caption = "Hello world")).uri))!!
        fun dark(b: android.graphics.Bitmap) = (0 until b.width).count { x -> (b.height * 3 / 4 until b.height).any { y -> val c = b.getPixel(x, y); android.graphics.Color.red(c) < 40 && android.graphics.Color.green(c) < 40 && android.graphics.Color.blue(c) < 40 } }
        assertTrue("caption outline adds black pixels near the bottom (plain ${dark(plain)}, captioned ${dark(cap)})", dark(cap) > dark(plain) + 20)
    }

    @Test fun savedToTheGalleryAsGif() {
        val r = make(GifSettings(0, 800, 8, GifSize.SMALL))
        ctx.contentResolver.query(r.uri, arrayOf(android.provider.MediaStore.MediaColumns.MIME_TYPE, android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.provider.MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("image/gif", it.getString(0)); assertTrue(it.getString(1).startsWith("Pictures/PhotoVideoStudio")); assertEquals(r.bytes, it.getLong(2))
        }
        assertTrue(GifMaker.loadable(r.uri, ctx))
    }

    @Test fun viewModelRunsTheWholeFlowAndHandlesBadInput() {
        val vm = GifViewModel(ctx.applicationContext as android.app.Application)
        Handler(Looper.getMainLooper()).post { vm.load(TestUtil.video) }
        waitFor { vm.state.value is GifState.Ready }
        val ready = vm.state.value as GifState.Ready
        assertTrue(ready.settings.lengthMs <= GifSettings.MAX_MS)
        Handler(Looper.getMainLooper()).post { vm.update { it.copy(fps = 8, size = GifSize.SMALL, endMs = 1500) }; vm.make() }
        waitFor { vm.state.value is GifState.Done || vm.state.value is GifState.Failed }
        assertTrue("state: ${vm.state.value}", vm.state.value is GifState.Done)
        Handler(Looper.getMainLooper()).post { vm.load(android.net.Uri.parse("file:///no/such/video.mp4")) }
        waitFor { vm.state.value is GifState.Failed }
    }

    private fun waitFor(ms: Long = 60_000, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(100); assertTrue("timed out", cond()) }

    @Test fun rangeAndSizeMathIsSafe() {
        assertEquals(0L to 10_000L, GifMaker.clampRange(0, 60_000, 60_000))              // capped at 10 s
        assertEquals(4_000L to 4_500L, GifMaker.clampRange(4_000, 4_100, 60_000))        // at least 0.5 s
        assertEquals(500L to 1_000L, GifMaker.clampRange(900, 1_000, 1_000))             // near the end of a short clip
        val s = GifMaker.outputSize(1080, 1920, 360); assertEquals(360 to 640, s)
        assertEquals(200, GifMaker.outputSize(200, 100, 480).first)                      // never upscales
    }
}
