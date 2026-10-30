package com.tortugapower.audiobookplayer.logic

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * What the engine hears of the device's network, for whoever runs it (SyncRuntime on the phone, the watch's sync
 * service): the app's default network (SyncNetworkCallback) and, on Android 12+, the physical one (PhysicalNetworkCallback).
 * Behind an always-on VPN the default network is the VPN, which stays up while the connection under it drops and comes
 * back: only the physical network tells that the connection came back. Android 9-11 have no public way to follow that
 * one, so behind a VPN their backoffs end on their own, or when the app is opened.
 */
class SyncNetworkWatch(engine: TaskConcurrencyManager) {
    private val default = SyncNetworkCallback(engine)
    private val physical = PhysicalNetworkCallback(engine)
    private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()

    /** Whether any callback was registered: a failure (too many callbacks) is logged, never thrown */
    fun register(connectivity: ConnectivityManager): Boolean {
        val active = connectivity.activeNetwork
        default.onRegistering(active)
        register(default) { connectivity.registerDefaultNetworkCallback(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            physical.onRegistering(physicalNetworksUp(connectivity))
            // Any network with internet that isn't a VPN: the best of them is the one a VPN runs over
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            register(physical) { connectivity.registerBestMatchingNetworkCallback(request, it, Handler(Looper.getMainLooper())) }
        }
        return registered.isNotEmpty()
    }

    fun unregister(connectivity: ConnectivityManager) {
        registered.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        registered.clear()
    }

    // The validated networks with internet that aren't a VPN. Behind a VPN the active network is the VPN, and a callback
    // only reports what comes after its registration: this list is what names the ones up before it
    @Suppress("DEPRECATION")
    private fun physicalNetworksUp(connectivity: ConnectivityManager): Set<Network> =
        connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }.toSet()

    private fun register(callback: ConnectivityManager.NetworkCallback, registration: (ConnectivityManager.NetworkCallback) -> Unit) {
        runCatching { registration(callback) }
            .onSuccess { registered += callback }
            .onFailure { Log.w("SyncNetworkWatch", "Network callback not registered", it) }
    }
}

/**
 * The engine's view of the device's network. Any network becoming the default rescans the lanes: the task list
 * doesn't re-emit for a network change, and uploads held on cellular may go on Wi-Fi. A new default network, once
 * validated, also cuts backoffs short (NetworkChangeDetector), since a task that failed on the old one may go
 * through on it.
 */
class SyncNetworkCallback(private val engine: TaskConcurrencyManager) : ConnectivityManager.NetworkCallback() {
    private val changes = NetworkChangeDetector<Network>()
    // The default network's metered state: only a metered → unmetered flip can release held uploads
    private var lastNotMetered: Boolean? = null

    /** Right before registering: the network up now, whose first callback is no change */
    fun onRegistering(activeNetwork: Network?) = changes.onRegistering(activeNetwork)

    override fun onAvailable(network: Network) {
        changes.onAvailable(network)
        engine.requestWorkerScan()
    }

    override fun onLost(network: Network) = changes.onLost(network)

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (changes.onCapabilitiesChanged(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) {
            engine.retryWaitingNow()
        }
        // Capability callbacks fire constantly (bandwidth estimates, validation), and each scan reads the queue
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val becameUnmetered = notMetered && lastNotMetered != true
        lastNotMetered = notMetered
        if (becameUnmetered) engine.requestWorkerScan()
    }
}

/**
 * The physical network the device uses (Android 12+, the best network with internet that isn't a VPN): a new one,
 * once validated, cuts backoffs short (NetworkChangeDetector), under a VPN too. Without a VPN it's also the default
 * network, which SyncNetworkCallback follows: both see the change, and the second finds nothing left to retry. Rescans
 * and the metered state stay with SyncNetworkCallback: a VPN takes its metered state from the network under it.
 */
class PhysicalNetworkCallback(private val engine: TaskConcurrencyManager) : ConnectivityManager.NetworkCallback() {
    private val changes = NetworkChangeDetector<Network>()

    /**
     * Right before registering: the physical networks up now (validated). The first callback reports one of them, no
     * change; a network that isn't among them came up since
     */
    fun onRegistering(upNow: Set<Network>) = changes.onRegisteringAmong(upNow)

    override fun onAvailable(network: Network) = changes.onAvailable(network)

    override fun onLost(network: Network) = changes.onLost(network)

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (changes.onCapabilitiesChanged(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) {
            engine.retryWaitingNow()
        }
    }
}
