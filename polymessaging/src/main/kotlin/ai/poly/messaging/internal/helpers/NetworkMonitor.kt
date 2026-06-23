// Copyright PolyAI Limited

package ai.poly.messaging.internal.helpers

import ai.poly.messaging.internal.ports.NetworkStatePort
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reachability via `ConnectivityManager.registerDefaultNetworkCallback`. Drives the reconnect
 * logic: drop a dead socket fast when the OS reports offline, reset the reconnect budget when
 * it returns.
 */
internal class NetworkMonitor(context: Context) : NetworkStatePort {

    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isOnline = MutableStateFlow(currentlyOnline())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { setOnline(true) }
        override fun onLost(network: Network) { setOnline(currentlyOnline()) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            setOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
    }

    private fun setOnline(online: Boolean) {
        val was = _isOnline.value
        _isOnline.value = online
        // Start a poll on the satisfied→unsatisfied edge and stop it on the restore edge.
        if (was && !online) startPolling() else if (!was && online) stopPolling()
    }

    // ConnectivityManager can miss a restore callback on some devices, so while offline
    // re-check connectivity every 3s and flip back online if it has recovered.
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (currentlyOnline()) { _isOnline.value = true; break }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun start() {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        // Arm the poll backstop even when offline at startup: registerDefaultNetworkCallback fires
        // no callback when there's no network at boot, so arm it here.
        if (!currentlyOnline()) startPolling()
    }

    override fun stop() {
        stopPolling()
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L // poll every 3s while offline
    }
}
