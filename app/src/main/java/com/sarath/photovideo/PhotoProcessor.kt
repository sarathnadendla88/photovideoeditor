package com.sarath.photovideo

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PhotoProcessor {
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        fun open(): InputStream = context.contentResolver.openInputStream(uri)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val orient = open().use { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val m = Matrix().apply { postRotate(deg); if (scale < 1f) postScale(scale, scale) }
        if (deg == 0f && scale >= 1f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }.getOrNull()

    /** Decodes and scales so the bitmap is exactly [widthPx] wide (height follows), like a picture sized by width in the preview. */
    fun decodeToWidth(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        val b = decode(context, uri, (widthPx * 2).coerceIn(256, 4096)) ?: return null
        if (b.width == widthPx) return b
        return Bitmap.createScaledBitmap(b, widthPx, (b.height * widthPx.toFloat() / b.width).toInt().coerceAtLeast(1), true)
    }

    /** On-device person segmentation (ML Kit bundled model, works offline). Returns an ALPHA_8 mask at [src] size, or null. */
    suspend fun segment(src: Bitmap): Bitmap? = runCatching {
        val client = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        val mask = suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(src, 0)).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
        }
        client.close()
        val buf = mask.buffer; val w = mask.width; val h = mask.height
        val bytes = ByteArray(w * h) { (buf.float.coerceIn(0f, 1f) * 255).toInt().toByte() }
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes)) }
        Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }.getOrNull()

    private fun blur(src: Bitmap, strength: Int = 24): Bitmap {
        val w = (src.width / strength).coerceAtLeast(2); val h = (src.height / strength).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val mid = Bitmap.createScaledBitmap(small, w * 2, h * 2, true)
        return Bitmap.createScaledBitmap(mid, src.width, src.height, true)
    }

    private fun applyBackground(src: Bitmap, mask: Bitmap, e: PhotoEdit): Bitmap {
        val fg = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(fg).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when (e.background) {
            Background.BLUR -> c.drawBitmap(blur(src), 0f, 0f, null)
            Background.COLOR -> c.drawColor(e.backgroundColor.argb)
            else -> Unit
        }
        c.drawBitmap(fg, 0f, 0f, null)
        fg.recycle()
        return out
    }

    /** Simple auto-levels: stretch the 1st–99th luminance percentile to the full range and add a touch of colour. */
    fun autoEnhance(src: Bitmap, e: PhotoEdit): PhotoEdit {
        val small = Bitmap.createScaledBitmap(src, 128, (128f * src.height / src.width).toInt().coerceAtLeast(1), true)
        val px = IntArray(small.width * small.height); small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val hist = IntArray(256)
        px.forEach { c -> hist[((0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()).coerceIn(0, 255)]++ }
        fun pct(f: Float): Int { var acc = 0; val target = (px.size * f).toInt(); for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }; return 255 }
        val lo = pct(0.01f).toFloat(); val hi = pct(0.99f).toFloat().coerceAtLeast(lo + 32f)
        val c = (255f / (hi - lo)).coerceIn(0.85f, 1.5f)
        val b = ((-c * lo - (1 - c) * 128f) / 255f).coerceIn(-0.3f, 0.3f)
        small.recycle()
        return e.copy(contrast = c, brightness = b, saturation = maxOf(e.saturation, 1.12f))
    }

    fun colorMatrix(e: PhotoEdit): ColorMatrix {
        val m = ColorMatrix()
        m.postConcat(ColorMatrix().apply { setSaturation(e.saturation) })
        val c = e.contrast; val t = (1 - c) * 128f + e.brightness * 255f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val w = e.warmth * 30f
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f)))
        if (e.fade > 0f) { val f = e.fade * 60f; val s = 1f - e.fade * 0.25f
            m.postConcat(ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, f, 0f, s, 0f, 0f, f, 0f, 0f, s, 0f, f, 0f, 0f, 0f, 1f, 0f))) }
        filterMatrix(e.filter)?.let { m.postConcat(it) }
        return m
    }

    fun filterMatrix(f: PhotoFilter): ColorMatrix? = when (f) {
        PhotoFilter.NONE -> null
        PhotoFilter.MONO -> ColorMatrix().apply { setSaturation(0f) }
        PhotoFilter.NOIR -> ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix(floatArrayOf(1.4f, 0f, 0f, 0f, -40f, 0f, 1.4f, 0f, 0f, -40f, 0f, 0f, 1.4f, 0f, -40f, 0f, 0f, 0f, 1f, 0f))) }
        PhotoFilter.SEPIA -> ColorMatrix(floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.VIVID -> ColorMatrix().apply { setSaturation(1.5f) }
        PhotoFilter.COOL -> ColorMatrix(floatArrayOf(.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.WARM -> ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, .9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.FADE -> ColorMatrix(floatArrayOf(.85f, 0f, 0f, 0f, 35f, 0f, .85f, 0f, 0f, 35f, 0f, 0f, .85f, 0f, 40f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.DRAMATIC -> ColorMatrix().apply { setSaturation(0.7f); postConcat(ColorMatrix(floatArrayOf(1.35f, 0f, 0f, 0f, -45f, 0f, 1.35f, 0f, 0f, -45f, 0f, 0f, 1.35f, 0f, -45f, 0f, 0f, 0f, 1f, 0f))) }
        PhotoFilter.SUNSET -> ColorMatrix(floatArrayOf(1.2f, .1f, 0f, 0f, 15f, 0f, .95f, 0f, 0f, 0f, 0f, .1f, .8f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.MINT -> ColorMatrix(floatArrayOf(.9f, 0f, 0f, 0f, 0f, 0f, 1.1f, .05f, 0f, 10f, 0f, .1f, 1.05f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
        PhotoFilter.RETRO -> ColorMatrix().apply { setSaturation(0.75f); postConcat(ColorMatrix(floatArrayOf(1.05f, .05f, 0f, 0f, 20f, 0f, 1f, 0f, 0f, 10f, 0f, 0f, .85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }
    }

    private fun sharpen(bmp: Bitmap, amount: Float): Bitmap {
        val blurred = blur(bmp, 4)
        val w = bmp.width; val h = bmp.height
        val a = IntArray(w * h); val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h); blurred.getPixels(b, 0, w, 0, 0, w, h)
        fun ch(p: Int, q: Int, s: Int) = (((p shr s) and 255) + amount * 1.5f * (((p shr s) and 255) - ((q shr s) and 255))).toInt().coerceIn(0, 255)
        for (i in a.indices) { val p = a[i]; val q = b[i]
            a[i] = (p and (0xFF shl 24)) or (ch(p, q, 16) shl 16) or (ch(p, q, 8) shl 8) or ch(p, q, 0) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(a, 0, w, 0, 0, w, h)
        blurred.recycle()
        return out
    }

    /** Centre coordinate that keeps an item of [size] fully inside 0..[frame]. */
    fun clampCentre(c: Float, size: Float, frame: Float): Float = if (size >= frame) frame / 2 else c.coerceIn(size / 2, frame - size / 2)

    fun drawText(canvas: Canvas, t: TextLayer, W: Float, H: Float) {
        val size = H * t.size
        val style = when { t.bold && t.italic -> Typeface.BOLD_ITALIC; t.bold -> Typeface.BOLD; t.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = t.color.argb; textAlign = Paint.Align.CENTER; typeface = Typeface.create(t.font.family, style) }
        if (!t.box) p.setShadowLayer(size / 8, 0f, 0f, if (t.color == Palette.BLACK) Color.WHITE else Color.BLACK)
        val cx = W * t.x; val cy = H * t.y
        canvas.save(); canvas.rotate(t.rotation, cx, cy)
        val baseline = cy - (p.ascent() + p.descent()) / 2
        if (t.box) {
            val w = p.measureText(t.text); val pad = size * 0.35f
            canvas.drawRoundRect(RectF(cx - w / 2 - pad, cy - size * 0.7f - pad / 2, cx + w / 2 + pad, cy + size * 0.55f + pad / 2), size / 3, size / 3, Paint().apply { color = if (t.color == Palette.BLACK) 0xCCFFFFFF.toInt() else 0xB3000000.toInt() })
        }
        canvas.drawText(t.text, cx, baseline, p)
        canvas.restore()
    }

    /** Full pipeline. [mask] is the optional person mask for [src]. Output may have transparency (Background.REMOVE). */
    fun apply(src: Bitmap, mask: Bitmap?, e: PhotoEdit, imageLoader: ((String) -> Bitmap?)? = null): Bitmap {
        val base = if (mask != null && e.background != Background.KEEP) applyBackground(src, mask, e) else src
        val geo = Matrix().apply { postRotate(e.rotation.toFloat()); if (e.flipH) postScale(-1f, 1f) }
        var bmp = Bitmap.createBitmap(base, 0, 0, base.width, base.height, geo, true)
        if (!e.cropRect.isFull) {
            val c = e.cropRect
            val x = (c.l * bmp.width).toInt().coerceIn(0, bmp.width - 2); val y = (c.t * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = ((c.r - c.l) * bmp.width).toInt().coerceIn(2, bmp.width - x); val h = ((c.b - c.t) * bmp.height).toInt().coerceIn(2, bmp.height - y)
            bmp = Bitmap.createBitmap(bmp, x, y, w, h)
        }
        if (e.sharpen > 0f) bmp = sharpen(bmp.copy(Bitmap.Config.ARGB_8888, false), e.sharpen)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix(e)) })
        val W = out.width.toFloat(); val H = out.height.toFloat()
        if (e.vignette > 0f) {
            val r = maxOf(W, H) * 0.75f
            canvas.drawRect(0f, 0f, W, H, Paint().apply {
                shader = RadialGradient(W / 2, H / 2, r, intArrayOf(Color.TRANSPARENT, Color.argb((e.vignette * 200).toInt(), 0, 0, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        e.strokes.forEach { s ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color.argb; style = Paint.Style.STROKE; strokeWidth = s.width * H; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val path = Path()
            s.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * W, y * H) else path.lineTo(x * W, y * H) }
            if (s.points.size == 1) canvas.drawPoint(s.points[0].first * W, s.points[0].second * H, p) else canvas.drawPath(path, p)
        }
        e.images.forEach { im ->
            val bmp2 = imageLoader?.invoke(im.uri) ?: return@forEach
            val dw = minOf(W * im.size, W); val dh = dw * bmp2.height / bmp2.width
            // Keep the whole picture inside the frame: dragging to an edge parks it flush instead of cutting it off.
            val cx = clampCentre(W * im.x, dw, W); val cy = clampCentre(H * im.y, dh, H)
            canvas.save(); canvas.rotate(im.rotation, cx, cy)
            canvas.drawBitmap(bmp2, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (im.alpha * 255).toInt() })
            canvas.restore()
        }
        e.stickers.forEach { s ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s.size * H; textAlign = Paint.Align.CENTER }
            val half = p.textSize * 0.55f
            val sx = clampCentre(s.x * W, half * 2, W); val sy = clampCentre(s.y * H, half * 2, H)
            canvas.save(); canvas.rotate(s.rotation, sx, sy)
            canvas.drawText(s.emoji, sx, sy + p.textSize * 0.35f, p)
            canvas.restore()
        }
        e.texts.filter { it.text.isNotBlank() }.forEach { t -> drawText(canvas, t, W, H) }
        var result = out
        if (e.border > 0f) {
            val b = (maxOf(W, H) * e.border).toInt()
            result = Bitmap.createBitmap(out.width + 2 * b, out.height + 2 * b, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(e.borderColor.argb); drawBitmap(out, b.toFloat(), b.toFloat(), null) }
            }
            out.recycle()
        }
        if (bmp !== src) bmp.recycle()
        if (base !== src) base.recycle()
        return result
    }

    fun saveCopy(context: Context, bmp: Bitmap, transparent: Boolean): Uri {
        val png = transparent
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Edit_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoVideoStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            r.openOutputStream(uri)!!.use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
            r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { r.delete(uri, null, null); throw e }
        return uri
    }
}
