package com.sarath.photovideo

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditingLogicTest {
    private val uri = Uri.parse("content://x/1")
    private fun clip(d: Long = 10_000) = VideoClip(uri, "c", d)

    @Test fun speedShortensOutput() {
        val p = VideoProject(clips = listOf(clip())).speed(0, 2f)
        assertEquals(5_000L, p.totalMs)
        assertEquals(40_000L, VideoProject(clips = listOf(clip())).speed(0, 0.25f).totalMs)
    }

    @Test fun splitProducesTwoClipsCoveringSameRange() {
        val p = VideoProject(clips = listOf(clip())).split(0, 4_000)
        assertEquals(2, p.clips.size)
        assertEquals(4_000L, p.clips[0].lengthMs); assertEquals(6_000L, p.clips[1].lengthMs)
        assertEquals(10_000L, p.totalMs)
    }

    @Test fun splitNearEdgesIsIgnored() {
        val p = VideoProject(clips = listOf(clip()))
        assertEquals(p, p.split(0, 100)); assertEquals(p, p.split(0, 9_900))
    }

    @Test fun trimIsClampedToSource() {
        val c = VideoProject(clips = listOf(clip())).trim(0, -5, 99_999).clips[0]
        assertEquals(0L, c.trimStartMs); assertEquals(10_000L, c.trimEndMs)
    }

    @Test fun cannotExportTooShortOrEmpty() {
        assertFalse(VideoProject().canExport)
        assertFalse(VideoProject(clips = listOf(clip().copy(trimEndMs = 100))).canExport)
    }

    @Test fun canvasSizeIsEvenAndScalesWithQuality() {
        for (c in CanvasPreset.entries) for (q in ExportQuality.entries) {
            val (w, h) = c.size(q); assertTrue(w % 2 == 0 && h % 2 == 0)
        }
        assertEquals(720 to 1280, CanvasPreset.PORTRAIT.size(ExportQuality.HD))
    }

    @Test fun templateAppliesItsSettings() {
        val t = SlideshowTemplates.first { it.name == "Birthday" }
        val p = SlideshowProject().applyTemplate(t)
        assertEquals(t.motion, p.motion); assertEquals(t.title, p.title); assertEquals(2.5f, p.secondsPerPhoto)
    }

    @Test fun undoRedoStack() {
        val s = UndoStack(PhotoEdit())
        s.push(s.current.copy(brightness = 0.2f)); s.push(s.current.copy(contrast = 1.2f))
        s.undo(); assertEquals(0.2f, s.current.brightness); assertEquals(1f, s.current.contrast)
        s.redo(); assertEquals(1.2f, s.current.contrast)
        s.undo(); s.push(s.current.copy(warmth = 0.5f)); assertFalse(s.canRedo)
    }
}
