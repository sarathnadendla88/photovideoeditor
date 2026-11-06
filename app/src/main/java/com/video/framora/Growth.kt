package com.video.framora

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Apps people post finished videos to. Shown after an export only when they are installed. */
data class ShareTarget(val label: String, val pkg: String)

object ShareTargets {
    val known = listOf(
        ShareTarget("WhatsApp", "com.whatsapp"), ShareTarget("Instagram", "com.instagram.android"),
        ShareTarget("YouTube", "com.google.android.youtube"), ShareTarget("Facebook", "com.facebook.katana"),
        ShareTarget("Telegram", "org.telegram.messenger"), ShareTarget("Snapchat", "com.snapchat.android"),
    )

    fun installed(ctx: Context): List<ShareTarget> = known.filter { t ->
        runCatching { ctx.packageManager.getPackageInfo(t.pkg, 0) }.isSuccess
    }

    /** Opens [uri] straight in [pkg]'s own share screen. */
    fun intentFor(uri: Uri, pkg: String, mime: String = "video/mp4"): Intent =
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun share(ctx: Context, uri: Uri, t: ShareTarget, mime: String = "video/mp4") {
        try { ctx.startActivity(intentFor(uri, t.pkg, mime)) }
        catch (e: android.content.ActivityNotFoundException) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) }
    }
}

/** "Tell a friend" and "rate us": the store link only resolves once the app is published on Google Play. */
object AppPromo {
    fun playUrl(ctx: Context) = "https://play.google.com/store/apps/details?id=${ctx.packageName}"
    fun shareIntent(ctx: Context): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Framora")
            .putExtra(Intent.EXTRA_TEXT, "I make videos, reels and photo edits with Framora, and it works offline. ${playUrl(ctx)}"), "Share Framora")
    fun rateIntent(ctx: Context): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun rateWebIntent(ctx: Context): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(playUrl(ctx))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun rate(ctx: Context) { try { ctx.startActivity(rateIntent(ctx)) } catch (e: android.content.ActivityNotFoundException) { runCatching { ctx.startActivity(rateWebIntent(ctx)) } } }
}

/** Launcher long-press shortcuts: each maps to an intent action handled by MainActivity. */
object Shortcuts {
    const val REEL = "com.video.framora.action.REEL"
    const val SLIDESHOW = "com.video.framora.action.SLIDESHOW"
    const val PHOTO = "com.video.framora.action.PHOTO"
    const val VIDEO = "com.video.framora.action.VIDEO"
    const val RINGTONE = "com.video.framora.action.RINGTONE"
    fun screenFor(action: String?): Screen? = when (action) {
        REEL -> Screen.CAMERA; SLIDESHOW -> Screen.SLIDESHOW; PHOTO -> Screen.PHOTO; VIDEO -> Screen.VIDEO; RINGTONE -> Screen.RINGTONE; else -> null
    }
}
