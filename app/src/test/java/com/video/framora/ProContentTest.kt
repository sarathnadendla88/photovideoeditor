package com.video.framora

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The content that makes Pro worth buying: its counts, its integrity, and that the table on the pricing screen is honest. */
@RunWith(RobolectricTestRunner::class)
class ProContentTest {
    // ---- templates
    @Test fun proTemplatesAreFarMoreThanTheFreeOnes() {
        val pro = SlideshowTemplates.filter { it.pro }; val free = SlideshowTemplates.filter { !it.pro }
        assertEquals(5, free.size); assertEquals(14, pro.size)
        assertEquals("names are unique", SlideshowTemplates.size, SlideshowTemplates.map { it.name }.toSet().size)
        assertTrue(SlideshowTemplates.all { it.seconds in 0.8f..8f && it.tagline.isNotBlank() })
        assertTrue("free templates come first, so the list starts with what everyone can use", SlideshowTemplates.take(5).none { it.pro })
    }

    @Test fun proTemplatesShowOffTheProMotionsAndTransitions() {
        val proMotions = Motion.entries.filter { it.pro }.toSet()
        assertEquals("every Pro motion is used by some template", proMotions, SlideshowTemplates.map { it.motion }.toSet().intersect(proMotions))
        assertTrue("some templates carry their own transition", SlideshowTemplates.any { it.transition != Transition.NONE })
        assertTrue("and the three canvases are all covered", SlideshowTemplates.filter { it.pro }.map { it.canvas }.toSet().size == 3)
    }

    @Test fun applyingATemplateCopiesItsWholeStyle() {
        val t = SlideshowTemplates.first { it.name == "Memories" }
        val p = SlideshowProject().applyTemplate(t)
        assertEquals(t.seconds, p.secondsPerPhoto, 0f); assertEquals(t.canvas, p.canvas); assertEquals(t.motion, p.motion); assertEquals(t.transition, p.transition); assertEquals(t.title, p.title)
    }

    // ---- filters
    @Test fun eightProFiltersEachChangeTheColours() {
        val pro = PhotoFilter.entries.filter { it.pro }; assertEquals(8, pro.size)
        val identity = android.graphics.ColorMatrix().array
        val seen = HashSet<List<Float>>()
        for (f in pro) {
            val m = PhotoProcessor.filterMatrix(f) ?: fail("$f has no colour matrix") as Nothing
            assertEquals(20, m.array.size); assertFalse("$f must not be a no-op", m.array.contentEquals(identity))
            assertTrue("$f must differ from every other filter", seen.add(m.array.toList()))
        }
        PhotoFilter.entries.filter { !it.pro && it != PhotoFilter.NONE }.forEach { assertTrue(seen.add(PhotoProcessor.filterMatrix(it)!!.array.toList())) }   // and from the free ones
    }

    @Test fun proFiltersKeepColourChannelsInRange() {
        // A matrix that pushes white far past 255 or black far below 0 would clip, so each Pro filter keeps mid-grey sensible.
        for (f in PhotoFilter.entries.filter { it.pro }) {
            val a = PhotoProcessor.filterMatrix(f)!!.array
            for (row in 0..2) { val v = a[row * 5] * 128 + a[row * 5 + 1] * 128 + a[row * 5 + 2] * 128 + a[row * 5 + 4]; assertTrue("$f row $row maps mid-grey to $v", v in 40f..230f) }
        }
    }

    @Test fun videoLooksUseTheProFiltersAndAreLockedWithThem() {
        assertEquals(6, LookPresets.count { it.filter.pro }); assertEquals(7, LookPresets.count { !it.filter.pro })
        assertTrue(LookPresets.filter { it.filter.pro }.all { !Entitlements.lookPreset(it, false) && Entitlements.lookPreset(it, true) })
        val p = VideoProject().applyLook(LookPresets.first { it.name == "Golden hour" }); assertEquals(PhotoFilter.GOLDEN, p.filter)
    }

    // ---- collage
    @Test fun everyCollageLayoutTilesTheCanvasWithoutGapsOrOverlaps() {
        for (l in CollageLayout.entries) {
            var area = 0f
            for (c in l.cells) { assertTrue("${l.name} cell inside the canvas", c[0] >= 0f && c[1] >= 0f && c[2] <= 1f && c[3] <= 1f && c[0] < c[2] && c[1] < c[3]); area += (c[2] - c[0]) * (c[3] - c[1]) }
            assertEquals("${l.name} covers the whole canvas", 1f, area, 1e-4f)
            for (i in l.cells.indices) for (j in i + 1 until l.cells.size) {
                val a = l.cells[i]; val b = l.cells[j]
                val w = minOf(a[2], b[2]) - maxOf(a[0], b[0]); val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
                assertFalse("${l.name} cells $i and $j overlap", w > 1e-4f && h > 1e-4f)
            }
        }
    }

    @Test fun collageHasProLayoutsUpToNinePhotos() {
        assertEquals(6, CollageLayout.entries.count { it.pro }); assertEquals(8, CollageLayout.entries.count { !it.pro })
        assertEquals(9, CollageLayout.entries.maxOf { it.cells.size }); assertTrue(CollageLayout.entries.take(8).none { it.pro })
    }

    // ---- stickers
    @Test fun stickerPacksAreWellFormed() {
        val all = StickerPacks.all
        assertFalse(StickerPacks.classic.pro); assertEquals(8, StickerPacks.proPacks.size); assertEquals(16, StickerPacks.classic.emojis.size)
        assertTrue(all.all { it.emojis.size >= 16 && it.emojis.all { e -> e.isNotBlank() } })
        assertEquals("names are unique", all.size, all.map { it.name }.toSet().size)
        for (p in all) assertEquals("no repeated sticker inside ${p.name}", p.emojis.size, p.emojis.toSet().size)
        val flat = all.flatMap { it.emojis }; assertEquals("no sticker appears in two packs", flat.size, flat.toSet().size)
        assertTrue(Entitlements.stickerPack(StickerPacks.classic, false)); assertTrue(StickerPacks.proPacks.none { Entitlements.stickerPack(it, false) }); assertTrue(StickerPacks.proPacks.all { Entitlements.stickerPack(it, true) })
    }

    // ---- motions
    @Test fun proMotionsAreLockedForFreeUsers() {
        assertEquals(4, Motion.entries.count { it.pro }); assertEquals(setOf(Motion.NONE, Motion.KEN_BURNS, Motion.SLIDE, Motion.POP), Motion.entries.filter { !it.pro }.toSet())
        assertTrue(Motion.entries.all { Entitlements.motion(it, true) }); assertFalse(Entitlements.motion(Motion.PULSE, false))
    }

    // ---- GIF
    @Test fun gifDelayFollowsFpsAndSpeedButNeverBelowTheSafeMinimum() {
        assertEquals(10, GifSettings.delayCs(10, 1f)); assertEquals(8, GifSettings.delayCs(12, 1f)); assertEquals(5, GifSettings.delayCs(10, 2f)); assertEquals(20, GifSettings.delayCs(10, 0.5f))
        assertEquals(3, GifSettings.delayCs(20, 2f))   // 2.5 rounds up
        assertEquals("never below 2 cs, which players treat as 10", 2, GifSettings.delayCs(60, 2f))
        assertTrue(Entitlements.gifStyle(GifStyle.BOOMERANG, false)); assertFalse(Entitlements.gifStyle(GifStyle.REVERSE, false)); assertTrue(Entitlements.gifSpeed(1f, false)); assertFalse(Entitlements.gifSpeed(2f, false)); assertTrue(Entitlements.gifSpeed(0.5f, true))
    }

    // ---- the pricing table
    @Test fun thePricingTableReportsTheRealNumbers() {
        fun row(t: String) = Entitlements.table.first { it.title == t }
        assertEquals("5 templates", row("Slideshow templates").free); assertTrue(row("Slideshow templates").pro.startsWith("19 templates"))
        assertEquals("3 motions", row("Slideshow motions").free); assertTrue(row("Slideshow motions").pro.startsWith("7 motions"))
        assertEquals("11 filters", row("Filters").free); assertTrue(row("Filters").pro.startsWith("19 filters"))
        assertEquals("7 looks", row("One-tap video looks").free); assertEquals("13 looks", row("One-tap video looks").pro)
        assertTrue(row("Sticker packs").pro.startsWith("9 packs")); assertTrue(row("Collage layouts").pro.startsWith("14 layouts"))
        assertTrue(row("GIF maker").pro.contains("Reverse"))
        assertTrue("every row says something different for free and Pro", Entitlements.table.all { it.free != it.pro })
    }
}
