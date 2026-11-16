package com.tortugapower.browserdrop.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** What the server needs from the device, behind a seam so the server runs in plain JVM tests. */
internal interface Platform {
    /** A file of the bundled page, by its name in the page folder (`index.html`), or null if there's none. */
    fun pageFile(name: String): ByteArray?

    /** Bytes free for [folder]'s volume, measured now; null when it can't be told (the upload is let through). */
    fun availableBytes(folder: File): Long?

    /** IPv4 addresses of the interfaces that are up. */
    fun interfaceEntries(): List<InterfaceAddresses.Entry>

    /** Calls [onChange] when the device's networks change, until the result is closed. */
    fun watchNetworks(onChange: () -> Unit): AutoCloseable
}

internal class AndroidPlatform(private val context: Context) : Platform {
    // The string-keyed lookup, as the app does: the Class overload isn't there under every test runtime.
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun pageFile(name: String): ByteArray? = try {
        context.assets.open("$PAGE_ASSETS/$name").use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    /** The measure the app's own storage checks use (`StatFs`), so a transfer and an import agree. */
    override fun availableBytes(folder: File): Long? = try {
        StatFs(folder.path).availableBytes
    } catch (e: IllegalArgumentException) {
        null // The folder is gone (the session closed meanwhile): nothing to measure.
    }

    /**
     * Networks first, from ConnectivityManager, which says which is Wi-Fi or wired. Then every other interface
     * that is up, from `NetworkInterface`: the hotspot's and other tethering interfaces aren't networks of ours.
     */
    override fun interfaceEntries(): List<InterfaceAddresses.Entry> {
        val entries = mutableListOf<InterfaceAddresses.Entry>()
        @Suppress("DEPRECATION") // allNetworks: the callback alternative can't answer synchronously.
        for (network in connectivity?.allNetworks.orEmpty()) {
            val link = connectivity?.getLinkProperties(network) ?: continue
            val name = link.interfaceName ?: continue
            val kind = pathKind(connectivity.getNetworkCapabilities(network))
            for (address in link.linkAddresses) {
                val ipv4 = address.address as? Inet4Address ?: continue
                entries += InterfaceAddresses.Entry(name, ipv4.hostAddress ?: continue, kind)
            }
        }
        val known = entries.map { it.name }.toSet()
        try {
            for (networkInterface in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (networkInterface.name in known || !networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.inetAddresses.toList()) {
                    val ipv4 = address as? Inet4Address ?: continue
                    entries += InterfaceAddresses.Entry(networkInterface.name, ipv4.hostAddress ?: continue)
                }
            }
        } catch (e: SocketException) {
            // The networks above are still listed.
        }
        return entries
    }

    override fun watchNetworks(onChange: () -> Unit): AutoCloseable {
        val manager = connectivity ?: return AutoCloseable {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()
        }
        // Every network, a local one without internet access included.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, callback)
        return AutoCloseable {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    private fun pathKind(capabilities: NetworkCapabilities?): InterfaceAddresses.PathKind = when {
        capabilities == null -> InterfaceAddresses.PathKind.OTHER
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> InterfaceAddresses.PathKind.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InterfaceAddresses.PathKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> InterfaceAddresses.PathKind.WIRED
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InterfaceAddresses.PathKind.CELLULAR
        else -> InterfaceAddresses.PathKind.OTHER
    }

    companion object {
        /** Where the page lives in the merged assets: a subfolder, so it can't collide with the app's. */
        const val PAGE_ASSETS = "browserdrop/page"
    }
}
