package co.adsparkle.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Thin, thread-safe wrapper over [SharedPreferences] that persists SDK state.
 *
 * All persisted values survive process death so that attribution data and the
 * offline retry queue are not lost between launches.
 */
internal class Storage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Synchronizes compound read-modify-write sequences (e.g. queue/chain mutation).
    private val lock = Any()

    var companyKey: String?
        get() = prefs.getString(KEY_COMPANY_KEY, null)
        set(value) = prefs.edit().putString(KEY_COMPANY_KEY, value).apply()

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var clickId: String?
        get() = prefs.getString(KEY_CLICK_ID, null)
        set(value) = prefs.edit().putString(KEY_CLICK_ID, value).apply()

    /**
     * Ordered, de-duplicated chain of recent click ids (most recent last).
     *
     * Implements a sliding 7-day TTL ([CHAIN_TTL_MS]): if the chain has not been
     * updated within the attribution window it is considered expired, cleared,
     * and an empty list is returned.
     */
    fun getClickIds(): List<String> = synchronized(lock) {
        readChainWithTtl()
    }

    /**
     * Appends [clickId] to the chain, removing any earlier duplicate and
     * trimming the chain to [MAX_CLICK_IDS] (oldest dropped first).
     *
     * Ignores ids that are not valid UUIDs. Resets the sliding TTL timestamp on
     * every successful append.
     */
    fun addClickId(clickId: String) = synchronized(lock) {
        if (!UUID_RE.matches(clickId)) return@synchronized
        // Read with TTL so an expired chain starts fresh from this id.
        val current = readChainWithTtl().toMutableList()
        current.remove(clickId)
        current.add(clickId)
        while (current.size > MAX_CLICK_IDS) {
            current.removeAt(0)
        }
        writeStringList(KEY_CLICK_IDS, current)
        prefs.edit().putLong(KEY_CLICK_IDS_TS, System.currentTimeMillis()).apply()
    }

    /**
     * Reads the chain enforcing the sliding 7-day window. Must be called while
     * holding [lock]. Clears persisted state when expired.
     */
    private fun readChainWithTtl(): List<String> {
        val list = readStringList(KEY_CLICK_IDS)
        if (list.isEmpty()) return emptyList()
        val ts = prefs.getLong(KEY_CLICK_IDS_TS, 0L)
        val now = System.currentTimeMillis()
        if (ts <= 0L || now - ts > CHAIN_TTL_MS) {
            prefs.edit()
                .remove(KEY_CLICK_IDS)
                .remove(KEY_CLICK_IDS_TS)
                .apply()
            return emptyList()
        }
        return list
    }

    // ---- Offline pending queue (list of serialized event JSON strings) ----

    fun getPendingQueue(): List<String> = synchronized(lock) {
        readStringList(KEY_PENDING_QUEUE)
    }

    fun enqueuePending(payloadJson: String) = synchronized(lock) {
        val current = readStringList(KEY_PENDING_QUEUE).toMutableList()
        current.add(payloadJson)
        while (current.size > MAX_PENDING) {
            current.removeAt(0)
        }
        writeStringList(KEY_PENDING_QUEUE, current)
    }

    /** Atomically returns and clears the entire pending queue. */
    fun drainPendingQueue(): List<String> = synchronized(lock) {
        val current = readStringList(KEY_PENDING_QUEUE)
        if (current.isNotEmpty()) {
            prefs.edit().remove(KEY_PENDING_QUEUE).apply()
        }
        current
    }

    // ---- JSON-array <-> List<String> helpers ----

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, null) ?: continue
                out.add(item)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeStringList(key: String, values: List<String>) {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "adsparkle_prefs"

        const val MAX_CLICK_IDS = 50
        const val MAX_PENDING = 100

        /** Sliding attribution window: 7 days in milliseconds. */
        const val CHAIN_TTL_MS = 604800000L

        /** UUID v-agnostic, case-insensitive (parity with adsparkle.js UUID_RE). */
        val UUID_RE = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )

        private const val KEY_COMPANY_KEY = "company_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_CLICK_ID = "click_id"
        private const val KEY_CLICK_IDS = "click_ids"
        private const val KEY_CLICK_IDS_TS = "click_ids_ts"
        private const val KEY_PENDING_QUEUE = "pending_queue"
    }
}
