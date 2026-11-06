package com.video.framora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val ctx2 get() = TestUtil.ctx
private fun free() { Pro.init(ctx2); Pro.reset() }

private fun slideshow(p: SlideshowProject): Uri {
    val latch = CountDownLatch(1); var last: ExportState = ExportState.Idle
    Handler(Looper.getMainLooper()).post { SlideshowExporter(ctx2).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() } }
    assertTrue("slideshow export timed out", latch.await(150, TimeUnit.SECONDS))
    return (last as? ExportState.Done ?: fail("slideshow export failed: $last") as Nothing).uri
}

private fun video(p: VideoProject): Uri {
    val comp = buildVideoComposition(ctx2, p); val out = File(ctx2.cacheDir, "more_${System.nanoTime()}.mp4"); val latch = CountDownLatch(1); var err: String? = null
    Handler(Looper.getMainLooper()).post {
        androidx.media3.transformer.Transformer.Builder(ctx2).setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264).addListener(object : androidx.media3.transformer.Transformer.Listener {
            override fun onCompleted(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult) { latch.countDown() }
            override fun onError(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult, e: androidx.media3.transformer.ExportException) { err = e.errorCodeName; latch.countDown() }
        }).build().start(comp, out.absolutePath)
    }
    assertTrue("video export timed out", latch.await(180, TimeUnit.SECONDS)); assertNull("video export failed: $err", err); return Uri.fromFile(out)
}

private fun frame(v: Uri, ms: Long): Bitmap = MediaMetadataRetriever().let { r -> r.setDataSource(ctx2, v); val b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!; r.release(); b }
private fun isMagenta(c: Int) = Color.red(c) > 190 && Color.green(c) < 90 && Color.blue(c) > 190
private fun magenta(b: Bitmap, x0: Double, y0: Double, x1: Double, y1: Double): Int { var n = 0; for (y in (b.height * y0).toInt() until (b.height * y1).toInt() step 2) for (x in (b.width * x0).toInt() until (b.width * x1).toInt() step 2) if (isMagenta(b.getPixel(x, y))) n++; return n }
private fun brightness(b: Bitmap): Double { var s = 0.0; var n = 0; for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) { val c = b.getPixel(x, y); s += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0; n++ }; return s / n }
private fun logoFile(): String = File(ctx2.getExternalFilesDir(null), "logo_test.png").also { f -> Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFFF00FF.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, f.outputStream()) }.let { Uri.fromFile(it).toString() }

class ProMoreExportTest {
    @Before fun before() = free()
    @After fun after() { Pro.reset() }
    private val one get() = listOf(TestUtil.photos[0])
    private fun base() = SlideshowProject(photos = one, secondsPerPhoto = 2f, canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, motion = Motion.NONE)

    @Test fun theLogoSitsInTheChosenCornerOfASlideshowAndNowhereElse() {
        val logo = logoFile()
        for ((corner, quad) in mapOf(LogoCorner.TOP_RIGHT to listOf(0.5, 0.0, 1.0, 0.5), LogoCorner.BOTTOM_LEFT to listOf(0.0, 0.5, 0.5, 1.0), LogoCorner.TOP_LEFT to listOf(0.0, 0.0, 0.5, 0.5), LogoCorner.BOTTOM_RIGHT to listOf(0.5, 0.5, 1.0, 1.0))) {
            val f = frame(slideshow(base().copy(logoUri = logo, logoCorner = corner)), 600)
            val inside = magenta(f, quad[0], quad[1], quad[2], quad[3]); val total = magenta(f, 0.0, 0.0, 1.0, 1.0)
            assertTrue("$corner: logo should be in its quadrant, found $inside", inside > 600); assertEquals("$corner: and only there", inside, total)
        }
        assertEquals("no logo, no magenta", 0, magenta(frame(slideshow(base()), 600), 0.0, 0.0, 1.0, 1.0))
    }

    @Test fun theLogoIsOnTheVideoEditorExportToo() {
        val d = GifMaker.durationMs(ctx2, TestUtil.video)
        val f = frame(video(VideoProject(clips = listOf(VideoClip(TestUtil.video, "v", d)), canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, logoUri = logoFile(), logoCorner = LogoCorner.TOP_LEFT)), 500)
        assertTrue(magenta(f, 0.0, 0.0, 0.5, 0.5) > 600); assertEquals(0, magenta(f, 0.5, 0.0, 1.0, 1.0) + magenta(f, 0.0, 0.5, 0.5, 1.0))
    }

    @Test fun theLogoIsOnGifsTooAndFreeGifsNeverGetIt() {
        fun first(logo: String?): Bitmap = BitmapFactory.decodeStream(ctx2.contentResolver.openInputStream(GifMaker.make(ctx2, TestUtil.video, GifSettings(0, 800, 8, GifSize.MEDIUM, logoUri = logo, logoCorner = LogoCorner.TOP_RIGHT)).uri))!!
        val withLogo = first(logoFile()); assertTrue("logo pixels top-right: ${magenta(withLogo, 0.5, 0.0, 1.0, 0.5)}", magenta(withLogo, 0.5, 0.0, 1.0, 0.5) > 300)
        assertEquals(0, magenta(first(null), 0.0, 0.0, 1.0, 1.0))
    }

    @Test fun flashWhiteBlanksTheFrameWhereDipGoesBlack() {
        val two = listOf(TestUtil.photos[0], TestUtil.photos[1])
        val flash = slideshow(base().copy(photos = two, transition = Transition.FLASH)); val dip = slideshow(base().copy(photos = two, transition = Transition.DIP))
        assertTrue("flash at the cut should be near white: ${brightness(frame(flash, 2030))}", brightness(frame(flash, 2030)) > 215)
        assertTrue("dip at the cut should be near black: ${brightness(frame(dip, 2030))}", brightness(frame(dip, 2030)) < 45)
        assertTrue("mid-photo is the normal picture", brightness(frame(flash, 1000)) in 60.0..200.0)
    }

    @Test fun theOutlineCaptionHasABlackOutlineWhereClassicHasNone() {
        fun blackOpaque(style: CaptionStyle): Int {
            val o = CaptionOverlay(listOf(Caption("Hello outline", 0, 5000)), 0, 720, 1280, style, CaptionAnim.NONE, -0.7f, 1f); val b = o.getBitmap(500_000)
            var n = 0; for (y in 0 until b.height) for (x in 0 until b.width) { val c = b.getPixel(x, y); if (Color.alpha(c) > 220 && Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40) n++ }; return n
        }
        val outline = blackOpaque(CaptionStyle.OUTLINE); val classic = blackOpaque(CaptionStyle.CLASSIC)
        assertTrue("outline $outline vs classic $classic", outline > classic * 3 + 200)
    }

    @Test fun everyProCaptionStyleRendersAndLooksDifferent() {
        fun sig(style: CaptionStyle): Long { val b = CaptionOverlay(listOf(Caption("Style test", 0, 5000)), 0, 720, 1280, style, CaptionAnim.NONE, -0.7f, 1f).getBitmap(500_000); var h = 0L; for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) h = h * 31 + b.getPixel(x, y); return h }
        val sigs = CaptionStyle.entries.associateWith { sig(it) }; assertEquals("every style renders differently", CaptionStyle.entries.size, sigs.values.toSet().size)
    }

    @Test fun robotEchoAndAlienExportWithAudioInStep() {
        val d = GifMaker.durationMs(ctx2, TestUtil.video)
        for (fx in listOf(VoiceFx.ROBOT, VoiceFx.ECHO, VoiceFx.ALIEN)) {
            val t = TestUtil.measure(video(VideoProject(clips = listOf(VideoClip(TestUtil.video, "v", d)), canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, originalVoice = fx)))
            assertTrue("$fx: has audio", t.audioMs > 0); assertTrue("$fx: audio ${t.audioMs} vs video ${t.videoMs}", abs(t.audioMs - t.videoMs) < 600)
        }
    }

    @Test fun timelapseAndSlowMotionChangeTheLengthAsPromised() {
        val d = GifMaker.durationMs(ctx2, TestUtil.video)
        fun length(speed: Float, trimEnd: Long) = TestUtil.measure(video(VideoProject(clips = listOf(VideoClip(TestUtil.video, "v", d, trimEndMs = trimEnd, speed = speed)), canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD))).videoMs
        assertEquals("5× on 4 s", 800.0, length(5f, 4000).toDouble(), 450.0); assertEquals("8× on 4 s", 500.0, length(8f, 4000).toDouble(), 400.0)
        assertEquals("0.1× on 1 s", 10_000.0, length(0.1f, 1000).toDouble(), 1500.0)
    }
}

class ProMorePhotoTest {
    @Before fun before() = free()
    @After fun after() { Pro.reset() }

    private fun textBitmap(font: TextFont): Bitmap {
        val b = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.BLACK)
        PhotoProcessor.drawText(Canvas(b), TextLayer("Hamburg 123", 0.5f, 0.5f, 0.3f, Palette.WHITE, font, bold = false), 480f, 160f); return b
    }
    private fun diff(a: Bitmap, b: Bitmap): Int { var n = 0; for (y in 0 until a.height) for (x in 0 until a.width) if (abs(Color.red(a.getPixel(x, y)) - Color.red(b.getPixel(x, y))) > 60) n++; return n }

    @Test fun proFontsDrawDifferentlyFromTheDefault() {
        val sans = textBitmap(TextFont.SANS); val differing = TextFont.entries.filter { it.pro }.filter { diff(sans, textBitmap(it)) > 250 }
        assertTrue("at least 5 of the 7 Pro fonts must look different on this device, got ${differing.map { it.label }}", differing.size >= 5)
    }

    private fun grey(w: Int = 200, h: Int = 100) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF808080.toInt()) }
    private val bgRed = 0xFFE53935.toInt()

    @Test fun polaroidHasAThickBottomMargin() {
        val o = PhotoProcessor.frame(grey(), FrameStyle.POLAROID, 10, bgRed)
        assertEquals(220, o.width); assertEquals(150, o.height); assertEquals(bgRed, o.getPixel(5, 5)); assertEquals("the thick bottom is frame colour", bgRed, o.getPixel(110, 140)); assertEquals(0xFF808080.toInt(), o.getPixel(110, 50))
    }

    @Test fun filmStripHasDarkBarsWithBrightHoles() {
        val o = PhotoProcessor.frame(grey(), FrameStyle.FILM, 10, bgRed)
        assertEquals(200, o.width); assertTrue(o.height > 100); var dark = 0; var bright = 0
        for (y in 0 until (o.height - 100) / 2) for (x in 0 until o.width) { val c = o.getPixel(x, y); if (Color.red(c) < 40) dark++ else if (Color.red(c) > 200) bright++ }
        assertTrue("dark $dark", dark > 500); assertTrue("holes $bright", bright > 40); assertEquals("the photo sits between the bars", 0xFF808080.toInt(), o.getPixel(100, o.height / 2))
    }

    @Test fun roundedFrameCutsTheCornersOfThePhoto() {
        val o = PhotoProcessor.frame(grey(), FrameStyle.ROUNDED, 10, bgRed)
        assertEquals("photo corner is rounded away to the frame colour", bgRed, o.getPixel(11, 11)); assertEquals(0xFF808080.toInt(), o.getPixel(110, 60)); assertEquals(220, o.width)
    }

    @Test fun shadowFrameDarkensTheEdgeJustOutsideThePhoto() {
        val o = PhotoProcessor.frame(grey(), FrameStyle.SHADOW, 12, 0xFFFFFFFF.toInt())
        val below = Color.red(o.getPixel(o.width / 2, 2 * 12 + 100 + 6)); val far = Color.red(o.getPixel(o.width / 2, o.height - 2))
        assertTrue("shadow below the photo ($below) must be darker than the plain background ($far)", below < far - 25)
    }

    @Test fun theFullPipelineAppliesAFrameStyleWithoutABorderWidth() {
        val src = grey(300, 200); val o = PhotoProcessor.apply(src, null, PhotoEdit(frameStyle = FrameStyle.POLAROID)); assertTrue(o.width > 300 && o.height > o.width * 200 / 300)
        assertEquals("plain with no border adds nothing", 300, PhotoProcessor.apply(src, null, PhotoEdit()).width)
    }
}
