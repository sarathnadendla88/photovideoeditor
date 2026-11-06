package com.video.framora

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Beat positions found in a song, in ms measured from the start of the analysed region. */
data class BeatInfo(val bpm: Float, val beatsMs: List<Long>) {
    val isUsable get() = bpm > 0f && beatsMs.size >= 4
}

/** Pure maths (no Android) so it can be unit-tested with synthetic click tracks. */
object BeatMath {
    const val HOP = 512

    /** Onset strength: positive change of short-time energy (a cheap spectral-flux stand-in that works well for percussive music). */
    fun onsetEnvelope(pcm: ShortArray, sampleRate: Int): FloatArray {
        val frames = pcm.size / HOP
        if (frames < 8) return FloatArray(0)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val base = f * HOP
            for (i in 0 until HOP) { val v = pcm[base + i] / 32768.0; sum += v * v }
            energy[f] = Math.sqrt(sum / HOP).toFloat()
        }
        val env = FloatArray(frames)
        for (f in 1 until frames) env[f] = max(0f, energy[f] - energy[f - 1])
        // normalise so the threshold logic is level independent
        val peak = env.max()
        if (peak > 0f) for (i in env.indices) env[i] /= peak
        return env
    }

    /** Tempo from autocorrelation of the (lightly smoothed) onset envelope, searched between [minBpm] and [maxBpm]. Returns the beat period in frames and the BPM. */
    fun estimateTempo(raw: FloatArray, sampleRate: Int, minBpm: Int = 70, maxBpm: Int = 180): Pair<Float, Float>? {
        if (raw.size < 16) return null
        // smoothing lets a beat period that falls between two frames still correlate strongly
        val env = FloatArray(raw.size) { i -> 0.25f * raw[max(0, i - 1)] + 0.5f * raw[i] + 0.25f * raw[min(raw.size - 1, i + 1)] }
        val hopSec = HOP.toFloat() / sampleRate
        val minLag = max(2, (60f / maxBpm / hopSec).roundToInt()); val maxLag = min(env.size / 2, (60f / minBpm / hopSec).roundToInt())
        if (maxLag <= minLag) return null
        val top = min(env.size - 1, maxLag * 2 + 2)
        val ac = FloatArray(top + 1)
        for (lag in 1..top) { var sum = 0f; for (i in lag until env.size) sum += env[i] * env[i - lag]; ac[lag] = sum }
        var bestLag = -1; var best = 0f
        for (lag in minLag..maxLag) if (ac[lag] > best) { best = ac[lag]; bestLag = lag }
        if (bestLag < 0 || best <= 0f) return null
        // The strongest lag is often a multiple of the real beat; step down while the half lag is nearly as strong.
        var guard = 0
        while (guard++ < 3) {
            val half = bestLag / 2
            if (half < minLag) break
            val cand = (max(minLag, half - 1)..min(maxLag, half + 1)).maxByOrNull { ac[it] } ?: break
            if (ac[cand] >= 0.6f * ac[bestLag]) bestLag = cand else break
        }
        val y0 = ac[max(1, bestLag - 1)]; val y1 = ac[bestLag]; val y2 = ac[min(top, bestLag + 1)]
        val denom = y0 - 2 * y1 + y2
        val shift = if (abs(denom) > 1e-6f) 0.5f * (y0 - y2) / denom else 0f
        val lag = bestLag + shift.coerceIn(-0.5f, 0.5f)
        return lag to (60f / (lag * hopSec))
    }

    /** Lay a beat grid over the track: choose the phase that lines up with the most onset energy. */
    fun beatGrid(env: FloatArray, periodFrames: Float, sampleRate: Int): List<Long> {
        val hopMs = HOP * 1000.0 / sampleRate
        var bestPhase = 0f; var best = -1f
        val steps = max(1, periodFrames.roundToInt())
        for (p in 0 until steps) {
            var s = 0f; var t = p.toFloat()
            while (t < env.size) { s += env[t.roundToInt().coerceIn(0, env.size - 1)]; t += periodFrames }
            if (s > best) { best = s; bestPhase = p.toFloat() }
        }
        val out = ArrayList<Long>(); var t = bestPhase
        while (t < env.size) { out += (t * hopMs).toLong(); t += periodFrames }
        return out
    }

    fun analyse(pcm: ShortArray, sampleRate: Int): BeatInfo? {
        val env = onsetEnvelope(pcm, sampleRate)
        val (period, bpm) = estimateTempo(env, sampleRate) ?: return null
        val grid = beatGrid(env, period, sampleRate)
        return BeatInfo(bpm, grid).takeIf { it.isUsable }
    }

    /**
     * Photo durations that cut on the beat. Boundaries are every [beatsPerPhoto] beats starting at the first beat;
     * the first photo also covers the lead-in before that beat so the slideshow still starts at 0. Each photo gets at
     * least [minMs]. The sum is the time up to the last boundary reached after [count] photos.
     */
    fun snapDurations(beatsMs: List<Long>, count: Int, beatsPerPhoto: Int, minMs: Long = 600L): List<Long> {
        if (count <= 0) return emptyList()
        if (beatsMs.size < 2) return List(count) { 2000L }
        val period = (beatsMs.last() - beatsMs.first()).toDouble() / (beatsMs.size - 1)
        // Photos shorter than minMs are unwatchable, so use more beats per photo instead of leaving the grid.
        val perPhoto = max(beatsPerPhoto.coerceAtLeast(1), Math.ceil(minMs / period).toInt())
        val step = period * perPhoto
        val t0 = beatsMs.first().toDouble()
        val out = ArrayList<Long>(count); var prevEnd = 0L
        for (i in 1..count) { val end = (t0 + step * i).toLong(); out += end - prevEnd; prevEnd = end }
        return out
    }
}

/** Decodes the audio of [uri] from [startMs] for up to [maxMs] to mono 16-bit PCM and analyses it. Run off the main thread. */
object BeatAnalyzer {
    fun analyse(ctx: Context, uri: Uri, startMs: Long, maxMs: Long = 90_000L): BeatInfo? = runCatching {
        val ex = MediaExtractor().also { it.setDataSource(ctx, uri, null) }
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val pcm = ArrayList<ShortArray>(); var total = 0
            val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false
            val startUs = ex.sampleTime.coerceAtLeast(0)
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime - startUs > maxMs * 1000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!; out.position(info.offset); out.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2); out.asShortBuffer().get(shorts)
                    // downmix to mono
                    val mono = ShortArray(shorts.size / max(1, channels)) { f -> var s = 0; for (c in 0 until channels) s += shorts[f * channels + c]; (s / channels).toShort() }
                    pcm += mono; total += mono.size
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            codec.stop(); codec.release()
            val all = ShortArray(total); var p = 0; for (a in pcm) { System.arraycopy(a, 0, all, p, a.size); p += a.size }
            BeatMath.analyse(all, rate)
        } finally { ex.release() }
    }.getOrNull()
}
