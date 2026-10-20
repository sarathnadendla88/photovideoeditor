package com.sarath.photovideo

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

/** Records a voiceover to app-private storage (never leaves the device). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    val file = File(context.filesDir, "voiceover.m4a")
    val isRecording get() = recorder != null

    fun start(): Boolean = runCatching {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare(); r.start()
        recorder = r; startedAt = System.currentTimeMillis(); true
    }.getOrElse { recorder?.release(); recorder = null; false }

    /** Returns (uri, durationMs) or null if nothing usable was recorded. */
    fun stop(): Pair<Uri, Long>? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val d = System.currentTimeMillis() - startedAt
        return if (ok && d > 500 && file.length() > 0) Uri.fromFile(file) to d else null
    }

    fun cancel() { runCatching { recorder?.stop() }; recorder?.release(); recorder = null }
}
