package co.adsparkle.sdk

import android.util.Log

/**
 * Internal lightweight logger. All output is suppressed unless debug mode is enabled via
 * [AdSparkle.enableDebugLogging]. No runtime overhead in production.
 */
internal object AdSparkleLogger {

    private const val TAG = "AdSparkle"

    @Volatile
    internal var debugEnabled: Boolean = false

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (debugEnabled) Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
