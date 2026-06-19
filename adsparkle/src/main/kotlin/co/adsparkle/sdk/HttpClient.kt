package co.adsparkle.sdk

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charsets

/**
 * Minimal HTTP client backed by [HttpURLConnection] — zero extra dependencies.
 *
 * Performs a single POST with a JSON body and the `X-Company-Key` header.
 * Returns `true` on HTTP 2xx, `false` on any network or server error.
 *
 * Timeouts:
 *  - Connect: 10 s
 *  - Read:    15 s
 */
internal object HttpClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 15_000

    /**
     * Executes a POST to [endpointUrl] with [body] and the given [companyKey].
     *
     * @return `true` if the server returned 2xx.
     */
    fun post(endpointUrl: String, companyKey: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod  = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                doOutput       = true
                setRequestProperty("Content-Type",   "application/json; charset=utf-8")
                setRequestProperty("Accept",         "application/json")
                setRequestProperty("X-Company-Key",  companyKey)
                setRequestProperty("X-SDK-Platform", "android")
                setRequestProperty("X-SDK-Version",  BuildConfig.SDK_VERSION)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val code = conn.responseCode
            val ok   = code in 200..299
            AdSparkleLogger.d("HttpClient: POST $endpointUrl  status=$code  ok=$ok")
            if (!ok) {
                // Consume error stream so the connection can be reused
                runCatching { conn.errorStream?.bufferedReader()?.readText() }
            }
            ok
        } catch (e: Exception) {
            AdSparkleLogger.e("HttpClient: network error posting to $endpointUrl", e)
            false
        } finally {
            conn?.disconnect()
        }
    }
}
