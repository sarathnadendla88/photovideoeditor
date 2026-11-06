package com.video.framora

import android.app.Activity
import com.razorpay.Checkout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What Pro costs and how it is paid for. A single one-time payment, no subscription. */
interface PaymentConfig {
    /** Razorpay's PUBLIC key id (rzp_test_... / rzp_live_...). The key secret must never be in the app. */
    val keyId: String
    val amountPaise: Int
    val currency: String get() = "INR"
    /** Base URL of YOUR payment server, or blank for the app-only flow (no Razorpay orders, no server-side verification). */
    val apiUrl: String get() = ""
    val serverVerified: Boolean get() = apiUrl.isNotBlank()
    val enabled: Boolean get() = keyId.isNotBlank()
    /** A rzp_test_ key: Razorpay simulates payments and no real money moves. */
    val testMode: Boolean get() = keyId.trim().startsWith("rzp_test_")
    val displayPrice: String get() = priceText(amountPaise)
    fun priceText(paise: Int) = "₹" + (if (paise % 100 == 0) (paise / 100).toString() else "%.2f".format(paise / 100.0))
}

object BuildPaymentConfig : PaymentConfig {
    override val keyId: String get() = BuildConfig.RAZORPAY_KEY_ID
    override val amountPaise: Int get() = BuildConfig.PRO_PRICE_PAISE
    override val apiUrl: String get() = BuildConfig.PAYMENT_API_URL
}

/** A Razorpay order created by the payment server. The amount comes from the server, so prices can change without an app update. */
data class Order(val orderId: String, val amountPaise: Int, val currency: String, val keyId: String?)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** [retryable] is true for problems that may pass (no network, server busy) as opposed to a definite "no". */
    data class Failed(val message: String, val retryable: Boolean) : ApiResult<Nothing>
}

/**
 * Talks to YOUR payment server, which holds the Razorpay key secret (see server/ and docs/PAYMENTS.md).
 *  POST {base}/orders  {"appVersion","product"}                 -> {"orderId","amountPaise","currency","keyId"?}
 *  POST {base}/verify  {"orderId","paymentId","signature"}      -> {"verified":true|false}
 *  POST {base}/restore {"paymentId"}                            -> {"pro":true|false}
 */
interface PaymentApi {
    val enabled: Boolean
    suspend fun createOrder(): ApiResult<Order>
    suspend fun verify(orderId: String, paymentId: String, signature: String): ApiResult<Boolean>
    suspend fun restore(paymentId: String): ApiResult<Boolean>
}

object NoPaymentApi : PaymentApi {
    override val enabled = false
    private val none = ApiResult.Failed("No payment server is configured.", retryable = false)
    override suspend fun createOrder(): ApiResult<Order> = none
    override suspend fun verify(orderId: String, paymentId: String, signature: String): ApiResult<Boolean> = none
    override suspend fun restore(paymentId: String): ApiResult<Boolean> = none
}

/** Returns an error message, or null if [url] is an acceptable payment-server address (https, or the emulator's host alias). */
fun validatePaymentApiUrl(url: String): String? {
    val u = url.trim()
    return when {
        u.isEmpty() -> "Enter the payment server address"
        u.any { it.isWhitespace() } -> "The address must not contain spaces"
        u.startsWith("https://") && u.length > 12 -> null
        u.startsWith("http://10.0.2.2") -> null
        else -> "The payment server must use https://"
    }
}

class HttpPaymentApi(baseUrl: String, private val appVersion: String = "") : PaymentApi {
    private val base = baseUrl.trim().trimEnd('/')
    override val enabled = true

    override suspend fun createOrder(): ApiResult<Order> =
        post("/orders", JSONObject().put("appVersion", appVersion).put("product", "pro_lifetime")) { j ->
            val id = j.optString("orderId"); val amount = j.optInt("amountPaise")
            if (id.isBlank() || amount <= 0) ApiResult.Failed("The payment server sent an invalid order.", false)
            else ApiResult.Ok(Order(id, amount, j.optString("currency", "INR"), j.optString("keyId").takeIf { it.isNotBlank() }))
        }

    override suspend fun verify(orderId: String, paymentId: String, signature: String): ApiResult<Boolean> =
        post("/verify", JSONObject().put("orderId", orderId).put("paymentId", paymentId).put("signature", signature)) { ApiResult.Ok(it.optBoolean("verified", false)) }

    override suspend fun restore(paymentId: String): ApiResult<Boolean> =
        post("/restore", JSONObject().put("paymentId", paymentId)) { ApiResult.Ok(it.optBoolean("pro", false)) }

    private suspend fun <T> post(path: String, body: JSONObject, parse: (JSONObject) -> ApiResult<T>): ApiResult<T> = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            when {
                code in 200..299 -> try { parse(JSONObject(c.inputStream.bufferedReader().readText())) } catch (e: org.json.JSONException) { ApiResult.Failed("The payment server sent an unreadable answer.", false) }
                code >= 500 || code == 429 -> ApiResult.Failed("The payment server is busy. Please try again.", true)
                else -> ApiResult.Failed("The payment server refused the request ($code).", false)
            }
        } catch (e: IOException) {
            ApiResult.Failed("Can't reach the payment server. Check your internet connection.", true)
        } finally { c?.disconnect() }
    }
}

sealed interface PaymentEvent {
    /** The checkout reported something (success or error); the outcome follows once it has been processed. */
    data object ResultReceived : PaymentEvent
    data class Unlocked(val paymentId: String) : PaymentEvent
    data object Cancelled : PaymentEvent
    data class Failed(val message: String) : PaymentEvent
}

/** Opens the payment screen. The outcome is reported to [PaymentCoordinator] by the activity that hosts the checkout. Swappable (e.g. for Google Play Billing). */
interface PaymentLauncher { fun start(activity: Activity, config: PaymentConfig, order: Order?) }

/** Razorpay Standard Checkout: UPI apps (PhonePe, Google Pay, Paytm ...), cards, netbanking and wallets enabled on your account. */
class RazorpayLauncher : PaymentLauncher {
    override fun start(activity: Activity, config: PaymentConfig, order: Order?) {
        val checkout = Checkout().apply { setKeyID(order?.keyId ?: config.keyId) }
        checkout.open(activity, buildOptions(config, order, BuildConfig.VERSION_NAME))
    }

    companion object {
        /** Builds the checkout options. Separate from [start] so it can be tested without opening a payment screen. */
        fun buildOptions(config: PaymentConfig, order: Order?, appVersion: String): JSONObject = JSONObject().apply {
            put("name", "Framora")
            put("description", "Framora Pro: no ads, no tag, High quality and more. One-time, for life.")
            put("currency", order?.currency ?: config.currency)
            put("amount", order?.amountPaise ?: config.amountPaise)
            order?.let { put("order_id", it.orderId) }   // a server order shows up under Orders in the Razorpay dashboard and lets the server verify
            // Notes appear on every payment in the dashboard, so transactions can be found and matched to the app.
            put("notes", JSONObject().put("app", "Framora").put("app_version", appVersion).put("product", "pro_lifetime"))
            put("theme", JSONObject().put("color", "#7C5CFF"))
            put("retry", JSONObject().put("enabled", true).put("max_count", 2))
            // In test mode Razorpay cannot complete a payment made through a real UPI app (it ends in its own "Something went wrong"
            // screen), so test builds offer only what works: typing a UPI id (success@razorpay) or a test card.
            if (config.testMode) put("config", testModeDisplay())
        }

        private fun testModeDisplay(): JSONObject {
            fun block(name: String, instrument: JSONObject) = JSONObject().put("name", name).put("instruments", JSONArray().put(instrument))
            val blocks = JSONObject()
                .put("test_upi", block("UPI ID (test: success@razorpay)", JSONObject().put("method", "upi").put("flows", JSONArray().put("collect"))))
                .put("test_card", block("Test card", JSONObject().put("method", "card")))
            return JSONObject().put("display", JSONObject().put("blocks", blocks)
                .put("sequence", JSONArray().put("block.test_upi").put("block.test_card"))
                .put("preferences", JSONObject().put("show_default_blocks", false)))
        }
    }
}

/**
 * Connects the checkout (which reports back to an Activity) with the rest of the app. Pro is granted only after the payment is
 * confirmed: with a payment server, after the server verifies the signature; without one, on Razorpay's success callback.
 * Never grants twice for one payment id, and never loses a payment that was made but could not be confirmed yet.
 */
class PaymentCoordinator(
    private val store: () -> ProStore,
    private val api: PaymentApi,
    private val config: PaymentConfig,
    private val launcher: PaymentLauncher,
    private val grant: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) {
    private val _events = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()

    /** Starts the payment. With a server, an order is created first; its failure is reported without opening checkout. */
    fun start(activity: Activity) {
        if (!config.enabled) { _events.tryEmit(PaymentEvent.Failed("Payments aren't set up in this version of the app.")); return }
        scope.launch {
            store().setPending(true)
            var order: Order? = null
            if (api.enabled) when (val r = api.createOrder()) {
                is ApiResult.Ok -> order = r.value
                is ApiResult.Failed -> { store().setPending(false); _events.emit(PaymentEvent.Failed(r.message)); return@launch }
            }
            withContext(mainDispatcher) {
                try { launcher.start(activity, config, order) }
                catch (e: Exception) { store().setPending(false); _events.emit(PaymentEvent.Failed("The payment screen couldn't open. Please try again.")) }
            }
        }
    }

    fun onSuccess(paymentId: String?, orderId: String? = null, signature: String? = null) {
        scope.launch {
            _events.emit(PaymentEvent.ResultReceived)
            val s = store()
            if (paymentId.isNullOrBlank()) { s.setPending(false); _events.emit(PaymentEvent.Failed("The payment finished but no payment id came back. Please contact support before paying again.")); return@launch }
            if (!api.enabled) { finish(paymentId); return@launch }
            if (orderId.isNullOrBlank() || signature.isNullOrBlank()) {
                s.setPending(false); _events.emit(PaymentEvent.Failed("Your payment ($paymentId) couldn't be confirmed because it has no order signature. Please contact support; don't pay again.")); return@launch
            }
            when (val r = api.verify(orderId, paymentId, signature)) {
                is ApiResult.Ok -> if (r.value) finish(paymentId) else { s.setPending(false); _events.emit(PaymentEvent.Failed("We couldn't verify your payment ($paymentId). Please contact support with this id; don't pay again.")) }
                is ApiResult.Failed -> if (r.retryable) {
                    // The customer has paid: keep what is needed to confirm it later instead of losing it.
                    s.addUnverified(ProStore.Unverified(paymentId, orderId, signature)); s.setPending(false)
                    _events.emit(PaymentEvent.Failed("Your payment ($paymentId) went through and is being confirmed. Reopen the app with internet to finish unlocking; don't pay again."))
                } else { s.setPending(false); _events.emit(PaymentEvent.Failed("We couldn't confirm your payment ($paymentId): ${r.message} Please contact support; don't pay again.")) }
            }
        }
    }

    private suspend fun finish(paymentId: String) { grant(paymentId); store().setPending(false); _events.emit(PaymentEvent.Unlocked(paymentId)) }

    fun onError(code: Int, description: String?) {
        scope.launch {
            _events.emit(PaymentEvent.ResultReceived); store().setPending(false)
            _events.emit(if (code == Checkout.PAYMENT_CANCELED) PaymentEvent.Cancelled else PaymentEvent.Failed(friendly(code)))
        }
    }

    // Razorpay's own error text can contain technical detail; show something short and actionable instead.
    private fun friendly(code: Int) = when (code) {
        Checkout.NETWORK_ERROR -> "No internet connection. Check your network and try again."
        Checkout.INVALID_OPTIONS -> "Payments aren't set up correctly in this version of the app."
        else -> "The payment didn't go through. You haven't been charged unless you see a confirmation from your bank. Please try again."
    }

    /** Tries again to confirm payments that were completed but could not be verified (for example, no network at the time). */
    suspend fun retryUnverified() {
        if (!api.enabled) return
        for (p in store().unverified()) when (val r = api.verify(p.orderId, p.paymentId, p.signature)) {
            is ApiResult.Ok -> { if (r.value) { grant(p.paymentId); _events.emit(PaymentEvent.Unlocked(p.paymentId)) }; store().removeUnverified(p.paymentId) }
            is ApiResult.Failed -> if (!r.retryable) store().removeUnverified(p.paymentId)
        }
    }

    /** Restores Pro on a new phone from a payment id (server needed). Returns a message for the person. */
    suspend fun restore(paymentId: String): String {
        val id = paymentId.trim()
        if (!Regex("^pay_[A-Za-z0-9]{8,30}$").matches(id)) return "That doesn't look like a payment id. It starts with pay_ and is on your Razorpay receipt."
        if (!api.enabled) return "Restoring by payment id needs the payment server. If Google backup was on, Pro returns on its own after you reinstall."
        return when (val r = api.restore(id)) {
            is ApiResult.Ok -> if (r.value) { grant(id); "Pro restored. Thank you!" } else "We couldn't confirm that payment. Check the id, or contact support."
            is ApiResult.Failed -> r.message
        }
    }
}

/** App-wide wiring: one coordinator built from the build's payment config. */
object Payments {
    val config: PaymentConfig = BuildPaymentConfig
    private var _coordinator: PaymentCoordinator? = null
    val coordinator: PaymentCoordinator
        get() = _coordinator ?: PaymentCoordinator({ Pro.store }, if (config.serverVerified) HttpPaymentApi(config.apiUrl, BuildConfig.VERSION_NAME) else NoPaymentApi,
            config, RazorpayLauncher(), { Pro.grant(it) }).also { _coordinator = it }
}
