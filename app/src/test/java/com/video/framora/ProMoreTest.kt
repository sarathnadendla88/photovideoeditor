package com.video.framora

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The Robot and Echo voices, checked on synthetic audio where the right answer is known exactly. */
class VoiceFxProcessorTest {
    private fun run(p: BaseAudioProcessor, input: ShortArray, rate: Int = 8000, channels: Int = 1): ShortArray {
        p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); p.flush()
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder()); input.forEach { buf.putShort(it) }; buf.flip()
        p.queueInput(buf); val out = p.output.order(ByteOrder.nativeOrder()); return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test fun robotChopsALevelSignalIntoA70HzTone() {
        val y = run(RobotAudioProcessor(freqHz = 70f, depth = 0.6f), ShortArray(8000) { 10_000 })
        assertEquals(8000, y.size)
        assertEquals("peak is the original level", 10_000.0, y.max().toDouble(), 150.0); assertEquals("trough goes below zero (ring modulation)", -2_000.0, y.min().toDouble(), 150.0)
        assertEquals("mean is the (1 - depth) share", 4_000.0, y.average(), 150.0)
        var crossings = 0; for (i in 1 until y.size) if ((y[i - 1] - 4000) * (y[i] - 4000) < 0) crossings++
        assertEquals("70 Hz → 140 crossings a second", 140.0, crossings.toDouble(), 4.0)
    }

    @Test fun robotKeepsSilenceSilentAndStereoChannelsTogether() {
        assertTrue(run(RobotAudioProcessor(), ShortArray(4000)).all { it.toInt() == 0 })
        val st = ShortArray(2000) { 6000 }; val o = run(RobotAudioProcessor(), st, channels = 2)
        for (i in 0 until 1000) assertEquals("left and right stay identical", o[2 * i], o[2 * i + 1])
    }

    @Test fun echoRepeatsAnImpulseAtTheDelayAndFadesByTheFeedback() {
        val x = ShortArray(8000); x[0] = 10_000
        val y = run(EchoAudioProcessor(delayMs = 280, feedback = 0.45f), x)           // 280 ms at 8 kHz = 2240 samples
        assertEquals(10_000, y[0].toInt()); assertEquals(4_500.0, y[2240].toDouble(), 2.0); assertEquals(2_025.0, y[4480].toDouble(), 3.0); assertEquals(911.0, y[6720].toDouble(), 4.0)
        for (i in listOf(1, 100, 2239, 2241, 3000, 4479, 4481, 7000)) assertEquals("silence between echoes at $i", 0, y[i].toInt())
        assertEquals("same length as the input", x.size, y.size)
    }

    @Test fun echoNeverWrapsAroundOnLoudAudio() {
        val y = run(EchoAudioProcessor(), ShortArray(9000) { 30_000 })
        assertTrue("clipped, not wrapped to negative", y.all { it >= 0 }); assertEquals(32_767, y.max().toInt())
    }

    @Test fun flushForgetsTheEchoSoACutStartsClean() {
        val p = EchoAudioProcessor(delayMs = 100, feedback = 0.5f); val x = ShortArray(2000) { if (it == 0) 10_000 else 0 }
        val a = run(p, x); val b = run(p, x)                                            // run() flushes before each pass
        assertArrayEquals(a, b)
    }

    @Test fun nonPcm16IsRejectedForBothEffects() {
        for (p in listOf<BaseAudioProcessor>(RobotAudioProcessor(), EchoAudioProcessor())) assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) { p.configure(AudioProcessor.AudioFormat(8000, 1, C.ENCODING_PCM_FLOAT)) }
    }

    @Test fun voiceEffectsMapToTheRightProcessors() {
        assertTrue(voiceProcessors(VoiceFx.NORMAL).isEmpty())
        assertTrue(voiceProcessors(VoiceFx.ROBOT).single() is RobotAudioProcessor); assertTrue(voiceProcessors(VoiceFx.ECHO).single() is EchoAudioProcessor)
        assertEquals("Alien = pitch up + wobble", 2, voiceProcessors(VoiceFx.ALIEN).size); assertTrue(voiceProcessors(VoiceFx.CHIPMUNK).single() is androidx.media3.common.audio.SonicAudioProcessor)
    }
}

@RunWith(RobolectricTestRunner::class)
class ProMoreTest {
    private fun clip(speed: Float = 1f) = VideoClip(Uri.parse("content://x/1"), "c", 4000, speed = speed)

    @Test fun theNewProRulesAreExactlyWhatTheSheetPromises() {
        assertEquals(5, TextFont.entries.count { !it.pro }); assertEquals(12, TextFont.entries.size)
        assertEquals(listOf(FrameStyle.PLAIN), FrameStyle.entries.filter { !it.pro }); assertEquals(5, FrameStyle.entries.size)
        assertEquals(5, CaptionStyle.entries.count { !it.pro }); assertEquals(8, CaptionStyle.entries.size)
        assertEquals(setOf(VoiceFx.NORMAL, VoiceFx.HIGH, VoiceFx.DEEP, VoiceFx.CHIPMUNK, VoiceFx.GIANT), VoiceFx.entries.filter { !it.pro }.toSet()); assertEquals(8, VoiceFx.entries.size)
        assertEquals(setOf(Transition.NONE, Transition.EDGES, Transition.DIP), Transition.entries.filter { !it.pro }.toSet())
        for (s in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)) assertTrue("$s× is free", Entitlements.speed(s, false))
        for (s in listOf(0.1f, 5f, 8f)) { assertFalse("$s× is Pro", Entitlements.speed(s, false)); assertTrue(Entitlements.speed(s, true)) }
        assertEquals(1, AppTheme.entries.count { !it.pro }); assertEquals(6, AppTheme.entries.size)
    }

    @Test fun everyNewProItemIsLockedForFreeAndOpenForPro() {
        assertTrue(TextFont.entries.filter { it.pro }.all { !Entitlements.font(it, false) && Entitlements.font(it, true) })
        assertTrue(FrameStyle.entries.filter { it.pro }.all { !Entitlements.frame(it, false) && Entitlements.frame(it, true) })
        assertTrue(CaptionStyle.entries.filter { it.pro }.all { !Entitlements.captionStyle(it, false) })
        assertTrue(VoiceFx.entries.filter { it.pro }.all { !Entitlements.voiceFx(it, false) && Entitlements.voiceFx(it, true) })
        assertFalse(Entitlements.transition(Transition.FLASH, false)); assertTrue(Entitlements.transition(Transition.FLASH, true))
        assertTrue(AppTheme.entries.filter { it.pro }.all { !Entitlements.theme(it, false) && Entitlements.theme(it, true) })
    }

    @Test fun aFreeExportQuietlyBecomesTheFreeEquivalent() {
        val p = VideoProject(clips = listOf(clip(8f), clip(0.1f), clip(2f)), quality = ExportQuality.FHD, originalVoice = VoiceFx.ROBOT,
            voiceover = Voiceover(Uri.parse("content://v"), 3000, VoiceFx.ECHO), transition = Transition.FLASH, captionStyle = CaptionStyle.OUTLINE,
            filter = PhotoFilter.CINEMA, logoUri = "file:///logo.png")
        val f = p.forExport(false)
        assertEquals(listOf(3f, 0.25f, 2f), f.clips.map { it.speed }); assertEquals(VoiceFx.NORMAL, f.originalVoice); assertEquals(VoiceFx.NORMAL, f.voiceover!!.fx)
        assertEquals(Transition.DIP, f.transition); assertEquals(CaptionStyle.BOXED, f.captionStyle); assertEquals(PhotoFilter.NONE, f.filter); assertNull(f.logoUri)
        assertEquals(ExportQuality.HD, f.quality); assertTrue("free exports carry the tag", f.brandTag)
        val pro = p.forExport(true)
        assertEquals(listOf(8f, 0.1f, 2f), pro.clips.map { it.speed }); assertEquals(VoiceFx.ROBOT, pro.originalVoice); assertEquals(VoiceFx.ECHO, pro.voiceover!!.fx); assertEquals(Transition.FLASH, pro.transition)
        assertEquals(CaptionStyle.OUTLINE, pro.captionStyle); assertEquals(PhotoFilter.CINEMA, pro.filter); assertEquals("file:///logo.png", pro.logoUri); assertEquals(ExportQuality.FHD, pro.quality); assertFalse(pro.brandTag)
    }

    @Test fun aFreeSlideshowExportDropsProMotionTransitionAndLogo() {
        val p = SlideshowProject(motion = Motion.PULSE, transition = Transition.FLASH, quality = ExportQuality.FHD, logoUri = "file:///l.png")
        val f = p.forExport(false); assertEquals(Motion.KEN_BURNS, f.motion); assertEquals(Transition.DIP, f.transition); assertEquals(ExportQuality.HD, f.quality); assertNull(f.logoUri); assertTrue(f.brandTag)
        val g = p.forExport(true); assertEquals(Motion.PULSE, g.motion); assertEquals(Transition.FLASH, g.transition); assertEquals("file:///l.png", g.logoUri)
    }

    @Test fun aFreePhotoSaveDropsProFilterEffectFrameAndFont() {
        val e = PhotoEdit(filter = PhotoFilter.GOLDEN, effect = PhotoEffect.CARTOON, frameStyle = FrameStyle.POLAROID, texts = listOf(TextLayer("hi", font = TextFont.TYPEWRITER), TextLayer("yo", font = TextFont.SERIF)))
        val f = e.forSave(false)
        assertEquals(PhotoFilter.NONE, f.filter); assertEquals(PhotoEffect.NONE, f.effect); assertEquals(FrameStyle.PLAIN, f.frameStyle); assertEquals(listOf(TextFont.SANS, TextFont.SERIF), f.texts.map { it.font })
        assertEquals("Pro keeps everything", e, e.forSave(true))
    }

    @Test fun anEditWithNothingProPassesThroughUnchanged() {
        val e = PhotoEdit(filter = PhotoFilter.SEPIA, effect = PhotoEffect.SKETCH, texts = listOf(TextLayer("ok"))); assertEquals(e, e.forSave(false))
        val v = VideoProject(clips = listOf(clip()), transition = Transition.DIP, captionStyle = CaptionStyle.NEON); val f = v.forExport(false)
        assertEquals(v.clips, f.clips); assertEquals(v.transition, f.transition); assertEquals(v.captionStyle, f.captionStyle)
    }

    @Test fun appThemesLookDifferentAndFreeAlwaysGetsViolet() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication(); Pro.init(ctx); Pro.store.reset(); Pro.init(ctx); assertFalse(Pro.isPro)
        assertEquals("every theme has its own accent", AppTheme.entries.size, AppTheme.entries.map { it.darkPrimary }.toSet().size)
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.hero }.toSet().size); assertTrue(AppTheme.entries.all { it.hero.size == 3 && it.aurora.size == 3 })
        ThemePrefs.current = AppTheme.OCEAN; assertEquals("a free user is shown Violet even if Ocean was saved", AppTheme.VIOLET, ThemePrefs.active)
        Pro.grant("pay_THEMETEST001"); assertEquals(AppTheme.OCEAN, ThemePrefs.active)
        Pro.store.reset(); Pro.init(ctx); ThemePrefs.current = AppTheme.VIOLET
    }

    @Test fun theChoiceIsRememberedAndBadValuesFallBackToViolet() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("theme_test", 0)
        ThemePrefs.set(prefs, AppTheme.ROSE); ThemePrefs.current = AppTheme.VIOLET; ThemePrefs.load(prefs); assertEquals(AppTheme.ROSE, ThemePrefs.current)
        prefs.edit().putString("app_theme", "NOT_A_THEME").commit(); ThemePrefs.load(prefs); assertEquals(AppTheme.VIOLET, ThemePrefs.current)
    }

    @Test fun theNewPricingRowsExistAndSayDifferentThingsForFreeAndPro() {
        fun row(t: String) = Entitlements.table.first { it.title == t }
        assertEquals("5 fonts", row("Photo text fonts").free); assertTrue(row("Photo text fonts").pro.startsWith("12 fonts"))
        assertTrue(row("Photo frames").pro.startsWith("5 styles")); assertEquals("5 styles", row("Video caption styles").free); assertTrue(row("Video caption styles").pro.startsWith("8 styles"))
        assertEquals("4 effects", row("Voice effects").free); assertTrue(row("Voice effects").pro.startsWith("7 effects"))
        assertEquals("2 transitions", row("Slideshow transitions").free); assertTrue(row("Slideshow transitions").pro.startsWith("3 transitions"))
        assertTrue(row("Clip speed").pro.contains("8×")); assertTrue(row("Your own logo").pro.contains("GIF")); assertTrue(row("App themes").pro.startsWith("6"))
        assertTrue(Entitlements.table.all { it.free != it.pro })
    }

    @Test fun logoCornersAnchorToTheirOwnCornerOnly() {
        assertEquals(4, LogoCorner.entries.size)
        for (c in LogoCorner.entries) { assertEquals(0.94f, Math.abs(c.ax), 0f); assertEquals(0.94f, Math.abs(c.ay), 0f) }
        assertEquals(4, LogoCorner.entries.map { it.ax to it.ay }.toSet().size)
    }
}
