package com.sarath.photovideo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The colour/finish part of a photo edit (no crop, text, stickers or drawing) so it can be reused on other photos. */
data class PhotoLook(
    val name: String, val filter: PhotoFilter, val brightness: Float, val contrast: Float, val saturation: Float,
    val warmth: Float, val vignette: Float, val sharpen: Float, val fade: Float,
)

fun PhotoEdit.toLook(name: String) = PhotoLook(name, filter, brightness, contrast, saturation, warmth, vignette, sharpen, fade)
fun PhotoLook.toEdit() = PhotoEdit(filter = filter, brightness = brightness, contrast = contrast, saturation = saturation, warmth = warmth, vignette = vignette, sharpen = sharpen, fade = fade)
fun PhotoEdit.withLook(l: PhotoLook) = copy(filter = l.filter, brightness = l.brightness, contrast = l.contrast, saturation = l.saturation, warmth = l.warmth, vignette = l.vignette, sharpen = l.sharpen, fade = l.fade)

/** Personal looks saved on the device (SharedPreferences, private to the app). */
class LookStore(context: Context) {
    private val prefs = context.getSharedPreferences("looks", Context.MODE_PRIVATE)

    fun all(): List<PhotoLook> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        List(a.length()) { i ->
            a.getJSONObject(i).let { o ->
                PhotoLook(o.getString("name"), PhotoFilter.valueOf(o.getString("filter")), o.getDouble("b").toFloat(), o.getDouble("c").toFloat(), o.getDouble("s").toFloat(),
                    o.getDouble("w").toFloat(), o.getDouble("v").toFloat(), o.getDouble("sh").toFloat(), o.getDouble("f").toFloat())
            }
        }
    }.getOrDefault(emptyList())

    fun save(look: PhotoLook): List<PhotoLook> {
        val list = all().filter { it.name != look.name } + look
        val a = JSONArray()
        list.forEach { l -> a.put(JSONObject().put("name", l.name).put("filter", l.filter.name).put("b", l.brightness.toDouble()).put("c", l.contrast.toDouble())
            .put("s", l.saturation.toDouble()).put("w", l.warmth.toDouble()).put("v", l.vignette.toDouble()).put("sh", l.sharpen.toDouble()).put("f", l.fade.toDouble())) }
        prefs.edit().putString("items", a.toString()).apply()
        return list
    }

    fun delete(name: String): List<PhotoLook> {
        val keep = all().filter { it.name != name }
        prefs.edit().remove("items").apply()
        keep.forEach { save(it) }
        return keep
    }
}
