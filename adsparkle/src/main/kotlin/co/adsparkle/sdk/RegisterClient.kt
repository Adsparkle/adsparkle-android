package co.adsparkle.sdk

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * ADIM 5: register-click istemcisi (app-tetikli DETERMINISTIC click).
 *
 * App Links ile app YUKLU acildiginda sistem dogrudan app'i acar → sunucuya
 * ugranmaz, ClickEvent olusmaz. [AdSparkle.handleDeepLink] bu istemciyle click'i
 * APP olusturur: `unique_key`'den backend ClickEvent uretir ve `click_id` doner.
 *
 * - DETERMINISTIC: `device_id` = SDK'nin KALICI UUID'si (E5 dedup anahtari;
 *   iki kullanici ayni WiFi + ayni UA olsa bile ayni ClickEvent'e catlanmaz),
 *   `platform` = "android". Cihaz fingerprint'i GONDERILMEZ → backend
 *   hasJsFingerprint false → ClickEvent /match adayi olmaz.
 * - Basari → `click_id`. 4xx (yanlis company_key vb.) / 5xx / ag hatasi → `null`
 *   (cagiran sessizce gecer, E3; throw ETMEZ).
 *
 * Bloklayan I/O yapar; cagiran off-main-thread (executor) uzerinde calistirmali
 * (parity: [PostbackClient]).
 */
internal object RegisterClient {

    /**
     * POST {baseUrl}/api/tracking/register-click.
     *
     * @return backend'in urettigi `click_id`, veya basarisizlikta `null`.
     */
    fun resolve(
        baseUrl: String,
        companyKey: String,
        uniqueKey: String,
        deviceId: String,
        queryParams: Map<String, String>,
        referrer: String?,
        test: Boolean,
        debug: Boolean,
    ): String? {
        return try {
            val body = JSONObject()
            body.put("unique_key", uniqueKey)
            body.put("company_key", companyKey)
            body.put("device_id", deviceId)
            body.put("platform", "android")
            if (queryParams.isNotEmpty()) {
                val qp = JSONObject()
                for ((k, v) in queryParams) qp.put(k, v)
                body.put("query_params", qp)
            }
            if (!referrer.isNullOrEmpty()) {
                body.put("referrer", referrer)
            }
            // ADIM 4: sandbox → backend ClickEvent YAZMAZ, sentetik click_id döner.
            if (test) body.put("test", true)
            post(baseUrl, companyKey, body.toString(), debug)
        } catch (e: Exception) {
            // Ag hatasi / beklenmeyen istisna → sessizce null (E3). Asla throw etme.
            if (debug) Log.w(TAG, "register-click error: ${e.message}")
            null
        }
    }

    private fun post(
        baseUrl: String,
        companyKey: String,
        payloadJson: String,
        debug: Boolean,
    ): String? {
        val endpoint = URL("${baseUrl.trimEnd('/')}$PATH")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            // Publishable "co_" key — not a secret. HMAC secrets are never used client-side.
            connection.setRequestProperty("X-Company-Key", companyKey)

            val bytes = payloadJson.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            if (code !in 200..299) {
                // 4xx/5xx: register-click TEK sefer denenir; retry pendingRegisterClick
                // ile ust katmanda yapilir (E3). Burada null don.
                if (debug) Log.w(TAG, "register-click non-2xx (HTTP $code)")
                readBody(connection, code)
                return null
            }
            val bodyStr = readBody(connection, code) ?: return null
            val json = JSONObject(bodyStr)
            if (json.optBoolean("success", false)) {
                val clickId = json.optString("click_id", "")
                if (clickId.isNotEmpty()) {
                    if (debug) Log.d(TAG, "register-click resolved a click_id")
                    return clickId
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String? {
        val stream = try {
            if (code in 200..399) connection.inputStream else connection.errorStream
        } catch (_: IOException) {
            null
        } ?: return null
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                sb.toString()
            }
        } catch (_: IOException) {
            null
        }
    }

    private const val TAG = "AdSparkle"
    private const val PATH = "/api/tracking/register-click"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
}
