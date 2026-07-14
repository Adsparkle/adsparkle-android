package co.adsparkle.sdk

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails

/**
 * Android deferred (install) attribution — Play Install Referrer okuyucusu.
 *
 * Akis: kullanici affiliate linkine tiklar → backend `recordClick` Play Store
 * redirect URL'ine `referrer=click_id%3D<uuid>` ekler → Play Store bu referrer'i
 * kurulumda saklar → app ILK acildiginda buradan okuyup click_id'yi kurtaririz.
 * Boylece iOS'un aksine (App Store referrer gecirmez) Android'de install
 * DETERMINISTIK olarak dogru influencer/kampanyaya baglanir.
 *
 * `com.android.installreferrer:installreferrer` baglantisi ASYNC oldugu icin
 * sonuc bir callback ile doner; cagiran (AdSparkle) bunu setClickId'e verir.
 */
internal object InstallReferrerReader {

    private const val TAG = "AdSparkle"

    /**
     * Install Referrer'i bir kez okur; `referrer` icindeki `click_id=<uuid>`
     * degerini cikarip [onClickId]'e verir. Hata/boş durumda sessizce gecer
     * (debug'da loglar). Baglanti her durumda kapatilir.
     */
    fun fetch(context: Context, debug: Boolean, onClickId: (String) -> Unit) {
        val client = try {
            InstallReferrerClient.newBuilder(context.applicationContext).build()
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "InstallReferrer client build failed: ${e.message}")
            return
        }

        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    try {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            val details: ReferrerDetails = client.installReferrer
                            val clickId = parseClickId(details.installReferrer)
                            if (clickId != null) {
                                onClickId(clickId)
                                if (debug) Log.d(TAG, "InstallReferrer: click_id captured")
                            } else if (debug) {
                                Log.d(TAG, "InstallReferrer: no click_id in referrer")
                            }
                        } else if (debug) {
                            Log.d(TAG, "InstallReferrer setup responseCode=$responseCode")
                        }
                    } catch (e: Throwable) {
                        if (debug) Log.w(TAG, "InstallReferrer read failed: ${e.message}")
                    } finally {
                        try { client.endConnection() } catch (_: Throwable) { /* no-op */ }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Play Store servisi koparsa yeniden denemeye calismayiz —
                    // referrer sabittir; bir sonraki configure() zaten flag'i
                    // set ettigi icin tekrar sorgulanmaz.
                }
            })
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "InstallReferrer startConnection failed: ${e.message}")
            try { client.endConnection() } catch (_: Throwable) { /* no-op */ }
        }
    }

    /**
     * `referrer` string'inden ("click_id=<uuid>&utm_source=..." gibi) `click_id`
     * degerini cikarir. Bulunamazsa null.
     */
    private fun parseClickId(rawReferrer: String?): String? {
        if (rawReferrer.isNullOrEmpty()) return null
        // Play Store referrer'i genelde bir kez URL-decode edip verir ("click_id=<uuid>")
        // ama etmese bile ("click_id%3D<uuid>") calissin: once decode et (uuid'de
        // %/+ olmadigi icin zaten decode edilmisse deger degismez).
        val referrer = try {
            java.net.URLDecoder.decode(rawReferrer, "UTF-8")
        } catch (_: Throwable) {
            rawReferrer
        }
        for (pair in referrer.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == "click_id") {
                val v = pair.substring(idx + 1)
                return v.ifEmpty { null }
            }
        }
        return null
    }
}
