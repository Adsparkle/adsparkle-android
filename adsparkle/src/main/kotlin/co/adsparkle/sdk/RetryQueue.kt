package co.adsparkle.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent FIFO queue for failed postback payloads.
 *
 * Rules:
 *  - Maximum 100 pending items. Oldest items are dropped when the cap is exceeded.
 *  - Each item is a raw JSON string (the full request body already serialised).
 *  - The queue is stored in SharedPreferences so it survives process restarts.
 */
internal class RetryQueue(context: Context) {

    companion object {
        private const val PREFS_NAME  = "adsparkle_retry_queue"
        private const val KEY_QUEUE   = "queue"
        private const val MAX_SIZE    = 100
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Adds [payload] to the tail of the queue.
     * If the queue is full the oldest item (front) is discarded.
     */
    fun enqueue(payload: String) {
        synchronized(lock) {
            val queue = loadQueue().toMutableList()
            if (queue.size >= MAX_SIZE) {
                queue.removeAt(0)
                AdSparkleLogger.w("RetryQueue: max size reached, oldest item dropped")
            }
            queue.add(payload)
            saveQueue(queue)
            AdSparkleLogger.d("RetryQueue: enqueued item  pending=${queue.size}")
        }
    }

    /**
     * Returns all pending payloads and clears the queue atomically.
     * Callers are responsible for re-enqueueing items that still fail.
     */
    fun drainAll(): List<String> {
        synchronized(lock) {
            val queue = loadQueue()
            if (queue.isNotEmpty()) {
                saveQueue(emptyList())
                AdSparkleLogger.d("RetryQueue: drained ${queue.size} item(s)")
            }
            return queue
        }
    }

    fun size(): Int {
        synchronized(lock) { return loadQueue().size }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun loadQueue(): List<String> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            AdSparkleLogger.e("RetryQueue: parse error, resetting queue", e)
            emptyList()
        }
    }

    private fun saveQueue(items: List<String>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }
}
