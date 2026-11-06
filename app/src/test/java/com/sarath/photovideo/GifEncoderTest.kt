package com.sarath.photovideo

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.random.Random

/** Decodes GIFs with an independent reference decoder (straight from the GIF89a spec), so encoder bugs cannot hide. */
class GifEncoderTest {
    data class Gif(val w: Int, val h: Int, val loop: Boolean, val delays: List<Int>, val palette: IntArray, val frames: List<IntArray>)  // frames = palette indices

    private fun decode(b: ByteArray): Gif {
        var p = 0
        fun u8() = b[p++].toInt() and 255
        fun u16() = u8() or (u8() shl 8)
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII)); p = 6
        val w = u16(); val h = u16(); val packed = u8(); u8(); u8()
        assertTrue("global colour table expected", packed and 0x80 != 0)
        val tableSize = 1 shl ((packed and 7) + 1)
        val pal = IntArray(tableSize) { (u8() shl 16) or (u8() shl 8) or u8() }
        var loop = false; val delays = ArrayList<Int>(); val frames = ArrayList<IntArray>()
        while (true) {
            when (val t = u8()) {
                0x21 -> { val label = u8()
                    if (label == 0xF9) { u8(); u8(); delays += u16(); u8(); u8() }
                    else { if (label == 0xFF) { val n = u8(); val name = String(b, p, n, Charsets.US_ASCII); p += n; if (name == "NETSCAPE2.0") loop = true }
                        while (true) { val n = u8(); if (n == 0) break; p += n } } }
                0x2C -> { u16(); u16(); val fw = u16(); val fh = u16(); val ip = u8(); assertEquals("no local table", 0, ip and 0x80)
                    val minCode = u8(); val data = ArrayList<Byte>()
                    while (true) { val n = u8(); if (n == 0) break; for (i in 0 until n) data += b[p++] }
                    frames += lzwDecode(data.toByteArray(), minCode, fw * fh) }
                0x3B -> return Gif(w, h, loop, delays, pal, frames)
                else -> fail("unexpected block 0x${t.toString(16)} at ${p - 1}")
            }
        }
    }

    private fun lzwDecode(data: ByteArray, minCode: Int, count: Int): IntArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val stack = IntArray(4097)
        var codeSize = minCode + 1; var next = clear + 2; var prev = -1
        val out = IntArray(count); var o = 0
        var acc = 0; var bits = 0; var pos = 0
        for (i in 0 until clear) suffix[i] = i
        while (o < count) {
            while (bits < codeSize) { if (pos >= data.size) return out; acc = acc or ((data[pos++].toInt() and 255) shl bits); bits += 8 }
            val code = acc and ((1 shl codeSize) - 1); acc = acc ushr codeSize; bits -= codeSize
            if (code == clear) { codeSize = minCode + 1; next = clear + 2; prev = -1; continue }
            if (code == eoi) break
            var sp = 0; var c = code
            if (prev == -1) { out[o++] = suffix[code]; prev = code; continue }
            if (code >= next) { stack[sp++] = firstOf(prev, prefix, suffix, clear); c = prev }
            while (c >= clear) { stack[sp++] = suffix[c]; c = prefix[c] }
            stack[sp++] = suffix[c]
            val first = stack[sp - 1]
            while (sp > 0 && o < count) out[o++] = stack[--sp]
            if (next < 4096) { prefix[next] = prev; suffix[next] = first; next++; if (next == (1 shl codeSize) && codeSize < 12) codeSize++ }
            prev = code
        }
        return out
    }
    private fun firstOf(code: Int, prefix: IntArray, suffix: IntArray, clear: Int): Int { var c = code; while (c >= clear) c = prefix[c]; return suffix[c] }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun gradient(w: Int, h: Int, shift: Int = 0) = IntArray(w * h) { rgb(((it % w) * 255 / w + shift) % 256, ((it / w) * 255 / h), 128) }

    @Test fun headerLoopAndDelaysAreWritten() {
        val g = decode(GifEncoder.encodeToBytes(listOf(gradient(16, 8), gradient(16, 8, 40)), 16, 8, delayCs = 8))
        assertEquals(16, g.w); assertEquals(8, g.h); assertTrue(g.loop); assertEquals(listOf(8, 8), g.delays); assertEquals(2, g.frames.size)
        assertFalse(decode(GifEncoder.encodeToBytes(listOf(gradient(4, 4)), 4, 4, 5, loop = false)).loop)
    }

    @Test fun flatColourImagesRoundTripExactly() {
        val colors = intArrayOf(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255), rgb(255, 255, 0))
        val px = IntArray(40 * 40) { colors[((it % 40) / 10 + (it / 40) / 10) % 4] }
        val g = decode(GifEncoder.encodeToBytes(listOf(px), 40, 40, 5, dither = false))
        for (i in px.indices) assertEquals("pixel $i", px[i] and 0xFFFFFF, g.palette[g.frames[0][i]])
    }

    @Test fun lzwSurvivesTableResetsOnNoisyAndLargeImages() {
        val rnd = Random(9); val w = 300; val h = 200
        val noise = IntArray(w * h) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }   // forces many table fills / clear codes
        val bytes = GifEncoder.encodeToBytes(listOf(noise), w, h, 5, dither = false)
        val g = decode(bytes)
        val expected = GifEncoder.PaletteLut(g.palette).let { lut -> IntArray(noise.size) { lut.nearest(noise[it]) } }
        assertArrayEquals("indices must decode to exactly what was encoded", expected, g.frames[0])
    }

    @Test fun indexStreamsRoundTripThroughLzwForManyPatterns() {
        val rnd = Random(4)
        val patterns = listOf(
            ByteArray(1) { 7 }, ByteArray(2) { it.toByte() }, ByteArray(10_000) { 0 }, ByteArray(10_000) { (it % 256).toByte() },
            ByteArray(20_000) { (it / 50 % 7).toByte() }, ByteArray(30_000) { rnd.nextInt(256).toByte() }, ByteArray(30_000) { (rnd.nextInt(3) * 80).toByte() },
        )
        for ((n, idx) in patterns.withIndex()) {
            val out = java.io.ByteArrayOutputStream(); Lzw.encode(idx, out)
            val data = ArrayList<Byte>(); val s = out.toByteArray(); var p = 0
            while (p < s.size) { val len = s[p++].toInt() and 255; if (len == 0) break; for (i in 0 until len) data += s[p++] }
            val back = lzwDecode(data.toByteArray(), 8, idx.size)
            for (i in idx.indices) if ((idx[i].toInt() and 255) != back[i]) fail("pattern $n differs at $i (${idx[i].toInt() and 255} vs ${back[i]})")
        }
    }

    @Test fun smoothImagesStayCloseToTheOriginalWithDithering() {
        val px = gradient(120, 80)
        val g = decode(GifEncoder.encodeToBytes(listOf(px), 120, 80, 5, dither = true))
        var err = 0L
        for (i in px.indices) { val q = g.palette[g.frames[0][i]]; err += abs((px[i] shr 16 and 255) - (q shr 16 and 255)) + abs((px[i] shr 8 and 255) - (q shr 8 and 255)) + abs((px[i] and 255) - (q and 255)) }
        assertTrue("mean per-channel error ${err / (px.size * 3.0)}", err / (px.size * 3.0) < 6.0)
    }

    @Test fun globalPaletteIsSharedAcrossFrames() {
        val red = IntArray(100) { rgb(255, 0, 0) }; val blue = IntArray(100) { rgb(0, 0, 255) }
        val g = decode(GifEncoder.encodeToBytes(listOf(red, blue, red), 10, 10, 5, dither = false))
        assertEquals(3, g.frames.size)
        assertEquals(g.frames[0].toList(), g.frames[2].toList()); assertNotEquals(g.frames[0].toList(), g.frames[1].toList())
    }

    @Test fun paletteUsesUpTo256ColoursAndFewerForSimpleImages() {
        assertTrue(GifEncoder.buildPalette(listOf(IntArray(64) { rgb(10, 20, 30) }), 256).size <= 2)
        assertEquals(256, GifEncoder.buildPalette(listOf(gradient(200, 200)), 256).size)
    }

    @Test fun badInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { GifEncoder.encodeToBytes(listOf(IntArray(5)), 4, 4, 5) }
        assertThrows(IllegalArgumentException::class.java) { GifEncoder.encodeToBytes(emptyList(), 4, 4, 5) }
    }
}
