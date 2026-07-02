package co.adsparkle.sdk

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AdSparkle — Android client SDK for the AdSparkle tracking platform.
 *
 * Mobile apps use this singleton to forward affiliate attribution events
 * (install, sign-up, purchase, ...) to the tracking backend.
 *
 * Typical usage:
 * ```kotlin
 * AdSparkle.configure(context, companyKey = "co_xxx")
 * AdSparkle.handleDeepLink(intent.data)   // capture click_id from the deep link
 * AdSparkle.setUserId("user-123")
 * AdSparkle.trackInstall()
 * AdSparkle.trackPurchase(AdSparkleEvent(transactionId = "txn_1", amount = 9.99, currency = "USD"))
 * ```
 *
 * Thread-safety: every public method returns immediately. All blocking work
 * (persistence-backed networking) runs on a single-thread [ExecutorService] so
 * the calling (main) thread is never blocked and events are sent in order.
 *
 * Security: [configure]'s `companyKey` is a *publishable* `co_` key. It is safe
 * to embed in an app. HMAC signing secrets are never used on the client.
 */
object AdSparkle {

    private const val TAG = "AdSparkle"
    const val DEFAULT_BASE_URL = "https://api.adsparkle.co"

    /** The fixed set of server-recognized event types. */
    object EventType {
        const val INSTALL = "install"
        const val SIGN_UP = "sign_up"
        const val LOGIN = "login"
        const val DOWNLOAD = "download"
        const val PURCHASE = "purchase"
        const val SUBSCRIPTION = "subscription"
        const val REFUND = "refund"

        internal val ALL = setOf(
            INSTALL, SIGN_UP, LOGIN, DOWNLOAD, PURCHASE, SUBSCRIPTION, REFUND
        )
    }

    // Accepted event_type shape: a built-in key (e.g. "purchase") OR a company
    // custom-event shortId (e.g. "YE2YFSQ"). Mixed case on purpose — shortIds are
    // uppercase, built-in keys lowercase. Mirrors the backend's /^[a-zA-Z0-9_]+$/
    // (1-64 chars). EventType constants above remain as convenience.
    private val EVENT_TYPE_RE = Regex("^[A-Za-z0-9_]{1,64}$")

    // Guards initialization and mutation of configuration fields.
    private val lock = Any()

    @Volatile private var storage: Storage? = null
    @Volatile private var client: PostbackClient? = null
    @Volatile private var executor: ExecutorService? = null

    @Volatile private var companyKey: String? = null
    @Volatile private var baseUrl: String = DEFAULT_BASE_URL
    @Volatile private var debug: Boolean = false

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Initializes the SDK. Call once, as early as possible (e.g. in
     * `Application.onCreate`). Safe to call again to update configuration.
     *
     * @param context     any context; the application context is retained.
     * @param companyKey  publishable `co_` key (not a secret).
     * @param baseUrl     tracking API base url; defaults to [DEFAULT_BASE_URL].
     * @param debug       enables verbose logging.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        companyKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        debug: Boolean = false,
    ) {
        synchronized(lock) {
            val store = storage ?: Storage(context.applicationContext).also { storage = it }

            this.debug = debug
            this.companyKey = companyKey
            this.baseUrl = baseUrl.ifBlank { DEFAULT_BASE_URL }

            store.companyKey = this.companyKey
            store.baseUrl = this.baseUrl

            if (client == null) client = PostbackClient(debug)
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "AdSparkle-Worker").apply { isDaemon = true }
                }
            }

            if (debug) Log.d(TAG, "Configured. baseUrl=${this.baseUrl}")
        }

        // Attempt to send anything that failed on a previous run.
        flushPending()
    }

    // -------------------------------------------------------------------------
    // Identity & attribution
    // -------------------------------------------------------------------------

    /** Sets the current user id; persisted and attached to every event. */
    @JvmStatic
    fun setUserId(userId: String) {
        val store = storage
        if (store == null) {
            warnNotConfigured("setUserId")
            return
        }
        store.userId = userId
        if (debug) Log.d(TAG, "userId set")
    }

    /**
     * Extracts `click_id` from a deep-link [uri] (`?click_id=<uuid>`), persists
     * it as the active click id, and appends it to the click chain (de-duped,
     * max 50, 7-day sliding TTL). No-op when [uri] is null or carries no valid
     * click id.
     */
    @JvmStatic
    fun handleDeepLink(uri: Uri?) {
        val clickId = DeepLink.extractClickId(uri) ?: run {
            if (debug) Log.d(TAG, "handleDeepLink: no click_id in uri")
            return
        }
        setClickId(clickId)
    }

    /**
     * Sets the active click id directly and appends it to the chain. Silently
     * ignores values that are not well-formed UUIDs (parity with adsparkle.js).
     */
    @JvmStatic
    fun setClickId(clickId: String) {
        val store = storage
        if (store == null) {
            warnNotConfigured("setClickId")
            return
        }
        val trimmed = clickId.trim()
        if (trimmed.isEmpty()) return
        if (!Storage.UUID_RE.matches(trimmed)) {
            if (debug) Log.w(TAG, "setClickId ignored: not a valid UUID")
            return
        }
        store.clickId = trimmed
        store.addClickId(trimmed)
        if (debug) Log.d(TAG, "clickId captured")
    }

    /** Returns the currently active click id, or null if none captured yet. */
    @JvmStatic
    fun getClickId(): String? = storage?.clickId

    // -------------------------------------------------------------------------
    // Tracking
    // -------------------------------------------------------------------------

    /**
     * Tracks an event of [eventType] (must be one of [EventType]). The optional
     * [event] carries transaction/amount/product/custom data.
     *
     * Validates locally and silently skips (logging a warning in debug mode,
     * never throwing) when: not configured, unknown event type, or missing
     * click id / user id.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventType: String, event: AdSparkleEvent = AdSparkleEvent()) {
        val store = storage
        val key = companyKey
        val httpClient = client
        val exec = executor

        if (store == null || key == null || httpClient == null || exec == null) {
            warnNotConfigured("track")
            return
        }

        if (!EVENT_TYPE_RE.matches(eventType)) {
            if (debug) Log.w(TAG, "invalid event_type: $eventType")
            return
        }

        val clickId = store.clickId

        if (clickId.isNullOrEmpty()) {
            if (debug) Log.w(TAG, "track skipped: no click_id (call handleDeepLink/setClickId first)")
            return
        }

        // Anonymous fallback (parity with adsparkle.js getOrCreateAnonId): never
        // drop a conversion just because the merchant did not call setUserId —
        // mint and persist a stable anonymous id instead.
        val userId = store.userId?.takeIf { it.isNotEmpty() } ?: getOrCreateAnonId(store)

        val clickIds = store.getClickIds()
        val payload = buildPayload(eventType, clickId, clickIds, userId, event)
        val currentBaseUrl = baseUrl

        exec.execute {
            // Opportunistically flush any backlog first so ordering is preserved.
            drainPendingInternal(store, httpClient, key, currentBaseUrl)

            val ok = httpClient.send(currentBaseUrl, key, payload)
            if (!ok) {
                store.enqueuePending(payload)
                if (debug) Log.w(TAG, "Event queued for retry: $eventType")
            }
        }
    }

    /**
     * Re-attempts delivery of any events that were persisted after exhausting
     * their retries. Safe to call anytime; runs on the background worker.
     */
    @JvmStatic
    fun flushPending() {
        val store = storage ?: return
        val key = companyKey ?: return
        val httpClient = client ?: return
        val exec = executor ?: return
        val currentBaseUrl = baseUrl
        exec.execute {
            drainPendingInternal(store, httpClient, key, currentBaseUrl)
        }
    }

    // ---- Typed convenience helpers ----

    @JvmStatic @JvmOverloads
    fun trackInstall(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.INSTALL, event)

    @JvmStatic @JvmOverloads
    fun trackSignUp(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.SIGN_UP, event)

    @JvmStatic @JvmOverloads
    fun trackLogin(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.LOGIN, event)

    @JvmStatic @JvmOverloads
    fun trackDownload(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.DOWNLOAD, event)

    @JvmStatic @JvmOverloads
    fun trackPurchase(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.PURCHASE, event)

    @JvmStatic @JvmOverloads
    fun trackSubscription(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.SUBSCRIPTION, event)

    @JvmStatic @JvmOverloads
    fun trackRefund(event: AdSparkleEvent = AdSparkleEvent()) = track(EventType.REFUND, event)

    // ---- Adjust-style otomatik ürün yakalama (Play Billing) ----
    //
    // Play Billing `Purchase` objesinden ürün kimliklerini (ve order id'yi)
    // KENDİLİĞİNDEN çıkarır; merchant SKU'yu elle yazmak zorunda kalmaz. Web
    // SDK'daki dataLayer otomatik yakalamanın mobil karşılığı — mobilde ödeme
    // Play Store üzerinden geçtiği için ürün kimliği makbuzda zaten vardır.
    //
    // ÖNEMLİ: billingclient'a HARD bağımlılık EKLENMEZ. `Purchase` objesi
    // reflection ile okunur; böylece Play Billing kullanmayan uygulamalar
    // etkilenmez ve SDK bağımlılık-yüzeyi büyümez.
    //
    // amount/currency Purchase'da bulunmadığı için yüzde komisyonlu event'lerde
    // merchant tarafından geçilmelidir.

    @JvmStatic @JvmOverloads
    fun trackPurchaseFromBilling(
        purchase: Any,
        amount: Double? = null,
        currency: String? = null,
        customParams: Map<String, String>? = null,
    ) = trackFromBilling(EventType.PURCHASE, purchase, amount, currency, customParams)

    @JvmStatic @JvmOverloads
    fun trackSubscriptionFromBilling(
        purchase: Any,
        amount: Double? = null,
        currency: String? = null,
        customParams: Map<String, String>? = null,
    ) = trackFromBilling(EventType.SUBSCRIPTION, purchase, amount, currency, customParams)

    private fun trackFromBilling(
        eventType: String,
        purchase: Any,
        amount: Double?,
        currency: String?,
        customParams: Map<String, String>?,
    ) {
        val productIds = extractBillingProductIds(purchase)
        track(
            eventType,
            AdSparkleEvent(
                transactionId = extractBillingTransactionId(purchase),
                amount = amount,
                currency = currency,
                productIds = productIds.takeIf { it.isNotEmpty() },
                customParams = customParams,
            ),
        )
    }

    /** Play Billing Purchase.getProducts() (yeni API) → getSkus() (eski) reflection ile. */
    private fun extractBillingProductIds(purchase: Any): List<String> {
        for (method in listOf("getProducts", "getSkus")) {
            try {
                val result = purchase.javaClass.getMethod(method).invoke(purchase)
                if (result is List<*>) {
                    val ids = result.filterIsInstance<String>()
                    if (ids.isNotEmpty()) return ids
                }
            } catch (_: Exception) { /* bir sonraki method adını dene */ }
        }
        return emptyList()
    }

    /** Play Billing Purchase.getOrderId() → getPurchaseToken() reflection ile. */
    private fun extractBillingTransactionId(purchase: Any): String? {
        for (method in listOf("getOrderId", "getPurchaseToken")) {
            try {
                val result = purchase.javaClass.getMethod(method).invoke(purchase)
                if (result is String && result.isNotEmpty()) return result
            } catch (_: Exception) { /* bir sonraki method adını dene */ }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Must be called on the background worker. Sends and drops on success. */
    private fun drainPendingInternal(
        store: Storage,
        httpClient: PostbackClient,
        key: String,
        currentBaseUrl: String,
    ) {
        val pending = store.drainPendingQueue()
        if (pending.isEmpty()) return
        val stillFailing = ArrayList<String>()
        for (payload in pending) {
            val ok = httpClient.send(currentBaseUrl, key, payload)
            if (!ok) stillFailing.add(payload)
        }
        // Re-queue the ones that failed again so they survive to the next flush.
        for (payload in stillFailing) {
            store.enqueuePending(payload)
        }
        if (debug && stillFailing.isNotEmpty()) {
            Log.w(TAG, "Flush incomplete: ${stillFailing.size} event(s) still pending")
        }
    }

    private fun buildPayload(
        eventType: String,
        clickId: String,
        clickIds: List<String>,
        userId: String,
        event: AdSparkleEvent,
    ): String {
        val json = JSONObject()
        json.put("click_id", clickId)
        // click_ids is always present (parity with adsparkle.js): the chain is
        // non-empty whenever a conversion fires, since click_id is mandatory. If
        // the persisted chain expired under TTL between capture and send, fall
        // back to the active click_id so attribution is never lost.
        val chain = if (clickIds.isNotEmpty()) clickIds else listOf(clickId)
        json.put("click_ids", JSONArray(chain))
        json.put("event_type", eventType)
        json.put("user_id", userId)

        event.transactionId?.let { json.put("transaction_id", it) }
        event.amount?.let { json.put("amount", it) }
        event.currency?.let { json.put("currency", it) }

        event.productIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            json.put("product_ids", JSONArray(ids))
        }

        event.customParams?.takeIf { it.isNotEmpty() }?.let { params ->
            val obj = JSONObject()
            for ((k, v) in params) obj.put(k, v)
            json.put("custom_params", obj)
        }

        return json.toString()
    }

    /** Charset for base36 random suffix (0-9a-z), matching JS toString(36). */
    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * Returns the persisted user id, or mints, persists, and returns a new
     * anonymous one: `anon_` + base36(now) + 8 base36 random chars (parity with
     * adsparkle.js getOrCreateAnonId). The value need not be cryptographically
     * random — uniqueness, not unpredictability, is the goal.
     */
    private fun getOrCreateAnonId(store: Storage): String {
        store.userId?.takeIf { it.isNotEmpty() }?.let { return it }
        val random = Random()
        val suffix = StringBuilder(8)
        repeat(8) { suffix.append(BASE36[random.nextInt(BASE36.length)]) }
        val anon = "anon_" + System.currentTimeMillis().toString(36) + suffix
        store.userId = anon
        if (debug) Log.d(TAG, "anonymous user_id generated")
        return anon
    }

    private fun warnNotConfigured(method: String) {
        // Logged unconditionally: calling before configure() is a programming error.
        Log.w(TAG, "$method() called before configure(); ignoring.")
    }
}
