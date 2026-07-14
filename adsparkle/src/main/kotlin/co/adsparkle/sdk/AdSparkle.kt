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
 * ADIM 4: SDK çalışma ortamı. [SANDBOX] → tüm giden isteklere (`postback`,
 * `register-click`) `test: true` eklenir; backend ClickEvent YAZMAZ, postback
 * yalnızca şekil-doğrulanır (ledger etkilenmez). Android'de `/match` yoktur (S4).
 * Varsayılan [PRODUCTION]. Kotlin enum → Java'dan da erişilir.
 */
enum class AdSparkleEnvironment { PRODUCTION, SANDBOX }

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

    // ADIM 5: App Links link-domain soneki. click_id TASIMAYAN bir uri'nin host'u
    // bununla bitiyorsa (`<slug>.go.adsparkle.co`) register-click ile deterministic
    // click uretilir. Sadece bu son-eke sahip host'lar register-click tetikler (E1).
    // Varsayilan prod domaini; `configure(linkDomainSuffix = ...)` ile override edilebilir
    // (test/prod farkli link domaini — backend LINK_DOMAIN_SUFFIX env'iyle esler).
    const val DEFAULT_LINK_DOMAIN_SUFFIX = ".go.adsparkle.co"

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
    /** ADIM 4: sandbox modu mu? true ise giden body'lere `test: true` eklenir. */
    @Volatile private var isSandbox: Boolean = false
    /** ADIM 5: App Links link-domain soneki (configure ile override edilebilir; @Volatile → thread-safe). */
    @Volatile private var linkDomainSuffix: String = DEFAULT_LINK_DOMAIN_SUFFIX

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
        // ADIM 4: @JvmOverloads TUZAĞI — environment EN SONA konur (debug'dan sonra).
        // Aksi halde eski `configure(context, companyKey, baseUrl, debug)` Java overload'ı
        // 4. arg'ı environment'a maplar → tip uyuşmazlığı. Sona koyunca eski imza korunur.
        environment: AdSparkleEnvironment = AdSparkleEnvironment.PRODUCTION,
        // ADIM 5: link domain soneki EN SONA konur (yukaridaki @JvmOverloads tuzagi geregi —
        // yeni param sona → eski Java overload imzalari korunur).
        linkDomainSuffix: String = DEFAULT_LINK_DOMAIN_SUFFIX,
    ) {
        synchronized(lock) {
            val store = storage ?: Storage(context.applicationContext).also { storage = it }

            this.debug = debug
            this.companyKey = companyKey
            this.baseUrl = baseUrl.ifBlank { DEFAULT_BASE_URL }
            this.isSandbox = (environment == AdSparkleEnvironment.SANDBOX)
            // Bas nokta + lowercase normalize (host karsilastirmasi lowercase host + `.suffix`).
            val normSuffix = linkDomainSuffix.lowercase()
            this.linkDomainSuffix = if (normSuffix.startsWith(".")) normSuffix else ".$normSuffix"

            store.companyKey = this.companyKey
            store.baseUrl = this.baseUrl
            store.isSandbox = this.isSandbox

            if (client == null) client = PostbackClient(debug)
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "AdSparkle-Worker").apply { isDaemon = true }
                }
            }

            if (debug) Log.d(TAG, "Configured. baseUrl=${this.baseUrl}")
        }

        // Android deferred attribution onceligi (ADIM 5 + KARAR 3). Yalnizca HALA
        // click_id yoksa calisir (handleDeepLink araya girmis olabilir):
        //   1. click_id zaten var        → hicbir sey yapma.
        //   2. bekleyen register-click   → App Links DETERMINISTIC click; Install
        //      (App Links, app yuklu)      Referrer'DAN ONCE dene (E3 retry).
        //   3. Play Install Referrer      → BIR KEZ oku, `referrer=click_id=<uuid>`.
        //   4. /match CAGIRMA             → Android'de probabilistic eslesme yok (S4).
        // Referrer callback async doner; o sirada click_id set edilmis olabilecegi
        // icin yalnizca HALA bos ise uygulanir.
        storage?.let { store ->
            if (store.clickId.isNullOrEmpty()) {
                if (store.pendingRegisterClick != null) {
                    attemptRegisterClick(store)
                } else if (!store.referrerChecked) {
                    store.referrerChecked = true
                    InstallReferrerReader.fetch(context.applicationContext, debug) { clickId ->
                        if (getClickId().isNullOrEmpty()) setClickId(clickId)
                    }
                }
            }
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
     * Handles a deep-link / App Links [uri].
     *
     * 1. `?click_id=<uuid>` tasiyorsa: onu aktif click id yapar ve zincire ekler
     *    (de-duped, max 50, 7-gun kayan TTL).
     * 2. click_id YOKSA ama host `*.go.adsparkle.co` ise (App Links ile app YUKLU
     *    acilmis → sunucuya ugranmamis): ADIM 5 register-click ile deterministic
     *    click'i APP olusturur. Istek [Storage.pendingRegisterClick]'e yazilir ve
     *    hemen denenir; hata olursa saklanip configure()/track()'te tekrar denenir
     *    (E3). Cihaz fingerprint'i GONDERILMEZ.
     *
     * [uri] null / gecersiz / bilinmeyen host ise no-op.
     */
    @JvmStatic
    fun handleDeepLink(uri: Uri?) {
        // (1) URL'de dogrudan click_id → onu kullan (mevcut davranis).
        val directClickId = DeepLink.extractClickId(uri)
        if (directClickId != null) {
            setClickId(directClickId)
            return
        }

        // Zaten bir click_id yakalanmissa register-click'e gerek yok.
        if (!getClickId().isNullOrEmpty()) return

        // (2) E1: click_id yok — host link-domain mi? Degilse no-op.
        val target = DeepLink.extractLinkTarget(uri)
        if (target == null || !target.host.endsWith(linkDomainSuffix)) {
            if (debug) Log.d(TAG, "handleDeepLink: no click_id / not a link-domain uri")
            return
        }

        val store = storage ?: run {
            warnNotConfigured("handleDeepLink")
            return
        }

        // E2: bekleyen register-click istegini kalici sakla (unique_key + query),
        // sonra dene (E3 retry mekanizmasi). platform/device_id RegisterClient'ta.
        val pending = JSONObject()
        pending.put("unique_key", target.uniqueKey)
        val qp = JSONObject()
        for ((k, v) in target.queryParams) qp.put(k, v)
        pending.put("query_params", qp)
        store.pendingRegisterClick = pending.toString()

        attemptRegisterClick(store)
    }

    /**
     * ADIM 5: bekleyen register-click istegini backend'e gonderir (E3).
     *
     * Async (executor uzerinde). Basari → click_id set edilir ve pending temizlenir.
     * Hata (4xx/5xx/ag) → pending KORUNUR; sonraki configure()/track()/deep-link'te
     * tekrar denenir. click_id bu arada baska bir yoldan geldiyse (yaris) no-op.
     */
    private fun attemptRegisterClick(store: Storage) {
        val key = companyKey ?: return
        val exec = executor ?: return
        if (!getClickId().isNullOrEmpty()) return
        val pendingJson = store.pendingRegisterClick ?: return
        val currentBaseUrl = baseUrl
        val deviceId = store.getOrCreateDeviceId()

        exec.execute {
            // Yaris: baska bir yol (dogrudan click_id / referrer) araya girmis olabilir.
            if (!getClickId().isNullOrEmpty()) return@execute
            val parsed = try {
                JSONObject(pendingJson)
            } catch (_: Exception) {
                store.pendingRegisterClick = null // bozuk kayit → temizle
                return@execute
            }
            val uniqueKey = parsed.optString("unique_key", "")
            if (uniqueKey.isEmpty()) {
                store.pendingRegisterClick = null
                return@execute
            }
            val params = LinkedHashMap<String, String>()
            val qpObj = parsed.optJSONObject("query_params")
            if (qpObj != null) {
                val it = qpObj.keys()
                while (it.hasNext()) {
                    val name = it.next()
                    params[name] = qpObj.optString(name, "")
                }
            }
            val referrer = parsed.optString("referrer").takeIf { it.isNotEmpty() }

            val clickId = RegisterClient.resolve(
                baseUrl = currentBaseUrl,
                companyKey = key,
                uniqueKey = uniqueKey,
                deviceId = deviceId,
                queryParams = params,
                referrer = referrer,
                test = isSandbox,
                debug = debug,
            )
            if (clickId != null) {
                store.pendingRegisterClick = null // basari → temizle (E3)
                if (getClickId().isNullOrEmpty()) setClickId(clickId)
            }
            // clickId == null: pending KORUNUR — bir sonraki tetikte tekrar denenir.
        }
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
        // ADIM 6a: click_id geldi → bekleyen deferred olaylari (install-oncesi track'ler)
        // gonder. flush once kuyrugu bosaltir, sonra her birini track() ile yollar.
        flushDeferred(store)
    }

    /** Returns the currently active click id, or null if none captured yet. */
    @JvmStatic
    fun getClickId(): String? = storage?.clickId

    // -------------------------------------------------------------------------
    // ADIM 6a: Deferred events (click_id henuz yokken cagrilan track'ler)
    // -------------------------------------------------------------------------

    /**
     * click_id YOKKEN gelen track'i kalici deferred kuyruga alir (DROP yerine —
     * iOS/RN/Flutter paritesi). Event verisi (click_id/user_id HARIC) JSON'a
     * serialize edilir; click_id gelince [flushDeferred] yeniden track eder.
     */
    private fun enqueueDeferredEvent(store: Storage, eventType: String, event: AdSparkleEvent, test: Boolean) {
        val json = JSONObject()
        json.put("event_type", eventType)
        // Q2: enqueue-ANI sandbox flag'i event'le SAKLANIR (flush'ta guncel state degil
        // BU deger kullanilir → sandbox event sandbox kalir).
        if (test) json.put("test", true)
        event.transactionId?.let { json.put("transaction_id", it) }
        event.amount?.let { json.put("amount", it) }
        event.currency?.let { json.put("currency", it) }
        event.productIds?.takeIf { it.isNotEmpty() }?.let { json.put("product_ids", JSONArray(it)) }
        event.customParams?.takeIf { it.isNotEmpty() }?.let { params ->
            val obj = JSONObject()
            for ((k, v) in params) obj.put(k, v)
            json.put("custom_params", obj)
        }
        store.enqueueDeferred(json.toString())
    }

    /**
     * click_id set edildikten SONRA cagrilir. Kuyrugu ATOMIK bosaltir (yaris/cift-
     * gonderim olmasin), sonra her deferred olayi track() ile yeniden gonderir —
     * artik click_id VAR → send path'e girer, tekrar defer edilmez (sonsuz dongu yok).
     */
    private fun flushDeferred(store: Storage) {
        val events = store.drainDeferredEvents()
        if (events.isEmpty()) return
        if (debug) Log.d(TAG, "flushing ${events.size} deferred event(s)")
        for (raw in events) {
            val parsed = try {
                JSONObject(raw)
            } catch (_: Exception) {
                continue // bozuk kayit → atla
            }
            val eventType = parsed.optString("event_type", "")
            if (eventType.isEmpty()) continue
            val productIds = parsed.optJSONArray("product_ids")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }
            val customParams = parsed.optJSONObject("custom_params")?.let { obj ->
                val m = HashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = obj.optString(k)
                }
                m
            }
            // Q2: enqueue-aninda saklanan sandbox flag'i (guncel state DEGIL) kullanilir.
            val storedTest = parsed.optBoolean("test", false)
            val event = AdSparkleEvent(
                transactionId = parsed.optString("transaction_id").takeIf { it.isNotEmpty() },
                amount = parsed.optDouble("amount", Double.NaN).takeIf { !it.isNaN() },
                currency = parsed.optString("currency").takeIf { it.isNotEmpty() },
                productIds = productIds,
                customParams = customParams,
            )
            trackInternal(eventType, event, storedTest)
        }
    }

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
        trackInternal(eventType, event, null)
    }

    // Q2 (ADIM 6a): testOverride — flush'ta deferred event'in ENQUEUE-ANI sandbox
    // flag'i gecirilir → sandbox event, hangi env'de flush olursa olsun sandbox KALIR
    // (dev/prod karismaz; app-restart + env degisimi kenar durumu). Normal track'te
    // null → guncel [isSandbox] kullanilir. Public API degismedi.
    private fun trackInternal(eventType: String, event: AdSparkleEvent, testOverride: Boolean?) {
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

        val effectiveTest = testOverride ?: isSandbox
        val clickId = store.clickId

        if (clickId.isNullOrEmpty()) {
            // ADIM 6a: click_id yok → DROP ETME, deferred kuyruga al (iOS/RN/Flutter
            // paritesi; install-oncesi track'ler kaybolmasin). Once bekleyen register-
            // click'i tekrar dene (basarida click_id gelir → setClickId → flushDeferred
            // bunlari gonderir). Kalici (SharedPreferences), uygulama kapansa bile durur.
            attemptRegisterClick(store)
            enqueueDeferredEvent(store, eventType, event, effectiveTest)
            if (debug) Log.w(TAG, "No click_id yet — deferred '$eventType' (queued)")
            return
        }

        // Anonymous fallback (parity with adsparkle.js getOrCreateAnonId): never
        // drop a conversion just because the merchant did not call setUserId —
        // mint and persist a stable anonymous id instead.
        val userId = store.userId?.takeIf { it.isNotEmpty() } ?: getOrCreateAnonId(store)

        val clickIds = store.getClickIds()
        val payload = buildPayload(eventType, clickId, clickIds, userId, event, effectiveTest)
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
        test: Boolean,
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

        // ADIM 4: sandbox → backend postback'i yalnızca şekil-doğrular, ledger'a/DB'ye
        // yazmaz (HMAC de bypass). Q2: `test` cagiran taraftan gelir (normal track'te
        // isSandbox, flush'ta event'in enqueue-anI flag'i).
        if (test) json.put("test", true)

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
