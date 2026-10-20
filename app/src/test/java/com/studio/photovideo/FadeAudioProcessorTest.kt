package com.studio.photovideo

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FadeAudioProcessorTest {
    private fun run(inMs: Long, outMs: Long, itemMs: Long, frames: Int): ShortArray {
        val p = FadeAudioProcessor(inMs, outMs, itemMs)
        p.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)); p.flush()
        val inBuf = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        repeat(frames) { inBuf.putShort(10_000) }; inBuf.flip()
        p.queueInput(inBuf)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test fun fadeInRampsFromSilence() {
        val s = run(inMs = 100, outMs = 0, itemMs = 400, frames = 400)
        assertEquals(0, s[0].toInt()); assertTrue(s[50] in 4_000..6_000); assertEquals(10_000, s[200].toInt())
    }

    @Test fun fadeOutEndsInSilence() {
        val s = run(inMs = 0, outMs = 100, itemMs = 400, frames = 400)
        assertEquals(10_000, s[100].toInt()); assertTrue(s[350] in 4_000..6_000); assertTrue(s[399] < 200)
    }

    @Test fun noFadeLeavesAudioUntouched() {
        assertTrue(run(0, 0, 400, 400).all { it.toInt() == 10_000 })
    }
}
