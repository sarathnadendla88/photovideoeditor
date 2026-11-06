package com.sarath.photovideo

import android.app.Activity
import android.content.Context
import com.razorpay.Checkout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class ProTest {
    private lateinit var ctx: Context
    private lateinit var store: ProStore
    @Before fun setUp() { ctx = org.robolectric.RuntimeEnvironment.getApplication(); store = ProStore(ctx.getSharedPreferences("pro_test_${System.nanoTime()}", Context.MODE_PRIVATE)) }

    // ------------------------------------------------------------------ what Pro changes

    @Test fun freeVersusProRules() {
        assertTrue(Entitlements.quality(ExportQuality.HD, false)); assertFalse(Entitlements.quality(ExportQuality.FHD, false)); assertTrue(Entitlements.quality(ExportQuality.FHD, true))
        assertEquals(ExportQuality.HD, Entitlements.cap(ExportQuality.FHD, false)); assertEquals(ExportQuality.SD, Entitlements.cap(ExportQuality.SD, false)); assertEquals(ExportQuality.FHD, Entitlements.cap(ExportQuality.FHD, true))
        assertEquals(5_000L, Entitlements.gifMaxMs(false)); assertEquals(10_000L, Entitlements.gifMaxMs(true))
        assertFalse(Entitlements.gifSize(GifSize.LARGE, false)); assertTrue(Entitlements.gifSize(GifSize.MEDIUM, false)); assertTrue(Entitlements.gifSize(GifSize.LARGE, true))
        assertFalse(Entitlements.gifFps(20, false)); assertTrue(Entitlements.gifFps(15, false)); assertTrue(Entitlements.gifFps(20, true))
        assertEquals(4, PhotoEffect.entries.count { !Entitlements.effect(it, false) }); assertTrue(PhotoEffect.entries.all { Entitlements.effect(it, true) })
        assertTrue(Entitlements.canSaveAnotherLook(2, false)); assertFalse(Entitlements.canSaveAnotherLook(3, false)); assertTrue(Entitlements.canSaveAnotherLook(300, true))
        assertEquals(5, SlideshowTemplates.count { Entitlements.template(it, false) }); assertEquals(19, SlideshowTemplates.size)
        assertTrue(Entitlements.brandTag(false)); assertFalse(Entitlements.brandTag(true)); assertTrue(Entitlements.adsFree(true)); assertFalse(Entitlements.adsFree(false))
    }

    @Test fun theComparisonTableMatchesTheRealLimits() {
        // The pricing screen is generated from the same constants, so the words cannot drift from the behaviour.
        val gif = Entitlements.table.first { it.title == "GIF maker" }
        assertTrue(gif.free.contains("${Entitlements.FREE_GIF_MS / 1000} s")); assertTrue(gif.pro.contains("${Entitlements.PRO_GIF_MS / 1000} s")); assertTrue(gif.pro.contains("${Entitlements.PRO_GIF_FPS} fps"))
        assertTrue(Entitlements.table.first { it.title == "Saved photo looks" }.free.contains("${Entitlements.FREE_LOOKS}"))
        assertTrue(Entitlements.table.first { it.title == "Slideshow templates" }.let { it.free.startsWith("5") && it.pro.startsWith("${SlideshowTemplates.size}") })
        val fx = Entitlements.table.first { it.title == "Photo effects" }     // "None" is not an effect
        assertEquals("${PhotoEffect.entries.count { it != PhotoEffect.NONE && Entitlements.effect(it, false) }} effects", fx.free); assertEquals("All ${PhotoEffect.entries.size - 1} effects", fx.pro)
    }

    @Test fun priceIsFiftyRupeesByDefault() {
        assertEquals(5000, BuildConfig.PRO_PRICE_PAISE); assertEquals("₹50", BuildPaymentConfig.displayPrice)
        assertEquals("₹50.50", cfg(amount = 5050).displayPrice); assertEquals("₹39", cfg(amount = 3900).displayPrice)
    }

    // ------------------------------------------------------------------ the local record

    @Test fun storeStartsFreeAndUnlockIsIdempotent() {
        assertFalse(store.isPro())
        assertTrue(store.unlock("pay_AAAA1111")); assertTrue(store.isPro())
        assertFalse("the same payment never counts twice", store.unlock("pay_AAAA1111")); assertEquals(listOf("pay_AAAA1111"), store.paymentIds())
        store.unlock("pay_BBBB2222"); assertEquals(2, store.paymentIds().size)
        assertTrue("a new store over the same file still sees Pro (survives restarts)", ProStore(ctx.getSharedPreferences("pro_persist", Context.MODE_PRIVATE).also { ProStore(it).unlock("pay_X1234567") }).isPro())
    }

    @Test fun pendingAndUnverifiedAreRemembered() {
        store.setPending(true); assertTrue(store.pending()); store.setPending(false); assertFalse(store.pending())
        store.addUnverified(ProStore.Unverified("pay_1", "order_1", "sig_1")); store.addUnverified(ProStore.Unverified("pay_2", "order_2", "sig_2"))
        assertEquals(2, store.unverified().size); store.removeUnverified("pay_1"); assertEquals(listOf(ProStore.Unverified("pay_2", "order_2", "sig_2")), store.unverified())
        store.reset(); assertTrue(store.unverified().isEmpty()); assertFalse(store.isPro())
    }

    // ------------------------------------------------------------------ checkout options

    private fun cfg(key: String = "rzp_test_abc123456789", amount: Int = 5000, api: String = "") = object : PaymentConfig {
        override val keyId = key; override val amountPaise = amount; override val apiUrl = api
    }

    @Test fun checkoutOptionsCarryTheRightAmountAndNotes() {
        val o = RazorpayLauncher.buildOptions(cfg(), null, "1.2.3")
        assertEquals(5000, o.getInt("amount")); assertEquals("INR", o.getString("currency")); assertEquals("ReelMint", o.getString("name"))
        assertFalse("no order id without a server", o.has("order_id"))
        val n = o.getJSONObject("notes"); assertEquals("pro_lifetime", n.getString("product")); assertEquals("1.2.3", n.getString("app_version")); assertEquals("ReelMint", n.getString("app"))
        assertTrue(o.getJSONObject("retry").getBoolean("enabled"))
    }

    @Test fun aServerOrderOverridesAmountAndAddsTheOrderId() {
        val o = RazorpayLauncher.buildOptions(cfg(), Order("order_ABC123", 3000, "INR", "rzp_test_srv"), "1")
        assertEquals(3000, o.getInt("amount")); assertEquals("order_ABC123", o.getString("order_id"))
    }

    @Test fun testModeOffersOnlyWhatWorksAndLiveModeDoesNot() {
        assertTrue(cfg().testMode); assertTrue(RazorpayLauncher.buildOptions(cfg(), null, "1").has("config"))
        val live = cfg(key = "rzp_live_abc123456789"); assertFalse(live.testMode); assertFalse("live checkout must show all real methods", RazorpayLauncher.buildOptions(live, null, "1").has("config"))
    }

    @Test fun noKeyMeansPaymentsAreOff() { assertFalse(cfg(key = "").enabled); assertTrue(cfg().enabled); assertFalse(cfg().serverVerified); assertTrue(cfg(api = "https://pay.example.com").serverVerified) }

    @Test fun serverUrlsMustBeHttps() {
        assertNull(validatePaymentApiUrl("https://pay.example.com")); assertNull(validatePaymentApiUrl("http://10.0.2.2:8080"))
        assertNotNull(validatePaymentApiUrl("http://example.com")); assertNotNull(validatePaymentApiUrl("")); assertNotNull(validatePaymentApiUrl("https://a b.com")); assertNotNull(validatePaymentApiUrl("https://"))
    }

    // ------------------------------------------------------------------ the payment flow

    private class FakeApi(var order: ApiResult<Order> = ApiResult.Ok(Order("order_1", 5000, "INR", null)), var verify: ApiResult<Boolean> = ApiResult.Ok(true), var restore: ApiResult<Boolean> = ApiResult.Ok(true), override val enabled: Boolean = true) : PaymentApi {
        var verifyCalls = 0; var restoreCalls = 0; var orderCalls = 0
        override suspend fun createOrder(): ApiResult<Order> { orderCalls++; return order }
        override suspend fun verify(orderId: String, paymentId: String, signature: String): ApiResult<Boolean> { verifyCalls++; return verify }
        override suspend fun restore(paymentId: String): ApiResult<Boolean> { restoreCalls++; return restore }
    }
    private class FakeLauncher(var fail: Boolean = false) : PaymentLauncher {
        var starts = 0; var lastOrder: Order? = null
        override fun start(activity: Activity, config: PaymentConfig, order: Order?) { starts++; lastOrder = order; if (fail) error("boom") }
    }
    private class Rig(val store: ProStore, api: PaymentApi, val launcher: FakeLauncher = FakeLauncher(), config: PaymentConfig) {
        val granted = mutableListOf<String>(); val seen = mutableListOf<PaymentEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val c = PaymentCoordinator({ store }, api, config, launcher, { granted += it; store.unlock(it) }, scope, Dispatchers.Unconfined)
        init { scope.launch { c.events.collect { seen += it } } }
        fun failedText() = (seen.filterIsInstance<PaymentEvent.Failed>().lastOrNull())?.message
    }
    private fun rig(api: PaymentApi = NoPaymentApi, config: PaymentConfig = cfg(), launcher: FakeLauncher = FakeLauncher()) = Rig(store, api, launcher, config)
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test fun appOnlySuccessUnlocksProOnceAndTellsTheUi() {
        val r = rig(); r.c.onSuccess("pay_ABCD1234")
        assertEquals(listOf("pay_ABCD1234"), r.granted); assertTrue(store.isPro()); assertFalse(store.pending())
        assertEquals(listOf(PaymentEvent.ResultReceived, PaymentEvent.Unlocked("pay_ABCD1234")), r.seen)
        r.c.onSuccess("pay_ABCD1234")   // Razorpay delivering the same callback twice
        assertEquals("one payment is one purchase", 1, store.paymentIds().size)
    }

    @Test fun aSuccessWithoutAPaymentIdNeverUnlocks() {
        val r = rig(); r.c.onSuccess(null); r.c.onSuccess("  ")
        assertFalse(store.isPro()); assertTrue(r.granted.isEmpty()); assertNotNull(r.failedText())
    }

    @Test fun cancelAndErrorsAreReportedWithoutUnlocking() {
        val r = rig(); store.setPending(true)
        r.c.onError(Checkout.PAYMENT_CANCELED, "User cancelled"); assertTrue(r.seen.contains(PaymentEvent.Cancelled)); assertFalse(store.pending()); assertFalse(store.isPro())
        r.c.onError(Checkout.NETWORK_ERROR, "raw technical text"); assertTrue(r.failedText()!!.contains("internet"))
        r.c.onError(Checkout.INVALID_OPTIONS, "raw"); assertTrue(r.failedText()!!.contains("set up"))
        r.c.onError(99, "java.lang.Exception: secret internals"); assertFalse("raw provider text must never reach the user", r.failedText()!!.contains("secret internals")); assertFalse(store.isPro())
    }

    @Test fun serverVerifiedPaymentUnlocksOnlyWhenTheServerSaysSo() {
        val ok = FakeApi(); val r = rig(ok); r.c.onSuccess("pay_OK123456", "order_1", "sig")
        assertEquals(1, ok.verifyCalls); assertTrue(store.isPro()); assertEquals(listOf("pay_OK123456"), r.granted)
    }

    @Test fun aForgedSignatureNeverUnlocks() {
        val api = FakeApi(verify = ApiResult.Ok(false)); val r = rig(api); r.c.onSuccess("pay_FAKE1234", "order_1", "bad")
        assertFalse(store.isPro()); assertTrue(r.granted.isEmpty()); assertTrue(r.failedText()!!.contains("pay_FAKE1234")); assertTrue(store.unverified().isEmpty())
    }

    @Test fun serverModeRefusesASuccessWithoutAnOrderSignature() {
        val api = FakeApi(); val r = rig(api); r.c.onSuccess("pay_NOSIG123", null, null); r.c.onSuccess("pay_NOSIG123", "order_1", "")
        assertEquals("the server is never asked to verify an unsigned payment", 0, api.verifyCalls); assertFalse(store.isPro())
    }

    @Test fun aPaidButUnconfirmedPaymentIsKeptAndFinishedLater() {
        val api = FakeApi(verify = ApiResult.Failed("busy", retryable = true)); val r = rig(api)
        r.c.onSuccess("pay_LATER123", "order_9", "sig9")
        assertFalse(store.isPro()); assertEquals(1, store.unverified().size); assertTrue(r.failedText()!!.contains("don't pay again"))
        runBlocking { r.c.retryUnverified() }; assertFalse("still failing → still waiting, nothing lost", store.isPro()); assertEquals(1, store.unverified().size)
        api.verify = ApiResult.Ok(true); runBlocking { r.c.retryUnverified() }
        assertTrue(store.isPro()); assertTrue(store.unverified().isEmpty()); assertTrue(r.seen.contains(PaymentEvent.Unlocked("pay_LATER123")))
    }

    @Test fun aDefiniteNoFromTheServerDropsTheRetry() {
        val api = FakeApi(verify = ApiResult.Failed("refused (400)", retryable = false)); val r = rig(api)
        store.addUnverified(ProStore.Unverified("pay_BAD12345", "order_1", "sig")); runBlocking { r.c.retryUnverified() }
        assertTrue(store.unverified().isEmpty()); assertFalse(store.isPro())
    }

    @Test fun startOpensCheckoutOnlyWhenPaymentsAreSetUp() {
        val off = rig(config = cfg(key = "")); off.c.start(activity()); assertEquals(0, off.launcher.starts); assertNotNull(off.failedText()); assertFalse(store.pending())
        val on = rig(); on.c.start(activity()); assertEquals(1, on.launcher.starts); assertNull(on.launcher.lastOrder); assertTrue(store.pending())
    }

    @Test fun startCreatesAServerOrderFirstAndStopsIfThatFails() {
        val good = FakeApi(order = ApiResult.Ok(Order("order_77", 5000, "INR", "rzp_test_x"))); val a = rig(good); a.c.start(activity())
        assertEquals(1, good.orderCalls); assertEquals("order_77", a.launcher.lastOrder?.orderId)
        val bad = FakeApi(order = ApiResult.Failed("Can't reach the payment server.", true)); val b = rig(bad); b.c.start(activity())
        assertEquals("no checkout without an order", 0, b.launcher.starts); assertEquals("Can't reach the payment server.", b.failedText()); assertFalse(store.pending().and(b.launcher.starts > 0))
    }

    @Test fun aCheckoutThatCannotOpenIsReportedNotSwallowed() {
        val r = rig(launcher = FakeLauncher(fail = true)); r.c.start(activity()); assertNotNull(r.failedText()); assertFalse(store.pending())
    }

    @Test fun restoreValidatesTheIdAndNeedsAServer() = runBlocking {
        val api = FakeApi(); val r = rig(api)
        assertTrue(r.c.restore("hello").contains("pay_")); assertEquals("bad ids never hit the server", 0, api.restoreCalls)
        assertEquals("Pro restored. Thank you!", r.c.restore(" pay_Restore12345 ")); assertTrue(store.isPro()); assertEquals(listOf("pay_Restore12345"), r.granted)
        store.reset(); api.restore = ApiResult.Ok(false); assertTrue(r.c.restore("pay_Other123456").contains("couldn't confirm")); assertFalse(store.isPro())
        api.restore = ApiResult.Failed("busy", true); assertEquals("busy", r.c.restore("pay_Other123456"))
        val noServer = rig(NoPaymentApi); assertTrue(noServer.c.restore("pay_Restore12345").contains("backup")); assertFalse(store.isPro())
    }

    // ------------------------------------------------------------------ talking to a real HTTP server

    /** A tiny real HTTP/1.1 server on localhost (the JDK's built-in one is not visible to Android unit tests). */
    private class TestServer(val handler: (path: String, body: String) -> Pair<Int, String>) {
        private val socket = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { c ->
                    val input = c.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val ch = input.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) sb.append(ch.toChar()) }; return sb.toString() }
                    val path = line().split(" ").getOrElse(1) { "/" }
                    var len = 0; while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) len = h.substringAfter(":").trim().toInt() }
                    val body = String(ByteArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } })
                    val (code, resp) = handler(path, body); val b = resp.toByteArray()
                    c.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
                }
            } catch (e: java.io.IOException) { /* closed */ }
        }.also { it.isDaemon = true; it.start() }
        fun stop() = socket.close()
    }
    private fun server(handler: (path: String, body: String) -> Pair<Int, String>): Pair<TestServer, String> = TestServer(handler).let { it to it.url }

    @Test fun httpApiSpeaksTheDocumentedProtocol() = runBlocking {
        val seen = mutableListOf<Pair<String, String>>()
        val (srv, url) = server { path, body -> seen += path to body; when (path) {
            "/orders" -> 200 to """{"orderId":"order_1","amountPaise":5000,"currency":"INR","keyId":"rzp_test_k"}"""
            "/verify" -> 200 to """{"verified":true}"""; "/restore" -> 200 to """{"pro":true}"""; else -> 404 to "{}" } }
        try {
            val api = HttpPaymentApi(url, "1.0")
            assertEquals(Order("order_1", 5000, "INR", "rzp_test_k"), (api.createOrder() as ApiResult.Ok).value)
            assertTrue((api.verify("order_1", "pay_1", "sig") as ApiResult.Ok).value); assertTrue((api.restore("pay_1") as ApiResult.Ok).value)
            val o = org.json.JSONObject(seen.first { it.first == "/orders" }.second); assertEquals("pro_lifetime", o.getString("product")); assertEquals("1.0", o.getString("appVersion"))
            val v = org.json.JSONObject(seen.first { it.first == "/verify" }.second); assertEquals("order_1", v.getString("orderId")); assertEquals("pay_1", v.getString("paymentId")); assertEquals("sig", v.getString("signature"))
            assertFalse("the app never sends a Razorpay secret", seen.any { it.second.contains("secret", ignoreCase = true) })
        } finally { srv.stop() }
    }

    @Test fun httpApiClassifiesFailuresCorrectly() = runBlocking {
        var mode = 500
        val (srv, url) = server { _, _ -> when (mode) { 200 -> 200 to "not json"; 201 -> 200 to """{"orderId":"","amountPaise":0}"""; else -> mode to "{}" } }
        try {
            val api = HttpPaymentApi(url)
            mode = 500; assertTrue((api.verify("o", "p", "s") as ApiResult.Failed).retryable)
            mode = 429; assertTrue((api.verify("o", "p", "s") as ApiResult.Failed).retryable)
            mode = 400; assertFalse((api.verify("o", "p", "s") as ApiResult.Failed).retryable)
            mode = 200; assertFalse((api.verify("o", "p", "s") as ApiResult.Failed).retryable)
            mode = 201; assertFalse("an order with no id or amount is rejected", (api.createOrder() as ApiResult.Failed).retryable)
        } finally { srv.stop() }
        val dead = HttpPaymentApi("http://127.0.0.1:1"); assertTrue("no connection is retryable", (dead.createOrder() as ApiResult.Failed).retryable)
    }
}
