package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.Address
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

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

    /**
     * Whether a connection that arrived on [local] came in on one of the [shown] addresses, or on loopback. The
     * socket listens on every interface, cellular included, where a carrier that lets incoming IPv6 through
     * would let anyone upload; a computer on the network always connects to an address the app shows.
     */
    fun isShown(local: InetAddress, shown: List<Address>): Boolean {
        val address = unmapped(local)
        if (address.isLoopbackAddress) return true
        val host = (address as? Inet4Address)?.hostAddress ?: return false
        return shown.any { it.host == host }
    }

    /** `::ffff:192.168.1.20`, as a dual-stack socket can report an IPv4 connection, as `192.168.1.20`. */
    private fun unmapped(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val bytes = address.address
        val isMapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return if (isMapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }

    /** Usable addresses on [port], best first: Wi-Fi, wired, hotspot, other; then by interface name. */
    fun addresses(entries: List<Entry>, port: Int): List<Address> =
        entries.mapNotNull { entry -> kind(entry)?.let { Address(it, entry.name, entry.ipv4, port) } }
            .distinctBy { it.host }
            .sortedWith(compareBy<Address> { it.kind.ordinal }.thenBy { it.interfaceName })
}
