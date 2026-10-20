package com.studio.photovideo

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class TrendFeaturesTest {
    @Test fun noRampKeepsConstantSpeed() {
        assertEquals(5_000L, rampedOutputMs(10_000, SpeedRamp.NONE, 2f))
    }

    @Test fun heroRampLengthensClip() {
        // 35% at 1x, 30% at 0.3x, 35% at 1x of 10 s = 3.5 + 10 + 3.5 = 17 s
        assertEquals(17_000.0, rampedOutputMs(10_000, SpeedRamp.HERO, 1f).toDouble(), 5.0)
    }

    @Test fun rampSpeedProviderFollowsSegments() {
        val p = RampSpeedProvider(10_000_000, SpeedRamp.HERO, 1f)
        assertEquals(1f, p.getSpeed(1_000_000), 0.001f)
        assertEquals(0.3f, p.getSpeed(5_000_000), 0.001f)
        assertEquals(1f, p.getSpeed(9_000_000), 0.001f)
        assertEquals(3_500_000L, p.getNextSpeedChangeTimeUs(0))
        assertEquals(C.TIME_UNSET, p.getNextSpeedChangeTimeUs(9_000_000))
    }

    @Test fun baseSpeedMultipliesRamp() {
        assertEquals(0.6f, RampSpeedProvider(10_000_000, SpeedRamp.HERO, 2f).getSpeed(5_000_000), 0.001f)
    }

    @Test fun platformPresetSetsCanvasAndQuality() {
        val p = VideoProject().applyPlatform(Platform.POST)
        assertEquals(CanvasPreset.SQUARE, p.canvas); assertEquals(ExportQuality.FHD, p.quality)
    }

    @Test fun lookPresetSetsColourFields() {
        val l = LookPresets.first { it.name == "Cinematic" }
        val p = VideoProject().applyLook(l)
        assertEquals(l.filter, p.filter); assertEquals(l.contrast, p.contrast, 0f)
    }

    @Test fun photoLookRoundTripKeepsOnlyColourFields() {
        val e = PhotoEdit(brightness = 0.1f, filter = PhotoFilter.SEPIA, texts = listOf(TextLayer("hi")), rotation = 90)
        val back = e.toLook("x").toEdit()
        assertEquals(PhotoFilter.SEPIA, back.filter); assertEquals(0.1f, back.brightness, 0f)
        assertTrue(back.texts.isEmpty()); assertEquals(0, back.rotation)
    }
}

class CropRectTest {
    @Test fun freeIsFullImage() { assertTrue(CropRect.forRatio(null, 1.5f).isFull) }

    @Test fun squareFromLandscapeIsCentredAndSquare() {
        val c = CropRect.forRatio(1f, 1.5f)           // 3:2 image -> 1:1
        assertEquals(1f, (c.r - c.l) * 1.5f / (c.b - c.t), 0.001f)
        assertEquals(0f, c.t, 0f); assertEquals(1f, c.b, 0f)
        assertEquals(0.5f, (c.l + c.r) / 2, 0.001f)
    }

    @Test fun tallRatioFromLandscapeKeepsFullHeight() {
        val c = CropRect.forRatio(9f / 16f, 1.5f)
        assertEquals(9f / 16f, (c.r - c.l) * 1.5f / (c.b - c.t), 0.001f)
    }

    @Test fun wideRatioFromPortraitKeepsFullWidth() {
        val c = CropRect.forRatio(16f / 9f, 0.75f)
        assertEquals(0f, c.l, 0f); assertEquals(1f, c.r, 0f)
        assertEquals(16f / 9f, (c.r - c.l) * 0.75f / (c.b - c.t), 0.001f)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class OverlayLayerTest {
    @Test fun movingACaptionChangesOnlyItsPosition() {
        val p = VideoProject(captions = listOf(Caption("a", 0, 1000), Caption("b", 0, 1000)))
        val m = p.moveOverlay(OverlaySel(0, 1), 0.25f, 0.5f)
        assertEquals(p.captions[0], m.captions[0])
        assertEquals(0.25f, m.captions[1].x, 0.001f); assertEquals(-0.2f, m.captions[1].y, 0.001f)
    }

    @Test fun moveIsClampedToTheFrame() {
        val p = VideoProject(stickers = listOf(VideoSticker("🔥", x = 0.9f, y = 0.9f)))
        val m = p.moveOverlay(OverlaySel(1, 0), 5f, 5f)
        assertEquals(1f, m.stickers[0].x, 0f); assertEquals(1f, m.stickers[0].y, 0f)
    }

    @Test fun photoCanHoldManyTextAndImageLayers() {
        val e = PhotoEdit(texts = listOf(TextLayer("a"), TextLayer("b", font = TextFont.SERIF)), images = listOf(ImageLayer("content://x")))
        assertEquals(2, e.texts.size); assertEquals(TextFont.SERIF, e.texts[1].font); assertEquals(1, e.images.size)
    }

    @Test fun dragIsOneUndoStep() {
        val s = UndoStack(PhotoEdit(texts = listOf(TextLayer("a", x = 0.5f))))
        val base = s.current
        repeat(5) { i -> s.live(s.current.copy(texts = listOf(s.current.texts[0].copy(x = 0.5f + 0.05f * (i + 1))))) }
        s.commitFrom(base)
        assertEquals(0.75f, s.current.texts[0].x, 0.001f)
        s.undo(); assertEquals(0.5f, s.current.texts[0].x, 0.001f); assertFalse(s.canUndo)
    }

    @Test fun slideshowRemovingAPhotoDropsItsCaption() {
        val u = android.net.Uri.parse("content://x/1")
        val p = SlideshowProject(photos = listOf(u), slideCaptions = mapOf(u.toString() to "hi")).removePhoto(0)
        assertTrue(p.slideCaptions.isEmpty())
    }
}

class EdgeFitTest {
    @Test fun anchorKeepsItemInsideFrame() {
        // 216 px wide picture in a 720 px frame at the right edge: centre bias must stop 108 px short of the edge
        val a = fitAnchor(1f, 216, 720)
        assertEquals(1f - 216f / 720f, a, 0.0001f)
        assertEquals(-a, fitAnchor(-1f, 216, 720), 0.0001f)
        assertEquals(0f, fitAnchor(0f, 216, 720), 0f)
    }

    @Test fun oversizedItemIsCentredNotNegative() { assertEquals(0f, fitAnchor(1f, 900, 720), 0f) }

    @Test fun photoLayerIsClampedInside() {
        assertEquals(50f, PhotoProcessor.clampCentre(0f, 100f, 1000f), 0f)
        assertEquals(950f, PhotoProcessor.clampCentre(1000f, 100f, 1000f), 0f)
        assertEquals(400f, PhotoProcessor.clampCentre(400f, 100f, 1000f), 0f)
        assertEquals(500f, PhotoProcessor.clampCentre(10f, 1200f, 1000f), 0f)
    }
}
