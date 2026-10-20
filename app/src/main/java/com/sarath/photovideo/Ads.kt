package com.sarath.photovideo

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Mobile Ads, behind Google's User Messaging Platform consent flow.
 *
 * Rules this app follows (see claude.md): ads never appear inside an editor, never before a result is saved, never
 * block exporting, and are capped. No ad is requested until consent has been gathered where it is required.
 * The ids come from strings.xml and are Google's TEST ids until you replace them.
 */
object Ads {
    private val started = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var loading = false
    private const val EXPORTS_PER_AD = 3

    /** Kept in memory only: the cap restarts when the app restarts, which errs on the side of fewer ads per day. */
    private var exportsSinceAd = 0
    var consentReady by mutableStateOf(false); private set

    fun privacyOptionsRequired(context: Context) =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Gathers consent (shows Google's form if the user's region requires it) and only then starts the ads SDK. */
    fun start(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> initIfAllowed(activity, info) }
        }, { _ -> initIfAllowed(activity, info) })
        // A returning user whose consent was already gathered can start right away.
        if (info.canRequestAds()) initIfAllowed(activity, info)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> }
    }

    private fun initIfAllowed(activity: Activity, info: ConsentInformation) {
        if (!info.canRequestAds() || !started.compareAndSet(false, true)) return
        MobileAds.initialize(activity) { consentReady = true; preloadInterstitial(activity) }
    }

    fun preloadInterstitial(context: Context) {
        if (!consentReady || interstitial != null || loading) return
        loading = true
        InterstitialAd.load(context, context.getString(R.string.admob_interstitial_id), AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loading = false }
            override fun onAdFailedToLoad(e: LoadAdError) { interstitial = null; loading = false }
        })
    }

    /**
     * Call after an export has been saved and the user has dismissed the "Saved" dialog. Shows at most one full-screen
     * ad every [EXPORTS_PER_AD] exports, and only if one is already loaded, so it never delays the user.
     */
    fun maybeShowAfterExport(activity: Activity) {
        exportsSinceAd++
        val ad = interstitial
        if (exportsSinceAd < EXPORTS_PER_AD || ad == null) { preloadInterstitial(activity); return }
        exportsSinceAd = 0
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; preloadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) { interstitial = null; preloadInterstitial(activity) }
        }
        ad.show(activity)
    }
}

/** Banner for the home screen only. Collapses to nothing until an ad has loaded, so there is no empty gap. */
@Composable
fun HomeBanner(modifier: Modifier = Modifier) {
    if (!Ads.consentReady) return
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    val view = remember {
        AdView(ctx).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = ctx.getString(R.string.admob_banner_id)
            adListener = object : AdListener() {
                override fun onAdLoaded() { loaded = true }
                override fun onAdFailedToLoad(e: LoadAdError) { loaded = false }
            }
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    AndroidView({ view }, modifier.fillMaxWidth().then(if (loaded) Modifier.heightIn(min = 50.dp) else Modifier.heightIn(max = 0.dp)))
}

fun Context.findActivity(): Activity? { var c = this; while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }
