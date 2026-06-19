package co.adsparkle.sdk

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Sends postback events to the tracking platform using [HttpURLConnection],
 * with no third-party networking dependencies.
 *
 * Networking is expected to be invoked off the main thread by the caller; this
 * class performs synchronous blocking I/O with a bounded retry/backoff loop.
 */
internal class PostbackClient(
    private val debug: Boolean,
) {

    /**
     * Posts a pre-serialized JSON [payloadJson] to `{baseUrl}/api/tracking/postback`.
     *
     * Retries up to [MAX_ATTEMPTS] times with exponential backoff on transient
     * failures (HTTP 5xx or [IOException]). 4xx responses are treated as
     * permanent (no retry) — they will not succeed by retrying.
     *
     * @return `true` if the event was accepted (HTTP 2xx) or permanently
     *         rejected by the server (4xx, not worth re-queuing); `false` only
     *         when all transient retries were exhausted and the caller should
     *         persist the payload for a later flush.
     */
    fun send(baseUrl: String, companyKey: String, payloadJson: String): Boolean {
        val endpoint = buildEndpoint(baseUrl)
        var attempt = 0

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val code = post(endpoint, companyKey, payloadJson)
                when {
                    code in 200..299 -> {
                        if (debug) Log.d(TAG, "Postback accepted (HTTP $code) on attempt $attempt")
                        return true
                    }
                    code in 500..599 -> {
                        if (debug) Log.w(TAG, "Server error HTTP $code on attempt $attempt; will retry")
                        // fall through to backoff
                    }
                    else -> {
                        // 3xx/4xx — not retryable. Drop rather than loop forever.
                        if (debug) Log.w(TAG, "Non-retryable response HTTP $code; dropping event")
                        return true
                    }
                }
            } catch (e: IOException) {
                if (debug) Log.w(TAG, "Network error on attempt $attempt: ${e.message}")
                // fall through to backoff
            }

            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt)
            }
        }

        if (debug) Log.w(TAG, "Postback failed after $MAX_ATTEMPTS attempts; queuing for later")
        return false
    }

    private fun post(endpoint: URL, companyKey: String, payloadJson: String): Int {
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) {
                // default SSLSocketFactory is fine; left explicit for clarity
            }
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            // Publishable "co_" key — not a secret. HMAC secrets are never used client-side.
            connection.setRequestProperty("X-Company-Key", companyKey)

            val body = payloadJson.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            // Drain the response stream so the connection can be reused/released.
            drain(connection, code)
            return code
        } finally {
            connection.disconnect()
        }
    }

    private fun drain(connection: HttpURLConnection, code: Int) {
        val stream = try {
            if (code in 200..399) connection.inputStream else connection.errorStream
        } catch (_: IOException) {
            null
        } ?: return
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                if (debug && sb.isNotEmpty()) {
                    Log.d(TAG, "Response body: $sb")
                }
            }
        } catch (_: IOException) {
            // Ignore body read errors; we already have the status code.
        }
    }

    private fun buildEndpoint(baseUrl: String): URL {
        val trimmed = baseUrl.trimEnd('/')
        return URL("$trimmed$PATH")
    }

    private fun sleepBackoff(attempt: Int) {
        // Exponential backoff: ~0.5s, 1s, 2s ...
        val delayMs = BASE_BACKOFF_MS * (1L shl (attempt - 1))
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "AdSparkle"
        private const val PATH = "/api/tracking/postback"

        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 500L

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
