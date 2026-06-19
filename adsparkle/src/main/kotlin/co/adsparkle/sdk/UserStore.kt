package co.adsparkle.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the caller-supplied or auto-generated anonymous user id.
 *
 * Anonymous id format: `anon_<base36-epoch-millis><base36-random-suffix>`
 * e.g. `anon_lhk92ax3m5p7`
 */
internal class UserStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "adsparkle_user"
        private const val KEY_USER_ID = "user_id"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the stored user id, generating and persisting an anonymous one if none exists yet.
     */
    fun getUserId(): String {
        return prefs.getString(KEY_USER_ID, null) ?: generateAnonymousId().also { setUserId(it) }
    }

    /**
     * Stores a caller-supplied user id, replacing any previously stored value.
     */
    fun setUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId.trim()).apply()
        AdSparkleLogger.d("UserStore: user_id set to $userId")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun generateAnonymousId(): String {
        val timePart   = System.currentTimeMillis().toString(36)
        val randomPart = (Math.random() * Long.MAX_VALUE).toLong().toString(36)
        return "anon_$timePart$randomPart"
    }
}
