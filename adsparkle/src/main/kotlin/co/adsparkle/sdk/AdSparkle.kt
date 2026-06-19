package co.adsparkle.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AdSparkle Android SDK — public singleton.
 *
 * Usage:
 * ```kotlin
 * // Application.onCreate or Activity.onCreate
 * AdSparkle.initialize(context, "YOUR_COMPANY_KEY")
 *
 * // Capture a click from a deep link / universal link
 * AdSparkle.handleDeepLink(intent)
 *
 * // Record a conversion
 * AdSparkle.trackConversion("purchase",
 *     transactionId = "txn_abc123",
 *     amount        = 49.99,
 *     currency      = "USD"
 * )
 * ```
 *
 * All I/O operations are dispatched to [Dispatchers.IO] — the public API is safe to call
 * from the main thread.
 */
object AdSparkle {

    // ── Internal state (lateinit, guarded by [checkInitialized]) ─────────────

    @Volatile private var companyKey:    String         = ""
    @Volatile private var endpointBase:  String         = DEFAULT_ENDPOINT_BASE
    @Volatile private var initialized:   Boolean        = false

    private lateinit var clickStore:     ClickStore
    private lateinit var userStore:      UserStore
    private lateinit var retryQueue:     RetryQueue
    private lateinit var networkMonitor: NetworkMonitor

    /** Coroutine scope backed by a SupervisorJob so one failure doesn't cancel siblings. */
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val DEFAULT_ENDPOINT_BASE = "https://api.adsparkle.co"
    private const val POSTBACK_PATH          = "/api/tracking/postback"

    // ── Initialisation ───────────────────────────────────────────────────────

    /**
     * Initialises the SDK. Must be called before any other method, typically in
     * [android.app.Application.onCreate].
     *
     * @param context     Application or Activity context (stored as applicationContext).
     * @param companyKey  Your AdSparkle company API key (sent as `X-Company-Key`).
     * @param endpointBase Override the default API base URL (no trailing slash).
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context:      Context,
        companyKey:   String,
        endpointBase: String = DEFAULT_ENDPOINT_BASE
    ) {
        require(companyKey.isNotBlank()) { "AdSparkle: companyKey must not be blank" }

        val appContext   = context.applicationContext
        this.companyKey  = companyKey
        this.endpointBase = endpointBase.trimEnd('/')

        clickStore     = ClickStore(appContext)
        userStore      = UserStore(appContext)
        retryQueue     = RetryQueue(appContext)
        networkMonitor = NetworkMonitor(appContext) {
            // Auto-flush when network comes back
            flushQueue()
        }
        networkMonitor.register()

        // Prune stale click ids on every cold start
        sdkScope.launch { clickStore.pruneExpired() }

        initialized = true
        AdSparkleLogger.d("AdSparkle SDK v${BuildConfig.SDK_VERSION} initialized  endpoint=$endpointBase")
    }

    // ── Debug toggle ─────────────────────────────────────────────────────────

    /**
     * Enables verbose Logcat output tagged `AdSparkle`. Disable in production.
     */
    @JvmStatic
    fun enableDebugLogging(enabled: Boolean) {
        AdSparkleLogger.debugEnabled = enabled
    }

    // ── Click capture ────────────────────────────────────────────────────────

    /**
     * Convenience wrapper — extracts the [Uri] from [intent] and delegates to [trackClick].
     * Safe to call even if the intent has no data.
     */
    @JvmStatic
    fun handleDeepLink(intent: Intent) {
        intent.data?.let { trackClick(it) }
    }

    /**
     * Parses [uri] for a `click_id` query parameter, validates it, and adds it to the
     * persisted click chain.
     *
     * @param uri The incoming deep-link / universal-link URI.
     */
    @JvmStatic
    fun trackClick(uri: Uri) {
        checkInitialized()
        val raw = uri.getQueryParameter("click_id")
        if (raw.isNullOrBlank()) {
            AdSparkleLogger.d("trackClick: no click_id in URI $uri — skipping")
            return
        }
        // IO-bound persistence, but ClickStore.add is fast — launch off-main anyway
        sdkScope.launch { clickStore.add(raw) }
    }

    // ── User identity ─────────────────────────────────────────────────────────

    /**
     * Overrides the user id used in all subsequent postback calls.
     * If never called, an anonymous id (`anon_…`) is generated and persisted automatically.
     */
    @JvmStatic
    fun setUserId(userId: String) {
        checkInitialized()
        require(userId.isNotBlank()) { "AdSparkle: userId must not be blank" }
        sdkScope.launch { userStore.setUserId(userId) }
    }

    // ── Conversion tracking ──────────────────────────────────────────────────

    /**
     * Records a conversion event by POSTing to the AdSparkle postback endpoint.
     *
     * If the click-id chain is empty (organic traffic) the call is silently ignored and
     * [callback] receives `false` — this is expected and not an error.
     *
     * Failed requests are persisted to the offline retry queue and re-sent automatically
     * when network connectivity is restored, or on the next [flushQueue] call.
     *
     * @param type          Event type string (see [EventType] for accepted values and aliases).
     * @param transactionId Optional external transaction / order id.
     * @param amount        Optional monetary amount (e.g. purchase value).
     * @param currency      Optional ISO 4217 currency code (e.g. "USD").
     * @param productIds    Optional list of product / SKU identifiers.
     * @param customParams  Optional arbitrary key-value pairs forwarded as-is.
     * @param callback      Invoked on the calling thread with `true` on success, `false` otherwise.
     */
    @JvmStatic
    @JvmOverloads
    fun trackConversion(
        type:          String,
        transactionId: String?               = null,
        amount:        Double?               = null,
        currency:      String?               = null,
        productIds:    List<String>?         = null,
        customParams:  Map<String, String>?  = null,
        callback:      ((Boolean) -> Unit)?  = null
    ) {
        checkInitialized()

        sdkScope.launch {
            val result = trackConversionInternal(
                type, transactionId, amount, currency, productIds, customParams
            )
            val success = result is ConversionResult.Success
            callback?.invoke(success)
        }
    }

    /**
     * Suspending variant for callers using coroutines directly.
     * Returns a typed [ConversionResult].
     */
    suspend fun trackConversionAsync(
        type:          String,
        transactionId: String?               = null,
        amount:        Double?               = null,
        currency:      String?               = null,
        productIds:    List<String>?         = null,
        customParams:  Map<String, String>?  = null
    ): ConversionResult {
        checkInitialized()
        return trackConversionInternal(type, transactionId, amount, currency, productIds, customParams)
    }

    // ── Queue management ─────────────────────────────────────────────────────

    /**
     * Retries all pending items in the offline queue.
     * Automatically called whenever network becomes available.
     * Safe to call manually (e.g. on app foreground).
     */
    @JvmStatic
    fun flushQueue() {
        checkInitialized()
        sdkScope.launch { flushQueueInternal() }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private suspend fun trackConversionInternal(
        type:          String,
        transactionId: String?,
        amount:        Double?,
        currency:      String?,
        productIds:    List<String>?,
        customParams:  Map<String, String>?
    ): ConversionResult {

        // 1. Resolve event type
        val eventType = EventType.resolve(type)
        if (eventType == null) {
            AdSparkleLogger.w("trackConversion: unknown event type '$type' — rejected")
            return ConversionResult.UnknownEventType(type)
        }

        // 2. Prune and read click chain
        clickStore.pruneExpired()
        val chain    = clickStore.getChain()
        val clickId  = chain.lastOrNull()

        if (clickId == null) {
            AdSparkleLogger.d("trackConversion: click chain is empty — organic traffic, skipping postback")
            return ConversionResult.NoClickId
        }

        // 3. Build payload
        val payload = PostbackPayload(
            clickId       = clickId,
            clickIds      = chain,
            eventType     = eventType.canonical,
            userId        = userStore.getUserId(),
            transactionId = transactionId,
            amount        = amount,
            currency      = currency,
            productIds    = productIds,
            customParams  = customParams
        )
        val json      = payload.toJson()
        val endpoint  = "$endpointBase$POSTBACK_PATH"

        // 4. Send
        return sendPayload(endpoint, json)
    }

    private fun sendPayload(endpoint: String, json: String): ConversionResult {
        return try {
            val ok = HttpClient.post(endpoint, companyKey, json)
            if (ok) {
                ConversionResult.Success
            } else {
                AdSparkleLogger.w("sendPayload: server rejected postback — queuing for retry")
                retryQueue.enqueue(json)
                ConversionResult.Queued()
            }
        } catch (e: Exception) {
            AdSparkleLogger.e("sendPayload: exception — queuing for retry", e)
            retryQueue.enqueue(json)
            ConversionResult.Queued(e)
        }
    }

    private fun flushQueueInternal() {
        if (!networkMonitor.isConnected()) {
            AdSparkleLogger.d("flushQueue: no network — skipping flush")
            return
        }
        val pending = retryQueue.drainAll()
        if (pending.isEmpty()) {
            AdSparkleLogger.d("flushQueue: queue is empty")
            return
        }
        AdSparkleLogger.i("flushQueue: retrying ${pending.size} item(s)")
        val endpoint  = "$endpointBase$POSTBACK_PATH"
        val requeued  = mutableListOf<String>()

        pending.forEach { json ->
            try {
                val ok = HttpClient.post(endpoint, companyKey, json)
                if (!ok) {
                    AdSparkleLogger.w("flushQueue: retry failed, re-queuing item")
                    requeued.add(json)
                } else {
                    AdSparkleLogger.d("flushQueue: retry succeeded")
                }
            } catch (e: Exception) {
                AdSparkleLogger.e("flushQueue: exception during retry", e)
                requeued.add(json)
            }
        }
        // Re-enqueue anything that still failed
        requeued.forEach { retryQueue.enqueue(it) }
        AdSparkleLogger.i("flushQueue: complete  succeeded=${pending.size - requeued.size}  requeued=${requeued.size}")
    }

    private fun checkInitialized() {
        check(initialized) {
            "AdSparkle SDK is not initialized. Call AdSparkle.initialize(context, companyKey) first."
        }
    }
}
