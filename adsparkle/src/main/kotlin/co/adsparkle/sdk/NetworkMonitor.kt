package co.adsparkle.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Observes network connectivity changes and triggers [onNetworkAvailable] whenever
 * a usable network is gained. Uses [ConnectivityManager.NetworkCallback] (API 21+).
 *
 * The callback is invoked on an arbitrary system thread — callers must be thread-safe.
 */
internal class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AdSparkleLogger.d("NetworkMonitor: network available — triggering queue flush")
            onNetworkAvailable()
        }
    }

    fun register() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            AdSparkleLogger.d("NetworkMonitor: registered")
        } catch (e: Exception) {
            AdSparkleLogger.e("NetworkMonitor: failed to register callback", e)
        }
    }

    fun unregister() {
        try {
            cm.unregisterNetworkCallback(callback)
            AdSparkleLogger.d("NetworkMonitor: unregistered")
        } catch (e: Exception) {
            // Already unregistered or never registered — safe to ignore
        }
    }

    /** Returns `true` if the device currently has an internet-capable network. */
    fun isConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps    = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }
}
