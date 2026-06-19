package co.adsparkle.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Manages the persisted click-id chain.
 *
 * Rules:
 *  - Max 50 ids in the chain.
 *  - Attribution window: 7 days (TTL per id).
 *  - Deduplication: if the id already exists it is moved to the tail (most recent).
 *  - Format validation: UUID v4 regex.
 */
internal class ClickStore(context: Context) {

    companion object {
        private const val PREFS_NAME        = "adsparkle_clicks"
        private const val KEY_CHAIN         = "click_chain"
        private const val MAX_CHAIN_SIZE    = 50
        private val TTL_MS                  = 7L * 24 * 60 * 60 * 1_000  // 7 days

        private val UUID_V4_PATTERN: Pattern = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
        )

        fun isValidClickId(id: String): Boolean = UUID_V4_PATTERN.matcher(id).matches()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Thread-safe lock for all chain mutations. */
    private val lock = Any()

    // ── Data class stored per entry ──────────────────────────────────────────

    private data class Entry(val id: String, val addedAt: Long)

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Adds a validated click id to the chain. Returns `true` if the id was accepted.
     */
    fun add(clickId: String): Boolean {
        if (!isValidClickId(clickId)) {
            AdSparkleLogger.w("trackClick: rejected invalid click_id format: $clickId")
            return false
        }
        synchronized(lock) {
            val chain = loadChain().toMutableList()
            // Deduplicate — remove existing occurrence so we move it to the end
            chain.removeAll { it.id == clickId }
            chain.add(Entry(clickId, System.currentTimeMillis()))
            // Enforce max size (drop oldest from the front)
            val trimmed = if (chain.size > MAX_CHAIN_SIZE) chain.drop(chain.size - MAX_CHAIN_SIZE) else chain
            saveChain(trimmed)
            AdSparkleLogger.d("trackClick: accepted click_id=$clickId  chain_size=${trimmed.size}")
            return true
        }
    }

    /**
     * Returns all non-expired click ids in insertion order (oldest → newest).
     */
    fun getChain(): List<String> {
        synchronized(lock) {
            return loadChain().map { it.id }
        }
    }

    /**
     * The most-recent (last added) non-expired id, or `null` if the chain is empty.
     */
    fun getMostRecent(): String? = getChain().lastOrNull()

    /**
     * Removes expired entries and persists the cleaned chain.
     */
    fun pruneExpired() {
        synchronized(lock) {
            val now   = System.currentTimeMillis()
            val chain = loadChain()
            val fresh = chain.filter { now - it.addedAt < TTL_MS }
            if (fresh.size != chain.size) {
                saveChain(fresh)
                AdSparkleLogger.d("pruneExpired: removed ${chain.size - fresh.size} expired click_id(s)")
            }
        }
    }

    // ── Persistence helpers ──────────────────────────────────────────────────

    private fun loadChain(): List<Entry> {
        val raw = prefs.getString(KEY_CHAIN, null) ?: return emptyList()
        return try {
            val now   = System.currentTimeMillis()
            val array = JSONArray(raw)
            val result = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val obj     = array.getJSONObject(i)
                val id      = obj.getString("id")
                val addedAt = obj.getLong("addedAt")
                // Discard expired inline during load
                if (now - addedAt < TTL_MS && isValidClickId(id)) {
                    result.add(Entry(id, addedAt))
                }
            }
            result
        } catch (e: Exception) {
            AdSparkleLogger.e("ClickStore: failed to parse chain, resetting", e)
            emptyList()
        }
    }

    private fun saveChain(chain: List<Entry>) {
        val array = JSONArray()
        chain.forEach { entry ->
            array.put(JSONObject().apply {
                put("id",      entry.id)
                put("addedAt", entry.addedAt)
            })
        }
        prefs.edit().putString(KEY_CHAIN, array.toString()).apply()
    }
}
