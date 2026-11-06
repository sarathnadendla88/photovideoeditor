package com.video.framora

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Creative one-tap effects. All of them run on the device with plain bitmap maths. */
enum class PhotoEffect(val label: String) {
    NONE("None"), SKETCH("Sketch"), CARTOON("Cartoon"), PIXELATE("Pixelate"), GLITCH("Glitch"),
    SUNSET_DUO("Sunset duo"), OCEAN_DUO("Ocean duo"), GRAIN("Film grain"), BLUR("Soft blur"), MIRROR("Mirror"),
}

object PhotoEffects {
    /** [strength] is 0..1; the result always has the same size as [src]. */
    fun apply(src: Bitmap, effect: PhotoEffect, strength: Float): Bitmap {
        if (effect == PhotoEffect.NONE) return src
        val s = strength.coerceIn(0f, 1f)
        val w = src.width; val h = src.height
        return when (effect) {
            PhotoEffect.NONE -> src
            PhotoEffect.PIXELATE -> pixelate(src, s)
            PhotoEffect.BLUR -> softBlur(src, s)
            else -> {
                val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
                val out = when (effect) {
                    PhotoEffect.SKETCH -> sketch(px, w, h, s)
                    PhotoEffect.CARTOON -> cartoon(px, w, h, s)
                    PhotoEffect.GLITCH -> glitch(px, w, h, s)
                    PhotoEffect.SUNSET_DUO -> duotone(px, 0xFF2B1055.toInt(), 0xFFFFB84D.toInt(), s)
                    PhotoEffect.OCEAN_DUO -> duotone(px, 0xFF0B1F4D.toInt(), 0xFF2DD4BF.toInt(), s)
                    PhotoEffect.GRAIN -> grain(px, s)
                    PhotoEffect.MIRROR -> mirror(px, w, h)
                    else -> px
                }
                Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun pixelate(src: Bitmap, s: Float): Bitmap {
        val block = max(2, (min(src.width, src.height) * (0.01f + 0.07f * s)).toInt())
        val sw = max(1, src.width / block); val sh = max(1, src.height / block)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, false)   // nearest neighbour keeps hard blocks
    }

    private fun softBlur(src: Bitmap, s: Float): Bitmap {
        val f = max(2, (3 + 22 * s).toInt())
        val small = Bitmap.createScaledBitmap(src, max(2, src.width / f), max(2, src.height / f), true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** Box blur on a float channel, two passes (cheap and good enough for a pencil-sketch dodge). */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        fun pass(inp: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(inp.size)
            val n = if (horizontal) w else h; val lines = if (horizontal) h else w
            for (l in 0 until lines) {
                var sum = 0f; var cnt = 0
                for (i in 0 until min(r, n)) { sum += inp[if (horizontal) l * w + i else i * w + l]; cnt++ }
                for (i in 0 until n) {
                    val add = i + r; val sub = i - r - 1
                    if (add < n) { sum += inp[if (horizontal) l * w + add else add * w + l]; cnt++ }
                    if (sub >= 0) { sum -= inp[if (horizontal) l * w + sub else sub * w + l]; cnt-- }
                    out[if (horizontal) l * w + i else i * w + l] = sum / cnt
                }
            }
            return out
        }
        return pass(pass(a, true), false)
    }

    private fun sketch(px: IntArray, w: Int, h: Int, s: Float): IntArray {
        val gray = FloatArray(px.size) { lum(px[it]) }
        val inv = FloatArray(px.size) { 255f - gray[it] }
        val r = max(2, (min(w, h) * (0.004f + 0.02f * s)).toInt())
        val blurred = boxBlur(inv, w, h, r)
        return IntArray(px.size) {
            // colour dodge: gray / (1 - blurredInverse)
            val d = 255f - blurred[it]
            val v = clamp(if (d <= 1f) 255 else (gray[it] * 255f / d).toInt())
            Color.rgb(v, v, v)
        }
    }

    private fun cartoon(px: IntArray, w: Int, h: Int, s: Float): IntArray {
        val levels = max(3, (8 - 5 * s).toInt())
        val step = 255f / (levels - 1)
        val gray = FloatArray(px.size) { lum(px[it]) }
        val edgeCut = 90f - 50f * s
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = px[i]
            val r = (Math.round(Color.red(c) / step) * step).toInt(); val g = (Math.round(Color.green(c) / step) * step).toInt(); val b = (Math.round(Color.blue(c) / step) * step).toInt()
            var edge = 0f
            if (x in 1 until w - 1 && y in 1 until h - 1) {
                val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
                val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                edge = abs(gx) + abs(gy)
            }
            out[i] = if (edge > edgeCut * 4f) Color.rgb(20, 20, 30) else Color.rgb(clamp(r), clamp(g), clamp(b))
        }
        return out
    }

    private fun glitch(px: IntArray, w: Int, h: Int, s: Float): IntArray {
        val shift = max(2, (w * (0.01f + 0.04f * s)).toInt())
        val rnd = Random(7)
        val out = IntArray(px.size)
        val rowShift = IntArray(h)
        var y = 0
        while (y < h) {   // a handful of horizontally displaced slices
            val band = max(2, (h * rnd.nextFloat() * 0.06f).toInt()); val off = if (rnd.nextFloat() < 0.35f * (0.4f + s)) ((rnd.nextFloat() - 0.5f) * w * 0.12f * (0.5f + s)).toInt() else 0
            for (k in y until min(h, y + band)) rowShift[k] = off
            y += band
        }
        for (yy in 0 until h) for (x in 0 until w) {
            val sx = ((x - rowShift[yy]) % w + w) % w
            val rX = ((sx - shift) % w + w) % w; val bX = ((sx + shift) % w + w) % w
            out[yy * w + x] = Color.argb(255, Color.red(px[yy * w + rX]), Color.green(px[yy * w + sx]), Color.blue(px[yy * w + bX]))
        }
        return out
    }

    private fun duotone(px: IntArray, dark: Int, light: Int, s: Float): IntArray = IntArray(px.size) {
        val t = lum(px[it]) / 255f
        val r = Color.red(dark) + (Color.red(light) - Color.red(dark)) * t
        val g = Color.green(dark) + (Color.green(light) - Color.green(dark)) * t
        val b = Color.blue(dark) + (Color.blue(light) - Color.blue(dark)) * t
        // strength blends between the original and the pure duotone
        val c = px[it]
        Color.rgb(clamp((Color.red(c) + (r - Color.red(c)) * (0.4f + 0.6f * s)).toInt()), clamp((Color.green(c) + (g - Color.green(c)) * (0.4f + 0.6f * s)).toInt()), clamp((Color.blue(c) + (b - Color.blue(c)) * (0.4f + 0.6f * s)).toInt()))
    }

    private fun grain(px: IntArray, s: Float): IntArray {
        val rnd = Random(11); val amp = 12f + 55f * s
        return IntArray(px.size) {
            val n = ((rnd.nextFloat() - 0.5f) * 2f * amp).toInt(); val c = px[it]
            Color.rgb(clamp(Color.red(c) + n), clamp(Color.green(c) + n), clamp(Color.blue(c) + n))
        }
    }

    private fun mirror(px: IntArray, w: Int, h: Int): IntArray {
        val out = px.copyOf()
        for (y in 0 until h) for (x in 0 until w / 2) out[y * w + (w - 1 - x)] = px[y * w + x]
        return out
    }
}
