// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks *device* connectivity via
 * [ConnectivityManager], independently of the SDK's socket state — [isOnline] flips false when the OS
 * reports no usable default network. Call [start]/[stop] to register/unregister the callback (e.g.
 * from a Compose `DisposableEffect`).
 */
class NetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _isOnline.value = true }
        override fun onLost(network: Network) { _isOnline.value = false }
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        _isOnline.value = currentlyOnline()
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean {
        val cm = cm ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
