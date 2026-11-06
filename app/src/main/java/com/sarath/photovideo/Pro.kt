package com.sarath.photovideo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything ReelMint Pro changes, in ONE place so every screen asks the same question and the pricing table cannot disagree
 * with what the app actually does. Basic editing, saving and sharing are never locked.
 */
object Entitlements {
    const val FREE_LOOKS = 3
    const val FREE_GIF_MS = 5_000L
    const val PRO_GIF_MS = 10_000L
    const val PRO_GIF_FPS = 20
    private val REAL_EFFECTS get() = PhotoEffect.entries.size - 1   // everything except "None"
    val PRO_EFFECTS = setOf(PhotoEffect.CARTOON, PhotoEffect.GLITCH, PhotoEffect.SUNSET_DUO, PhotoEffect.OCEAN_DUO)

    /** The small "Made with ReelMint" tag on free video, slideshow and GIF exports. Flip this to false to ship without it. */
    const val FREE_EXPORTS_CARRY_TAG = true
    const val TAG_TEXT = "Made with ReelMint"

    fun adsFree(pro: Boolean) = pro
    fun brandTag(pro: Boolean) = FREE_EXPORTS_CARRY_TAG && !pro
    fun quality(q: ExportQuality, pro: Boolean) = pro || q != ExportQuality.FHD
    /** The best quality this person can export, given what they picked. */
    fun cap(q: ExportQuality, pro: Boolean) = if (quality(q, pro)) q else ExportQuality.HD
    fun gifMaxMs(pro: Boolean) = if (pro) PRO_GIF_MS else FREE_GIF_MS
    fun gifSize(s: GifSize, pro: Boolean) = pro || s != GifSize.LARGE
    fun gifFps(fps: Int, pro: Boolean) = pro || fps <= 15
    fun effect(e: PhotoEffect, pro: Boolean) = pro || e !in PRO_EFFECTS
    fun canSaveAnotherLook(saved: Int, pro: Boolean) = pro || saved < FREE_LOOKS
    fun template(t: SlideshowTemplate, pro: Boolean) = pro || !t.pro
    fun filter(f: PhotoFilter, pro: Boolean) = pro || !f.pro
    fun lookPreset(l: LookPreset, pro: Boolean) = pro || !l.filter.pro
    fun motion(m: Motion, pro: Boolean) = pro || !m.pro
    fun collageLayout(l: CollageLayout, pro: Boolean) = pro || !l.pro
    fun stickerPack(p: StickerPack, pro: Boolean) = pro || !p.pro
    fun gifStyle(s: GifStyle, pro: Boolean) = pro || !s.pro
    fun gifSpeed(speed: Float, pro: Boolean) = pro || speed == 1f
    fun voiceFx(v: VoiceFx, pro: Boolean) = pro || !v.pro
    fun font(f: TextFont, pro: Boolean) = pro || !f.pro
    fun theme(t: AppTheme, pro: Boolean) = pro || !t.pro
    fun frame(f: FrameStyle, pro: Boolean) = pro || !f.pro
    fun transition(t: Transition, pro: Boolean) = pro || !t.pro
    fun captionStyle(c: CaptionStyle, pro: Boolean) = pro || !c.pro
    /** Clip speed: 0.1× slow-mo and 5× / 8× timelapse are Pro. */
    fun speed(s: Float, pro: Boolean) = pro || s in 0.25f..3f

    /** The pricing table: what you get, free versus Pro. */
    data class Row(val title: String, val free: String, val pro: String)
    val table: List<Row> get() = listOf(
        Row("Ads", "Banner on home, short ad after some exports", "None, anywhere"),
        Row("\"Made with ReelMint\" tag", "On video, slideshow and GIF exports", "Never"),
        Row("Export quality", "Small and Standard", "+ High (1080p)"),
        Row("Slideshow templates", "${SlideshowTemplates.count { !it.pro }} templates", "${SlideshowTemplates.size} templates, new looks, motions and transitions"),
        Row("Slideshow motions", "${Motion.entries.count { !it.pro } - 1} motions", "${Motion.entries.size - 1} motions, incl. Zoom out, Pan, Drop in and Beat pulse"),
        Row("Filters", "${PhotoFilter.entries.count { !it.pro } - 1} filters", "${PhotoFilter.entries.size - 1} filters, for photos and video"),
        Row("One-tap video looks", "${LookPresets.count { !it.filter.pro }} looks", "${LookPresets.size} looks"),
        Row("Photo effects", "${REAL_EFFECTS - PRO_EFFECTS.size} effects", "All $REAL_EFFECTS effects"),
        Row("Sticker packs", "1 pack, ${StickerPacks.classic.emojis.size} stickers", "${StickerPacks.all.size} packs, ${StickerPacks.all.sumOf { it.emojis.size }} stickers"),
        Row("Collage layouts", "${CollageLayout.entries.count { !it.pro }} layouts", "${CollageLayout.entries.size} layouts, up to 9 photos"),
        Row("GIF maker", "Up to ${FREE_GIF_MS / 1000} s, Small / Medium, up to 15 fps", "Up to ${PRO_GIF_MS / 1000} s, Large, $PRO_GIF_FPS fps, Reverse loop and 0.5× / 2× speed"),
        Row("Photo text fonts", "${TextFont.entries.count { !it.pro }} fonts", "${TextFont.entries.size} fonts, incl. Light, Thin, Heavy, Typewriter"),
        Row("Photo frames", "Plain border", "${FrameStyle.entries.size} styles: Polaroid, Film strip, Rounded, Shadow"),
        Row("Video caption styles", "${CaptionStyle.entries.count { !it.pro }} styles", "${CaptionStyle.entries.size} styles, incl. Outline, Pink glow, Sunset"),
        Row("Voice effects", "${VoiceFx.entries.count { !it.pro } - 1} effects", "${VoiceFx.entries.size - 1} effects, incl. Robot, Echo, Alien"),
        Row("Slideshow transitions", "${Transition.entries.count { !it.pro } - 1} transitions", "${Transition.entries.size - 1} transitions, incl. Flash white"),
        Row("Clip speed", "0.25× to 3×", "0.1× slow-mo to 8× timelapse"),
        Row("Your own logo", "Not available", "On every video, slideshow and GIF, in any corner"),
        Row("App themes", "1 (Violet)", "${AppTheme.entries.size} colour themes"),
        Row("Saved photo looks", "Up to $FREE_LOOKS", "Unlimited"),
    )
}

/**
 * The local record of the one-time Pro payment. It lives in its own preferences file (`pro.xml`) which is the ONLY file in
 * Android backup, so Pro comes back after a reinstall or on a new phone when Google backup is on.
 * Without a payment server it is only as trustworthy as the device (see docs/PAYMENTS.md).
 */
class ProStore(private val prefs: SharedPreferences) {
    private val paymentsKey = "payments"      // entries "paymentId|epochMillis"
    private val unverifiedKey = "unverified"  // entries "paymentId|orderId|signature"
    private val pendingKey = "pending_since"

    fun isPro() = prefs.getStringSet(paymentsKey, emptySet()).orEmpty().isNotEmpty()
    fun paymentIds(): List<String> = prefs.getStringSet(paymentsKey, emptySet()).orEmpty().map { it.substringBefore('|') }

    /** Records a payment. Recording the same payment id twice never counts twice. Returns true if it was new. */
    fun unlock(paymentId: String, now: Long = System.currentTimeMillis()): Boolean {
        val cur = prefs.getStringSet(paymentsKey, emptySet()).orEmpty()
        if (cur.any { it.substringBefore('|') == paymentId }) return false
        prefs.edit().putStringSet(paymentsKey, cur + "$paymentId|$now").apply()
        runCatching { android.app.backup.BackupManager.dataChanged("com.sarath.photovideo") }   // schedule a cloud backup soon
        return true
    }

    fun setPending(on: Boolean) { prefs.edit().apply { if (on) putLong(pendingKey, System.currentTimeMillis()) else remove(pendingKey) }.apply() }
    fun pending() = prefs.contains(pendingKey)

    data class Unverified(val paymentId: String, val orderId: String, val signature: String)
    fun addUnverified(u: Unverified) { prefs.edit().putStringSet(unverifiedKey, prefs.getStringSet(unverifiedKey, emptySet()).orEmpty() + "${u.paymentId}|${u.orderId}|${u.signature}").apply() }
    fun unverified(): List<Unverified> = prefs.getStringSet(unverifiedKey, emptySet()).orEmpty().mapNotNull { l -> l.split("|").takeIf { it.size == 3 }?.let { Unverified(it[0], it[1], it[2]) } }
    fun removeUnverified(paymentId: String) { prefs.edit().putStringSet(unverifiedKey, prefs.getStringSet(unverifiedKey, emptySet()).orEmpty().filterNot { it.substringBefore('|') == paymentId }.toSet()).apply() }

    /** Forgets every payment. Only offered in test mode (a rzp_test_ key), so purchases can be tried again. */
    fun reset() { prefs.edit().clear().apply() }
}

/** App-wide Pro state. [isPro] is Compose state, so every screen updates the moment a payment is confirmed. */
object Pro {
    lateinit var store: ProStore; private set
    var isPro by mutableStateOf(false); private set

    fun init(context: Context) {
        if (!::store.isInitialized) store = ProStore(context.applicationContext.getSharedPreferences("pro", Context.MODE_PRIVATE))
        isPro = store.isPro()
    }

    /** Records a confirmed payment and switches Pro on. */
    fun grant(paymentId: String): Boolean = store.unlock(paymentId).also { isPro = true }
    /** Forgets Pro. Does nothing unless the build uses a Razorpay TEST key, so a real customer's purchase can never be wiped by accident. */
    fun reset(): Boolean { if (!Payments.config.testMode) return false; store.reset(); isPro = false; return true }
}
