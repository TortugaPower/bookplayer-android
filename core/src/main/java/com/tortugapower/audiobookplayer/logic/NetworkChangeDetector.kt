package com.tortugapower.audiobookplayer.logic

/**
 * Tells a real change of the device's network from the callback Android sends as soon as a default-network
 * listener is registered, which reports the network that was already up. Any other network becoming the
 * default is a change: one coming back after a loss, or a switch between Wi-Fi and cellular. Every job but a
 * held book upload (UploadDataPolicy) can use whichever network is up, so a task that failed on the old one
 * may go through on the new one.
 *
 * Generic over the network handle so the rules are testable without Android's `Network`.
 */
class NetworkChangeDetector<N : Any> {
    private var current: N? = null

    /** Right before registering the listener: the network up now, or null when there's none */
    @Synchronized
    fun onRegistering(activeNetwork: N?) {
        current = activeNetwork
    }

    /** True when [network] becoming the default is a change from the one before */
    @Synchronized
    fun onAvailable(network: N): Boolean {
        val changed = network != current
        current = network
        return changed
    }

    @Synchronized
    fun onLost(network: N) {
        if (network == current) current = null
    }
}
