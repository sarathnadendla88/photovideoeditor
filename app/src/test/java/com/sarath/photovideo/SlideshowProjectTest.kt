package com.sarath.photovideo

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlideshowProjectTest {
    private fun uris(n: Int) = List(n) { Uri.parse("content://x/$it") }

    @Test fun totalDurationIsPhotosTimesPerPhoto() {
        val p = SlideshowProject(photos = uris(4), secondsPerPhoto = 2.5f)
        assertEquals(10_000L, p.totalDurationMs)
    }

    @Test fun perPhotoDurationHasFloor() {
        assertEquals(SlideshowProject.MIN_PHOTO_MS, SlideshowProject(secondsPerPhoto = 0f).perPhotoMs)
    }

    @Test fun moveReordersAndIgnoresBadIndexes() {
        val p = SlideshowProject(photos = uris(3))
        assertEquals(listOf(1, 2, 0), p.movePhoto(0, 2).photos.map { it.lastPathSegment!!.toInt() })
        assertEquals(p, p.movePhoto(0, 9))
    }

    @Test fun removeDropsPhoto() {
        assertEquals(2, SlideshowProject(photos = uris(3)).removePhoto(1).photos.size)
    }
}
