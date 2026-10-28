package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.Address

/** The addresses a computer on the same network can open the page at. */
internal object InterfaceAddresses {
    /** What the system says a network interface is. Tethering interfaces aren't networks of ours: unknown. */
    enum class PathKind { WIFI, WIRED, CELLULAR, VPN, OTHER }

    /** An IPv4 address of an interface that is up, with what ConnectivityManager says it is, when it knows. */
    data class Entry(val name: String, val ipv4: String, val pathKind: PathKind? = null)

    /**
     * Interfaces that never reach a computer on the local network: loopback, cellular modems, VPN and tunnels,
     * Wi-Fi Direct / Aware peer links, and the 464XLAT `v4-` stacked interface.
     */
    private val excludedPrefixes = listOf(
        "lo", "rmnet", "ccmni", "pdp", "tun", "ppp", "ipsec", "p2p", "aware", "nan", "v4-", "clat", "dummy",
        "ifb", "sit", "ip6tnl", "gre"
    )

    /** Interfaces the system brings up to share this device's connection: Wi-Fi hotspot, USB, Bluetooth. */
    private val tetheringPrefixes = listOf("ap", "swlan", "softap", "wlan", "rndis", "usb", "ncm", "bt-pan")

    /**
     * What a computer would use the address for, or null when no computer on the network can reach it.
     * Interface names aren't API (`wlan0` isn't always Wi-Fi), so the system's view wins when it has one.
     */
    fun kind(entry: Entry): Address.Kind? {
        if (entry.ipv4.startsWith("127.") || entry.ipv4.startsWith("169.254.")) return null
        if (excludedPrefixes.any { entry.name.startsWith(it) }) return null
        return when (entry.pathKind) {
            PathKind.WIFI -> Address.Kind.WIFI
            PathKind.WIRED -> Address.Kind.WIRED
            PathKind.CELLULAR, PathKind.VPN -> null
            PathKind.OTHER, null ->
                if (entry.pathKind == null && tetheringPrefixes.any { entry.name.startsWith(it) }) Address.Kind.HOTSPOT
                else Address.Kind.OTHER
        }
    }

    /** Usable addresses on [port], best first: Wi-Fi, wired, hotspot, other; then by interface name. */
    fun addresses(entries: List<Entry>, port: Int): List<Address> =
        entries.mapNotNull { entry -> kind(entry)?.let { Address(it, entry.name, entry.ipv4, port) } }
            .distinctBy { it.host }
            .sortedWith(compareBy<Address> { it.kind.ordinal }.thenBy { it.interfaceName })
}
