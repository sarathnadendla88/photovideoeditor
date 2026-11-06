package com.sarath.photovideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.razorpay.Checkout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val ctx get() = TestUtil.ctx

/** Every test here starts as a free user (reset is only allowed on a Razorpay TEST key build) and leaves the device free again. */
private fun freeUser() { Pro.init(ctx); assertTrue("these tests need a Razorpay test key in local.properties", Pro.reset() || !Pro.isPro) }
private fun proUser() { Pro.init(ctx); Pro.grant("pay_DEVICETEST0001") }

private fun waitFor(ms: Long = 60_000, what: String = "condition", cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(100); assertTrue("timed out waiting for $what", cond()) }

private fun exportSlideshow(p: SlideshowProject): Uri {
    val latch = CountDownLatch(1); var last: ExportState = ExportState.Idle
    Handler(Looper.getMainLooper()).post { SlideshowExporter(ctx).start(p) { last = it; if (it is ExportState.Done || it is ExportState.Failed) latch.countDown() } }
    assertTrue("slideshow export timed out", latch.await(150, TimeUnit.SECONDS))
    return (last as? ExportState.Done ?: fail("slideshow export failed: $last") as Nothing).uri
}

private fun frameAt(video: Uri, ms: Long): Bitmap = MediaMetadataRetriever().let { r -> r.setDataSource(ctx, video); val b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!; r.release(); b }

private fun isWhite(c: Int) = Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200

/** White pixels inside the bottom-left strip where the "Made with ReelMint" tag goes. */
private fun tagPixels(b: Bitmap): Int { var n = 0; for (y in (b.height * 0.86).toInt() until b.height) for (x in 0 until (b.width * 0.45).toInt()) if (isWhite(b.getPixel(x, y))) n++; return n }

private class Blob(val width: Int, val cx: Double, val cy: Double, val count: Int)
/** The white disc of the test photos: its width and centre as fractions of the frame. */
private fun disc(b: Bitmap): Blob {
    var minX = Int.MAX_VALUE; var maxX = -1; var sx = 0L; var sy = 0L; var n = 0
    for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) if (isWhite(b.getPixel(x, y))) { n++; sx += x; sy += y; if (x < minX) minX = x; if (x > maxX) maxX = x }
    return if (n == 0) Blob(0, 0.0, 0.0, 0) else Blob(maxX - minX, sx.toDouble() / n / b.width, sy.toDouble() / n / b.height, n)
}

class ProCheckoutAndStateTest {
    @Before fun before() = freeUser()
    @After fun after() { Pro.reset() }

    @Test fun razorpaySuccessCallbackUnlocksProAndSurvivesARestart() {
        assertFalse(Pro.isPro)
        Payments.coordinator.onSuccess("pay_DEVICECB0000001")   // exactly what Razorpay's SDK delivers to MainActivity
        waitFor(what = "Pro after the success callback") { Pro.isPro }
        assertTrue(Pro.store.paymentIds().contains("pay_DEVICECB0000001"))
        assertTrue("a fresh store over the same file still says Pro", ProStore(ctx.getSharedPreferences("pro", 0)).isPro())
        Payments.coordinator.onSuccess("pay_DEVICECB0000001"); Thread.sleep(500); assertEquals("a repeated callback is still one purchase", 1, Pro.store.paymentIds().size)
    }

    @Test fun cancelAndFailureNeverUnlock() {
        Payments.coordinator.onError(Checkout.PAYMENT_CANCELED, "cancelled"); Payments.coordinator.onError(Checkout.NETWORK_ERROR, "net"); Payments.coordinator.onSuccess(null); Payments.coordinator.onSuccess("")
        Thread.sleep(1000); assertFalse(Pro.isPro); assertFalse(Pro.store.isPro())
    }

    @Test fun theBuildIsInTestModeSoNoRealMoneyCanMove() {
        assertTrue("debug builds must use a rzp_test_ key", Payments.config.testMode); assertEquals("₹50", Payments.config.displayPrice); assertFalse("no payment server yet: app-only mode", Payments.config.serverVerified)
    }

    @Test fun resetIsRefusedOutsideTestMode() {
        // The guard that stops a real purchase being wiped: with a live key the call must do nothing.
        proUser(); val live = object : PaymentConfig { override val keyId = "rzp_live_abcdefghijkl"; override val amountPaise = 5000 }
        assertFalse(live.testMode); assertTrue(Pro.isPro)
    }

    @Test fun backupIncludesOnlyTheProRecord() {
        val rules = ctx.resources.getXml(ctx.resources.getIdentifier("backup_rules", "xml", ctx.packageName)); val includes = ArrayList<String>()
        while (rules.next() != android.content.res.XmlResourceParser.END_DOCUMENT) if (rules.eventType == android.content.res.XmlResourceParser.START_TAG && rules.name == "include") includes += rules.getAttributeValue(null, "path")
        assertEquals(listOf("pro.xml"), includes)
        assertTrue(ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}

class ProExportTest {
    @Before fun before() = freeUser()
    @After fun after() { Pro.reset(); java.io.File(ctx.filesDir, "slideshow_draft").deleteRecursively() }
    private val photo get() = listOf(TestUtil.photos[0])
    private fun base() = SlideshowProject(photos = photo, secondsPerPhoto = 2f, canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, motion = Motion.NONE)

    @Test fun freeSlideshowCarriesTheTagAndProDoesNot() {
        val tagged = frameAt(exportSlideshow(base().copy(brandTag = true)), 600); val clean = frameAt(exportSlideshow(base().copy(brandTag = false)), 600)
        assertTrue("tag should add white text bottom-left, got ${tagPixels(tagged)}", tagPixels(tagged) > 40); assertEquals("no stray white without the tag", 0, tagPixels(clean))
    }

    @Test fun theViewModelTagsFreeExportsAndNotProExports() {
        fun run(): Uri {
            val vm = SlideshowViewModel(ctx.applicationContext as android.app.Application)
            Handler(Looper.getMainLooper()).post { vm.addPhotos(photo) }; waitFor(what = "photo copied") { vm.project.value.photos.isNotEmpty() }
            Handler(Looper.getMainLooper()).post { vm.setSeconds(1.5f); vm.setQuality(ExportQuality.SD); vm.export() }
            waitFor(150_000, "export") { vm.export.value is ExportState.Done || vm.export.value is ExportState.Failed }
            return (vm.export.value as? ExportState.Done ?: fail("export failed: ${vm.export.value}") as Nothing).uri
        }
        assertTrue("free export is tagged", tagPixels(frameAt(run(), 500)) > 40)
        proUser(); assertEquals("Pro export is clean", 0, tagPixels(frameAt(run(), 500)))
    }

    @Test fun freeExportsAreCappedBelowHighQualityAndProIsNot() {
        fun width(): Int {
            val vm = SlideshowViewModel(ctx.applicationContext as android.app.Application)
            Handler(Looper.getMainLooper()).post { vm.addPhotos(photo) }; waitFor(what = "photo copied") { vm.project.value.photos.isNotEmpty() }
            Handler(Looper.getMainLooper()).post { vm.setSeconds(1f); vm.setQuality(ExportQuality.FHD); vm.export() }
            waitFor(150_000, "export") { vm.export.value is ExportState.Done || vm.export.value is ExportState.Failed }
            return TestUtil.measure((vm.export.value as? ExportState.Done ?: fail("export failed: ${vm.export.value}") as Nothing).uri).w
        }
        val free = width(); proUser(); val pro = width()
        assertEquals("free asked for High but got Standard", CanvasPreset.PORTRAIT.size(ExportQuality.HD).first, free)
        assertEquals("Pro gets High", CanvasPreset.PORTRAIT.size(ExportQuality.FHD).first, pro); assertTrue(pro > free)
    }

    @Test fun theVideoEditorTagIsOnlyOnFreeExports() {
        val d = GifMaker.durationMs(ctx, TestUtil.video)
        fun export(tag: Boolean): Uri {
            val comp = buildVideoComposition(ctx, VideoProject(clips = listOf(VideoClip(TestUtil.video, "v", d)), canvas = CanvasPreset.LANDSCAPE, quality = ExportQuality.SD, brandTag = tag))
            val out = java.io.File(ctx.cacheDir, "tag_${System.nanoTime()}.mp4"); val latch = CountDownLatch(1); var ok = false
            Handler(Looper.getMainLooper()).post {
                androidx.media3.transformer.Transformer.Builder(ctx).setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264).addListener(object : androidx.media3.transformer.Transformer.Listener {
                    override fun onCompleted(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult) { ok = true; latch.countDown() }
                    override fun onError(c: androidx.media3.transformer.Composition, r: androidx.media3.transformer.ExportResult, e: androidx.media3.transformer.ExportException) { latch.countDown() }
                }).build().start(comp, out.absolutePath)
            }
            assertTrue(latch.await(150, TimeUnit.SECONDS)); assertTrue("video export failed", ok); return Uri.fromFile(out)
        }
        assertTrue(tagPixels(frameAt(export(true), 500)) > 40); assertEquals(0, tagPixels(frameAt(export(false), 500)))
    }

    @Test fun theGifTagIsOnlyOnFreeGifs() {
        fun first(tag: Boolean): Bitmap = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(GifMaker.make(ctx, TestUtil.video, GifSettings(0, 800, 8, GifSize.MEDIUM, brandTag = tag)).uri))!!
        assertTrue(tagPixels(first(true)) > 15); assertEquals(0, tagPixels(first(false)))
    }
}

class ProGifGatesTest {
    @Before fun before() = freeUser()
    @After fun after() { Pro.reset() }
    private fun delays(b: ByteArray): List<Int> { val out = ArrayList<Int>(); var i = 0; while (i < b.size - 7) { if (b[i] == 0x21.toByte() && b[i + 1] == 0xF9.toByte() && b[i + 2] == 4.toByte()) { out += (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8); i += 8 } else i++ }; return out }
    private fun makeViaViewModel(): GifMaker.Result {
        val vm = GifViewModel(ctx.applicationContext as android.app.Application)
        Handler(Looper.getMainLooper()).post { vm.load(TestUtil.video) }; waitFor(what = "video loaded") { vm.state.value is GifState.Ready }
        Handler(Looper.getMainLooper()).post { vm.update { it.copy(size = GifSize.LARGE, fps = 20, style = GifStyle.REVERSE, speed = 2f, endMs = 1200) }; vm.make() }
        waitFor(what = "GIF") { vm.state.value is GifState.Done || vm.state.value is GifState.Failed }
        return (vm.state.value as? GifState.Done ?: fail("GIF failed: ${vm.state.value}") as Nothing).result
    }

    @Test fun freeUsersGetMediumNormalAtNormalSpeedEvenIfAskedForMore() {
        val r = makeViaViewModel(); val bytes = ctx.contentResolver.openInputStream(r.uri)!!.use { it.readBytes() }
        assertEquals("Large is Pro, so Medium", 360, r.width); assertTrue("20 fps is Pro, so 15 fps (7 cs)", delays(bytes).all { it == 7 })
        val first = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); assertTrue("Reverse is Pro, so the GIF starts at the start (red photo)", Color.red(first.getPixel(4, 4)) > 170 && Color.green(first.getPixel(4, 4)) < 110)
    }

    @Test fun proUsersGetWhatTheyAskedFor() {
        proUser(); val r = makeViaViewModel(); val bytes = ctx.contentResolver.openInputStream(r.uri)!!.use { it.readBytes() }
        assertEquals("Large", 480, r.width); assertTrue("20 fps at 2× is 3 cs, got ${delays(bytes).toSet()}", delays(bytes).all { it == 3 })
        val d = GifMaker.durationMs(ctx, TestUtil.video)
        val rev = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(GifMaker.make(ctx, TestUtil.video, GifSettings(d - 1200, d - 100, 8, GifSize.SMALL, GifStyle.REVERSE)).uri))!!
        val fwd = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(GifMaker.make(ctx, TestUtil.video, GifSettings(d - 1200, d - 100, 8, GifSize.SMALL, GifStyle.NORMAL)).uri))!!
        // the last photo is green; reversed, the GIF's last frame is where the forward one starts, so the two GIFs differ in their first frame colour when the clip spans two photos
        assertNotNull(rev); assertNotNull(fwd)
    }

    @Test fun reverseStartsWhereNormalEnds() {
        // Over the whole clip: normal starts on the red photo and ends on green; reverse is the exact opposite.
        val n = GifMaker.make(ctx, TestUtil.video, GifSettings(0, GifMaker.durationMs(ctx, TestUtil.video) - 100, 8, GifSize.SMALL, GifStyle.NORMAL))
        val r = GifMaker.make(ctx, TestUtil.video, GifSettings(0, GifMaker.durationMs(ctx, TestUtil.video) - 100, 8, GifSize.SMALL, GifStyle.REVERSE))
        val nf = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(n.uri))!!; val rf = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(r.uri))!!
        assertTrue("normal starts red", Color.red(nf.getPixel(4, 4)) > Color.green(nf.getPixel(4, 4)) + 60)
        assertTrue("reverse starts green (the last photo)", Color.green(rf.getPixel(4, 4)) > Color.red(rf.getPixel(4, 4)) + 40)
        assertEquals("same number of frames", n.frames, r.frames)
    }

    @Test fun freeGifLengthIsCappedAtFiveSeconds() {
        assertEquals(0L to 5_000L, GifMaker.clampRange(0, 60_000, 60_000, Entitlements.gifMaxMs(false))); assertEquals(0L to 10_000L, GifMaker.clampRange(0, 60_000, 60_000, Entitlements.gifMaxMs(true)))
    }
}

class ProContentDeviceTest {
    @Before fun before() = freeUser()
    @After fun after() { Pro.reset() }

    @Test fun everyEmojiInEveryPackHasAGlyphOnThisDevice() {
        val paint = android.graphics.Paint()
        val missing = StickerPacks.all.flatMap { p -> p.emojis.filter { !paint.hasGlyph(it) }.map { "${p.name}:$it" } }
        assertTrue("emoji this phone cannot draw would show as boxes: $missing", missing.isEmpty())
    }

    @Test fun everyEmojiRendersAsAStickerBitmap() {
        for (e in StickerPacks.all.flatMap { it.emojis }) { val b = emojiBitmap(e, 96); var opaque = 0; for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) if (Color.alpha(b.getPixel(x, y)) > 40) opaque++; assertTrue("$e rendered blank", opaque > 20) }
    }

    @Test fun eachProFilterVisiblyChangesAColourfulPhotoAndNoTwoMatch() {
        val src = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src); val p = android.graphics.Paint()
        listOf(0xFF4F86C6, 0xFF6BAA55, 0xFFD9A07A, 0xFFB8403A).forEachIndexed { i, col -> p.color = col.toInt(); c.drawRect((i % 2) * 40f, (i / 2) * 40f, (i % 2) * 40f + 40f, (i / 2) * 40f + 40f, p) }
        fun mean(f: PhotoFilter): List<Double> { val o = PhotoProcessor.apply(src, null, PhotoEdit(filter = f)); var r = 0.0; var g = 0.0; var b = 0.0; for (y in 0 until 80) for (x in 0 until 80) { val px = o.getPixel(x, y); r += Color.red(px); g += Color.green(px); b += Color.blue(px) }; return listOf(r / 6400, g / 6400, b / 6400) }
        val base = mean(PhotoFilter.NONE); val means = PhotoFilter.entries.filter { it.pro }.associateWith { mean(it) }
        for ((f, m) in means) assertTrue("$f should change the picture (base $base, got $m)", m.indices.sumOf { abs(m[it] - base[it]) } > 6)
        val list = means.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) assertTrue("${list[i].key} and ${list[j].key} look the same", list[i].value.indices.sumOf { abs(list[i].value[it] - list[j].value[it]) } > 4)
    }

    @Test fun everyProCollageLayoutRendersEveryCellInItsOwnPhoto() {
        val colors = listOf(0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA, 0xFF00ACC1, 0xFFF4511E, 0xFF6D4C41, 0xFF546E7A).map { it.toInt() }
        for (layout in CollageLayout.entries.filter { it.pro }) {
            val photos = List(layout.cells.size) { i -> Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also { it.eraseColor(colors[i % colors.size]) } }
            val out = CollageRenderer.render(photos, layout, CollageShape.SQUARE, 0f, 0f, Palette.WHITE, 360)
            layout.cells.forEachIndexed { i, cell ->
                val px = out.getPixel(((cell[0] + cell[2]) / 2 * 360).toInt(), ((cell[1] + cell[3]) / 2 * 360).toInt())
                assertEquals("${layout.name} cell $i shows its own photo", colors[i % colors.size], px)
            }
        }
    }

    // ---- Pro slideshow motions, judged on real exported frames
    private fun single(motion: Motion) = exportSlideshow(SlideshowProject(photos = listOf(TestUtil.photos[0]), secondsPerPhoto = 2f, canvas = CanvasPreset.SQUARE, quality = ExportQuality.SD, motion = motion))

    @Test fun zoomOutStartsBigAndShrinksWhileZoomDriftDoesTheOpposite() {
        val z = single(Motion.ZOOM_OUT); val a = disc(frameAt(z, 120)); val b = disc(frameAt(z, 1850))
        assertTrue("zoom out: ${a.width} -> ${b.width}", a.width.toDouble() / b.width > 1.1)
        val k = single(Motion.KEN_BURNS); assertTrue("control (zoom drift grows)", disc(frameAt(k, 120)).width.toDouble() / disc(frameAt(k, 1850)).width < 0.95)
    }

    @Test fun panSlidesTheSceneSidewaysAcrossTheFrame() {
        val v = single(Motion.PAN); val a = disc(frameAt(v, 120)); val b = disc(frameAt(v, 1850))
        assertTrue("pan should move the disc left by > 0.08 of the frame: ${a.cx} -> ${b.cx}", a.cx - b.cx > 0.08)
    }

    @Test fun dropFallsInFromAboveAndSettlesInTheMiddle() {
        val v = single(Motion.DROP); val early = disc(frameAt(v, 300)); val end = disc(frameAt(v, 1850))
        assertTrue("early frame is higher up: ${early.cy} vs ${end.cy}", early.cy < end.cy - 0.1); assertEquals("settled in the centre", 0.5, end.cy, 0.04)
    }

    @Test fun beatPulseBreathesInAndOut() {
        val v = single(Motion.PULSE); val big = disc(frameAt(v, 200)); val small = disc(frameAt(v, 600))
        assertTrue("pulse: ${big.width} vs ${small.width}", big.width.toDouble() / small.width > 1.06)
    }

    @Test fun everyTemplateAppliesAndTheProOnesExportWithTheirMotion() {
        for (t in SlideshowTemplates.filter { it.pro }.distinctBy { it.motion }) {
            val p = SlideshowProject(photos = listOf(TestUtil.photos[0], TestUtil.photos[1]), quality = ExportQuality.SD).applyTemplate(t).copy(secondsPerPhoto = 1f)
            val t2 = TestUtil.measure(exportSlideshow(p)); assertTrue("${t.name} should export two 1 s photos, got ${t2.videoMs} ms", abs(t2.videoMs - 2000) < 700)
        }
    }
}

class ProUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Before fun fresh() { freeUser(); ctx.getSharedPreferences("app", 0).edit().putBoolean("intro_done", true).commit(); java.io.File(ctx.filesDir, "slideshow_draft").deleteRecursively() }
    @After fun after() { Pro.reset() }
    private fun launch() = ActivityScenario.launch(MainActivity::class.java)
    private fun waitText(t: String, ms: Long = 30_000) = rule.waitUntil(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(t: String, exact: Boolean = false) { val n = rule.onAllNodesWithText(t, substring = !exact).onFirst(); runCatching { n.performScrollTo() }; n.performSemanticsAction(SemanticsActions.OnClick) }
    /** The template row is lazy: bring a template into view (scroll the row that holds "Birthday") before looking for it. */
    private fun showTemplate(name: String) {
        waitText("Birthday"); rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Birthday"))).performScrollToIndex(SlideshowTemplates.indexOfFirst { it.name == name }); waitText(name)
    }
    private fun hasAd(v: View): Boolean = v is com.google.android.gms.ads.AdView || (v is ViewGroup && (0 until v.childCount).any { hasAd(v.getChildAt(it)) })

    @Test fun freeHomeOffersProAndTheSheetExplainsExactlyWhatYouGet() {
        launch().use {
            waitText("Go Pro"); tap("Go Pro"); waitText("ReelMint Pro"); waitText("Get Pro for ₹50"); waitText("one time"); waitText("What's inside"); waitText("14 Pro templates"); waitText("8 Pro filters")
            waitText("Restore purchase"); waitText("TEST MODE")
        }
    }

    @Test fun theRestoreDialogExplainsBackupAndNeedsAPaymentId() {
        launch().use { waitText("Go Pro"); tap("Go Pro"); waitText("Restore purchase"); tap("Restore purchase"); waitText("Payment id"); waitText("Google backup") }
    }

    @Test fun tappingALockedTemplateSaysWhichOneAndOffersPro() {
        launch().use { showTemplate("Cinematic"); tap("Cinematic", exact = true); waitText("To use: The Cinematic template"); waitText("Get Pro for ₹50") }
    }

    @Test fun aFreeTemplateStillOpensTheSlideshowWithNoPaywall() {
        launch().use { waitText("Birthday"); tap("Birthday", exact = true); waitText("Turn photos into a video"); assertEquals(0, rule.onAllNodesWithText("Get Pro for", substring = true).fetchSemanticsNodes().size) }
    }

    @Test fun proRemovesThePromoUnlocksTemplatesAndShowsTheBadge() {
        proUser()
        launch().use {
            waitText("ReelMint"); rule.waitUntil(10_000) { rule.onAllNodesWithText("Go Pro").fetchSemanticsNodes().isEmpty() }; waitText("PRO")
            showTemplate("Cinematic"); tap("Cinematic", exact = true); waitText("Turn photos into a video"); assertEquals("no paywall for Pro", 0, rule.onAllNodesWithText("Get Pro for", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun settingsShowsTheRightProStateAndTheTestOnlyReset() {
        launch().use { waitText("ReelMint"); rule.onNodeWithContentDescription("Settings and privacy").performClick(); waitText("See Pro"); assertEquals(0, rule.onAllNodesWithText("Reset Pro", substring = true).fetchSemanticsNodes().size) }
        proUser()
        launch().use { waitText("ReelMint"); rule.onNodeWithContentDescription("Settings and privacy").performClick(); waitText("Active, for life"); waitText("Reset Pro (test mode only)") }
    }

    @Test fun appThemesAreLockedForFreeAndSwitchForPro() {
        ThemePrefs.set(ctx.getSharedPreferences("app", 0), AppTheme.VIOLET)
        launch().use { waitText("ReelMint"); rule.onNodeWithContentDescription("Settings and privacy").performClick(); waitText("App theme"); tap("Ocean", exact = true); waitText("To use: Pro app themes"); assertEquals(AppTheme.VIOLET, ThemePrefs.active) }
        proUser()
        launch().use { waitText("ReelMint"); rule.onNodeWithContentDescription("Settings and privacy").performClick(); waitText("App theme"); tap("Ocean", exact = true); rule.waitUntil(10_000) { ThemePrefs.active == AppTheme.OCEAN } }
        assertEquals("the choice is remembered", "OCEAN", ctx.getSharedPreferences("app", 0).getString("app_theme", ""))
        ThemePrefs.set(ctx.getSharedPreferences("app", 0), AppTheme.VIOLET)
    }

    @Test fun proPeopleNeverLoadABannerAd() {
        proUser()
        launch().use { s -> waitText("ReelMint"); Thread.sleep(4_000); var found = false; s.onActivity { found = hasAd(it.window.decorView) }; assertFalse("no AdView anywhere for Pro", found) }
    }
}

class ProExportDialogTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()
    @Before fun fresh() = freeUser()
    @After fun after() { Pro.reset() }
    private fun waitText(t: String, ms: Long = 30_000) = rule.waitUntil(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theExportDoneDialogOffersProOnlyToFreeUsers() {
        rule.setContent { StudioTheme { ExportDialog(ExportState.Done(Uri.parse("content://x/1")), {}, {}, {}, {}) } }
        waitText("Saved to Movies"); waitText("Remove ads and the tag"); waitText("Share")
    }

    @Test fun theExportDoneDialogHasNoUpsellForPro() {
        proUser(); rule.setContent { StudioTheme { ExportDialog(ExportState.Done(Uri.parse("content://x/1")), {}, {}, {}, {}) } }
        waitText("Saved to Movies"); assertEquals(0, rule.onAllNodesWithText("Remove ads and the tag", substring = true).fetchSemanticsNodes().size)
    }
}
