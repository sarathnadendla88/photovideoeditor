package com.sarath.photovideo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class BeatMathTest {
    private val rate = 22050

    /** A click track: a short decaying 1 kHz burst at every beat, plus faint noise. [offsetMs] shifts the first click. */
    private fun clicks(bpm: Double, seconds: Int, offsetMs: Int = 0, noise: Float = 0.02f): ShortArray {
        val n = rate * seconds; val out = ShortArray(n); val rnd = Random(3)
        for (i in 0 until n) out[i] = ((rnd.nextFloat() - 0.5f) * 2f * noise * 32767).toInt().toShort()
        val period = 60.0 / bpm; var t = offsetMs / 1000.0
        while (t < seconds) {
            val start = (t * rate).toInt()
            for (k in 0 until 900) { val i = start + k; if (i < n) out[i] = (out[i] + (sin(2 * PI * 1000 * k / rate) * 20000 * Math.exp(-k / 150.0)).toInt()).coerceIn(-32768, 32767).toShort() }
            t += period
        }
        return out
    }

    @Test fun findsTheTempoOfClickTracks() {
        for (bpm in listOf(80.0, 100.0, 120.0, 140.0, 160.0)) {
            val info = BeatMath.analyse(clicks(bpm, 20), rate) ?: fail("no beats for $bpm") as Nothing
            assertEquals("bpm for $bpm", bpm, info.bpm.toDouble(), 3.0)
        }
    }

    @Test fun beatTimesLandOnTheClicks() {
        val info = BeatMath.analyse(clicks(120.0, 20, offsetMs = 130), rate)!!
        // every detected beat should be within 50 ms of a real click (clicks are at 130 + k*500 ms)
        for (b in info.beatsMs.take(30)) {
            val k = Math.round((b - 130) / 500.0); val err = abs(b - (130 + k * 500))
            assertTrue("beat at $b ms is $err ms from the nearest click", err < 50)
        }
    }

    @Test fun silenceOrTooShortAudioGivesNoBeats() {
        assertNull(BeatMath.analyse(ShortArray(rate * 10), rate))
        assertNull(BeatMath.analyse(ShortArray(500), rate))
    }

    @Test fun durationsSnapToTheBeatGrid() {
        val beats = List(40) { 250L + it * 500L }                 // 120 BPM starting at 250 ms
        val every2 = BeatMath.snapDurations(beats, 4, 2)
        assertEquals(listOf(1250L, 1000L, 1000L, 1000L), every2)   // first photo also covers the 250 ms lead-in
        assertEquals(250L + 4 * 1000L, every2.sum())               // ends exactly on a beat
        // one beat is 500 ms, below the 600 ms minimum, so it uses 2 beats and stays on the grid
        assertEquals(listOf(1250L, 1000L, 1000L), BeatMath.snapDurations(beats, 3, 1))
        // with a 4 s-per-photo wish at 120 BPM: 8 beats
        assertEquals(listOf(4250L, 4000L), BeatMath.snapDurations(beats, 2, 8))
    }

    @Test fun veryFastBeatsNeverMakeSubMinimumPhotos() {
        val beats = List(100) { it * 200L }                          // 300 BPM
        val d = BeatMath.snapDurations(beats, 6, 1, minMs = 600L); assertTrue(d.all { it >= 600L }); assertTrue("stays on the 200 ms grid", d.drop(1).all { it % 200L == 0L })
    }

    @Test fun noBeatsFallsBackToTwoSeconds() { assertEquals(listOf(2000L, 2000L), BeatMath.snapDurations(emptyList(), 2, 1)) }
}
