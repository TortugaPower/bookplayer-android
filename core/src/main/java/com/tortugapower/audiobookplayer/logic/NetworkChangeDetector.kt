package com.tortugapower.audiobookplayer.logic

/**
 * Tells when the device's network changed in a way worth retrying on: a new network that has internet access, as a
 * listener follows it (the app's default network, or the physical one under a VPN). A network coming back after a loss, or a switch between Wi-Fi and cellular, is a change;
 * the callback Android sends as soon as a default-network listener is registered, for the network that was
 * already up, isn't. Every job but a held book upload (UploadDataPolicy) can use whichever network is up, so
 * a task that failed on the old one may go through on the new one.
 *
 * A new default network can come up before it's validated (right after a full loss, behind a captive
 * portal): retried then, the tasks would just fail again and wait longer. So the change counts once Android
 * reports the network validated, which may come with its first capabilities or later.
 *
 * Generic over the network handle so the rules are testable without Android's `Network`.
 */
class NetworkChangeDetector<N : Any> {
    private var current: N? = null
    // The new default network, until it's validated and its change has been reported
    private var changedTo: N? = null
    // One was up at registration, not known by name: the first network reported is that one
    private var firstIsCurrent = false

    /** Right before registering the listener: the network up now, or null when there's none */
    @Synchronized
    fun onRegistering(activeNetwork: N?) {
        current = activeNetwork
        changedTo = null
        firstIsCurrent = false
    }

    /**
     * Right before registering the listener, when a network is up that can't be named (the physical one under a VPN):
     * the first network the listener reports is that one, no change
     */
    @Synchronized
    fun onRegisteringOneUp() {
        current = null
        changedTo = null
        firstIsCurrent = true
    }

    /** [network] became the default */
    @Synchronized
    fun onAvailable(network: N) {
        if (firstIsCurrent) firstIsCurrent = false else if (network != current) changedTo = network
        current = network
    }

    /** True once, when the network the default changed to is validated: time to retry */
    @Synchronized
    fun onCapabilitiesChanged(network: N, validated: Boolean): Boolean {
        if (!validated || network != changedTo) return false
        changedTo = null
        return true
    }

    @Synchronized
    fun onLost(network: N) {
        if (network == current) current = null
        if (network == changedTo) changedTo = null
    }
}
