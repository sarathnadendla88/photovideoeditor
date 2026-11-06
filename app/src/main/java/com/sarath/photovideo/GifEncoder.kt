package com.sarath.photovideo

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Animated GIF writer with no Android dependencies (so it is unit-testable on the JVM).
 * One global palette for all frames (median cut over samples from every frame, so colours do not flicker),
 * optional Floyd-Steinberg dithering, and GIF-flavoured variable-width LZW.
 */
object GifEncoder {
    /** [frames] are ARGB pixels (alpha ignored), each width*height. [delayCs] is the per-frame delay in 1/100 s. */
    fun encode(frames: List<IntArray>, width: Int, height: Int, delayCs: Int, out: OutputStream, loop: Boolean = true, dither: Boolean = true, onProgress: (Float) -> Unit = {}) {
        require(frames.isNotEmpty() && width > 0 && height > 0)
        frames.forEach { require(it.size == width * height) { "frame size does not match ${width}x$height" } }
        val palette = buildPalette(frames, 256)
        val lut = PaletteLut(palette)

        fun le(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le(width); le(height)
        out.write(0xF7)                     // global colour table present, 8 bits of colour resolution, table size 256
        out.write(0); out.write(0)          // background index, pixel aspect ratio
        for (i in 0 until 256) { val c = if (i < palette.size) palette[i] else 0; out.write(c shr 16 and 255); out.write(c shr 8 and 255); out.write(c and 255) }
        if (loop) { out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); out.write(byteArrayOf(3, 1, 0, 0, 0)) }

        val indices = ByteArray(width * height)
        frames.forEachIndexed { n, px ->
            if (dither) ditherFrame(px, width, height, lut, indices) else for (i in px.indices) indices[i] = lut.nearest(px[i]).toByte()
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04)); le(max(2, delayCs)); out.write(0); out.write(0)   // keep the previous frame, no transparency
            out.write(0x2C); le(0); le(0); le(width); le(height); out.write(0)                                  // image descriptor, no local colour table
            out.write(8)                                                                                       // LZW minimum code size
            Lzw.encode(indices, out)
            out.write(0)
            onProgress((n + 1f) / frames.size)
        }
        out.write(0x3B)
    }

    fun encodeToBytes(frames: List<IntArray>, width: Int, height: Int, delayCs: Int, loop: Boolean = true, dither: Boolean = true): ByteArray =
        ByteArrayOutputStream().also { encode(frames, width, height, delayCs, it, loop, dither) }.toByteArray()

    // ---------------------------------------------------------------- palette

    /** Median cut over up to ~16k sampled pixels of all frames. Returns at most [maxColors] 0xRRGGBB entries. */
    fun buildPalette(frames: List<IntArray>, maxColors: Int): IntArray {
        val perFrame = max(64, 16_384 / frames.size)
        val samples = ArrayList<Int>()
        for (f in frames) { val step = max(1, f.size / perFrame); var i = 0; while (i < f.size) { samples += f[i] and 0xFFFFFF; i += step } }
        val boxes = ArrayList<IntArray>(); boxes += samples.toIntArray()
        while (boxes.size < maxColors) {
            var bi = -1; var bestScore = 0L
            for ((i, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                val r = ranges(b); val score = r.max().toLong() * b.size
                if (r.max() > 0 && score > bestScore) { bestScore = score; bi = i }
            }
            if (bi < 0) break
            val box = boxes[bi]; val r = ranges(box)
            val ch = r.indices.maxByOrNull { r[it] }!!; val shift = 16 - 8 * ch
            val sorted = box.sortedBy { (it shr shift) and 255 }
            val half = sorted.size / 2
            boxes[bi] = sorted.subList(0, half).toIntArray(); boxes += sorted.subList(half, sorted.size).toIntArray()
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]; var r = 0L; var g = 0L; var bl = 0L
            for (c in b) { r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255 }
            val n = max(1, b.size)
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        }
    }

    private fun ranges(b: IntArray): IntArray {
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        for (c in b) { val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            rMin = min(rMin, r); rMax = max(rMax, r); gMin = min(gMin, g); gMax = max(gMax, g); bMin = min(bMin, bl); bMax = max(bMax, bl) }
        return intArrayOf(rMax - rMin, gMax - gMin, bMax - bMin)
    }

    /** Nearest palette entry for a colour, cached on a 5-5-5 grid so each distinct colour is searched once. */
    class PaletteLut(val palette: IntArray) {
        private val cache = IntArray(32768) { -1 }
        fun nearest(argb: Int): Int {
            val key = ((argb shr 19 and 31) shl 10) or ((argb shr 11 and 31) shl 5) or (argb shr 3 and 31)
            var v = cache[key]
            if (v < 0) {
                // search with the cell's centre colour so every colour in the cell maps the same way
                val r = ((key shr 10) shl 3) or 4; val g = ((key shr 5 and 31) shl 3) or 4; val b = ((key and 31) shl 3) or 4
                var best = Int.MAX_VALUE; v = 0
                for (i in palette.indices) {
                    val c = palette[i]; val dr = r - (c shr 16 and 255); val dg = g - (c shr 8 and 255); val db = b - (c and 255)
                    val d = dr * dr * 3 + dg * dg * 4 + db * db * 2   // eyes are most sensitive to green
                    if (d < best) { best = d; v = i }
                }
                cache[key] = v
            }
            return v
        }
    }

    private fun ditherFrame(px: IntArray, w: Int, h: Int, lut: PaletteLut, out: ByteArray) {
        // Floyd-Steinberg with a two-row error buffer per channel.
        var cur = Array(3) { FloatArray(w + 2) }; var nxt = Array(3) { FloatArray(w + 2) }
        for (y in 0 until h) {
            for (c in 0..2) java.util.Arrays.fill(nxt[c], 0f)
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = ((p shr 16 and 255) + cur[0][x + 1]).coerceIn(0f, 255f); val g = ((p shr 8 and 255) + cur[1][x + 1]).coerceIn(0f, 255f); val b = ((p and 255) + cur[2][x + 1]).coerceIn(0f, 255f)
                val idx = lut.nearest((r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()); out[y * w + x] = idx.toByte()
                val q = lut.palette[idx]
                val er = r - (q shr 16 and 255); val eg = g - (q shr 8 and 255); val eb = b - (q and 255)
                fun spread(c: Int, e: Float) {
                    cur[c][x + 2] += e * 7f / 16f; nxt[c][x] += e * 3f / 16f; nxt[c][x + 1] += e * 5f / 16f; nxt[c][x + 2] += e / 16f
                }
                spread(0, er); spread(1, eg); spread(2, eb)
            }
            val t = cur; cur = nxt; nxt = t
        }
    }
}

/** GIF-flavoured LZW (variable code width, clear/end codes, 12-bit table) writing data sub-blocks of at most 255 bytes. */
internal object Lzw {
    private const val BITS = 12
    private const val HSIZE = 1 shl 14

    fun encode(indices: ByteArray, sink: OutputStream) {
        val clear = 256; val eof = 257; val maxMax = 1 shl BITS
        val keys = IntArray(HSIZE) { -1 }; val vals = IntArray(HSIZE)
        var freeEnt = clear + 2; var nBits = 9; var maxCode = (1 shl nBits) - 1; var clearFlag = false
        var acc = 0; var accBits = 0
        val block = ByteArray(255); var blockLen = 0
        fun flush() { if (blockLen > 0) { sink.write(blockLen); sink.write(block, 0, blockLen); blockLen = 0 } }
        fun put(b: Int) { block[blockLen++] = b.toByte(); if (blockLen == 255) flush() }
        fun output(code: Int) {
            acc = acc or (code shl accBits); accBits += nBits
            while (accBits >= 8) { put(acc and 255); acc = acc ushr 8; accBits -= 8 }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) { nBits = 9; maxCode = (1 shl nBits) - 1; clearFlag = false }
                else { nBits++; maxCode = if (nBits == BITS) maxMax else (1 shl nBits) - 1 }
            }
            if (code == eof) { while (accBits > 0) { put(acc and 255); acc = acc ushr 8; accBits -= 8 }; flush() }
        }
        fun slot(key: Int): Int { var i = (key * -1640531527) ushr (32 - 14); while (keys[i] != -1 && keys[i] != key) i = (i + 1) and (HSIZE - 1); return i }

        output(clear)
        var ent = indices[0].toInt() and 255
        for (n in 1 until indices.size) {
            val c = indices[n].toInt() and 255
            val key = (c shl BITS) or ent
            val s = slot(key)
            if (keys[s] == key) { ent = vals[s]; continue }
            output(ent); ent = c
            if (freeEnt < maxMax) { keys[s] = key; vals[s] = freeEnt++ }
            else { java.util.Arrays.fill(keys, -1); freeEnt = clear + 2; clearFlag = true; output(clear) }
        }
        output(ent); output(eof)
    }
}
