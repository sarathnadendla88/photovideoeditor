package com.studio.photovideo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the slideshow draft as JSON in app-private storage; picked photos are copied in so they survive restarts. */
class DraftStore(private val context: Context) {
    private val dir = File(context.filesDir, "slideshow_draft").apply { mkdirs() }
    private val file = File(dir, "draft.json")

    fun importPhoto(uri: Uri): Uri? = runCatching {
        val ext = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; else -> "jpg"
        }
        val dest = File(dir, "photo_${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
        Uri.fromFile(dest)
    }.getOrNull()

    fun save(p: SlideshowProject) {
        val j = JSONObject()
            .put("photos", JSONArray(p.photos.map { it.toString() }))
            .put("seconds", p.secondsPerPhoto.toDouble())
            .put("canvas", p.canvas.name).put("quality", p.quality.name).put("motion", p.motion.name)
            .put("title", p.title).put("endText", p.endText).put("transition", p.transition.name).put("watermark", p.watermark)
            .put("slideCaptions", JSONObject(p.slideCaptions as Map<*, *>))
            .put("stickers", JSONArray(p.stickers.map { st -> JSONObject().put("emoji", st.emoji).put("x", st.x.toDouble()).put("y", st.y.toDouble()).put("size", st.size.toDouble()).put("rotation", st.rotation.toDouble()).put("image", st.imageUri ?: "") }))
        p.music?.let {
            j.put("music", JSONObject().put("uri", it.uri.toString()).put("name", it.displayName)
                .put("duration", it.durationMs).put("start", it.startMs).put("volume", it.volume.toDouble()).put("loop", it.loop).put("fadeIn", it.fadeInMs).put("fadeOut", it.fadeOutMs))
        }
        file.writeText(j.toString())
        // drop copies no longer referenced
        val keep = p.photos.mapNotNull { it.path?.let(::File) }.toSet()
        dir.listFiles { f -> f.name.startsWith("photo_") }?.filter { it !in keep }?.forEach { it.delete() }
    }

    fun load(): SlideshowProject? = runCatching {
        if (!file.exists()) return null
        val j = JSONObject(file.readText())
        val photos = j.getJSONArray("photos").let { a -> List(a.length()) { Uri.parse(a.getString(it)) } }
            .filter { it.scheme != "file" || File(it.path!!).exists() }
        val music = j.optJSONObject("music")?.let {
            MusicTrack(Uri.parse(it.getString("uri")), it.getString("name"), it.optLong("duration"), it.getLong("start"), it.getDouble("volume").toFloat(), it.getBoolean("loop"), it.optLong("fadeIn"), it.optLong("fadeOut"))
        }
        SlideshowProject(
            photos, j.getDouble("seconds").toFloat(), CanvasPreset.valueOf(j.getString("canvas")),
            ExportQuality.valueOf(j.optString("quality", "HD")), Motion.valueOf(j.optString("motion", "KEN_BURNS")),
            j.optString("title"), j.optString("endText"), Transition.valueOf(j.optString("transition", "NONE")), j.optString("watermark"),
            j.optJSONObject("slideCaptions")?.let { o -> o.keys().asSequence().associateWith { k -> o.getString(k) } }.orEmpty(),
            j.optJSONArray("stickers")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { o -> VideoSticker(o.getString("emoji"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("size").toFloat(), rotation = o.getDouble("rotation").toFloat(), imageUri = o.optString("image").ifEmpty { null }) } } }.orEmpty(),
            music,
        )
    }.getOrNull()
}
