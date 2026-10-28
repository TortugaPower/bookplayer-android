package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.Address
import com.tortugapower.browserdrop.internal.InterfaceAddresses.Entry
import com.tortugapower.browserdrop.internal.InterfaceAddresses.PathKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** SwiftBrowserDrop's `InterfaceAddressesTests`, with the interface names Android uses. */
class InterfaceAddressesTest {
    /** A phone sharing its mobile data over its hotspot: cellular and the 464XLAT link drop out. */
    @Test
    fun keepsOnlyTheHotspotOnAPhoneSharingItsConnection() {
        val entries = listOf(
            Entry("rmnet_data0", "100.96.154.46", PathKind.CELLULAR),
            Entry("v4-rmnet_data0", "192.0.0.4"),
            Entry("wlan1", "169.254.183.37"),
            Entry("ap0", "192.168.43.1"),
            Entry("tun0", "100.101.1.2", PathKind.VPN)
        )
        val addresses = InterfaceAddresses.addresses(entries, port = 8080)
        assertEquals(listOf("192.168.43.1"), addresses.map { it.host })
        assertEquals(Address.Kind.HOTSPOT, addresses.single().kind)
    }

    @Test
    fun letsTheSystemNameInterfaces() {
        val entries = listOf(
            Entry("eth9", "10.0.0.9"),
            Entry("swlan0", "192.168.43.1"),
            Entry("eth0", "10.0.0.5", PathKind.WIRED),
            Entry("wlan0", "192.168.1.5", PathKind.WIFI),
            Entry("wlan2", "10.0.0.7", PathKind.CELLULAR)
        )
        val addresses = InterfaceAddresses.addresses(entries, port = 8080)
        assertEquals(listOf("192.168.1.5", "10.0.0.5", "192.168.43.1", "10.0.0.9"), addresses.map { it.host })
        assertEquals(
            listOf(Address.Kind.WIFI, Address.Kind.WIRED, Address.Kind.HOTSPOT, Address.Kind.OTHER),
            addresses.map { it.kind }
        )
        assertEquals(8080, addresses.first().port)
    }

    /** An interface seen both as a network and by NetworkInterface is listed once. */
    @Test
    fun listsAnAddressOnce() {
        val entries = listOf(Entry("wlan0", "192.168.1.5", PathKind.WIFI), Entry("wlan0", "192.168.1.5"))
        assertEquals(listOf(Address(Address.Kind.WIFI, "wlan0", "192.168.1.5", 8080)), InterfaceAddresses.addresses(entries, 8080))
    }

    @Test
    fun dropsLoopbackAndLinkLocal() {
        val entries = listOf(Entry("wlan0", "127.0.0.1", PathKind.WIFI), Entry("eth0", "169.254.1.1", PathKind.WIRED))
        assertEquals(emptyList<Address>(), InterfaceAddresses.addresses(entries, 8080))
    }
}
