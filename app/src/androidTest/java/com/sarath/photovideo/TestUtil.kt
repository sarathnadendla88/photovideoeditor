package com.sarath.photovideo

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Fixtures live in the app's external files dir; push them with adb before running (see /tmp runtest.sh / README "Testing"). */
object TestUtil {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = ctx.getExternalFilesDir(null)!!
    val video: Uri get() = Uri.fromFile(File(dir, "test_av.mp4"))
    val tone: Uri get() = Uri.fromFile(File(dir, "test_tone.wav"))
    val photos: List<Uri> get() = listOf("p0.jpg", "p1.jpg", "p2.jpg").map { Uri.fromFile(File(dir, it)) }

    data class Tracks(val videoMs: Long, val audioMs: Long, val w: Int, val h: Int, val audioMime: String)

    /** Length of each track = timestamp of its last sample. */
    fun measure(uri: Uri): Tracks {
        val ex = MediaExtractor().also { it.setDataSource(ctx, uri, null) }
        var v = -1L; var a = -1L; var w = 0; var h = 0; var am = ""
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            ex.selectTrack(i); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var last = 0L
            while (ex.sampleTrackIndex == i) { last = maxOf(last, ex.sampleTime); if (!ex.advance()) break }
            ex.unselectTrack(i)
            if (mime.startsWith("video")) {
                v = last / 1000; w = f.getInteger(MediaFormat.KEY_WIDTH); h = f.getInteger(MediaFormat.KEY_HEIGHT)
                // Portrait output is stored as a landscape frame with a rotation flag; report the size players show.
                val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            }
            if (mime.startsWith("audio")) { a = last / 1000; am = mime }
        }
        ex.release(); return Tracks(v, a, w, h, am)
    }

    /** Newest video this app saved to Movies/PhotoVideoStudio. */
    fun latestExport(): Uri? = latest(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/PhotoVideoStudio")
    fun latestPhoto(): Uri? = latest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PhotoVideoStudio")

    private fun latest(base: Uri, path: String): Uri? =
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
            ?.use { if (it.moveToFirst()) android.content.ContentUris.withAppendedId(base, it.getLong(0)) else null }

    fun countIn(base: Uri, path: String): Int =
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"), null)?.use { it.count } ?: 0
}
