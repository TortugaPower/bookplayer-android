package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.Address
import com.tortugapower.browserdrop.internal.InterfaceAddresses.Entry
import com.tortugapower.browserdrop.internal.InterfaceAddresses.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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

    /** A connection is served only on an address the app shows, or on loopback: never on cellular. */
    @Test
    fun servesOnlyConnectionsToAShownAddress() {
        val shown = InterfaceAddresses.addresses(listOf(Entry("wlan0", "192.168.1.20", PathKind.WIFI)), 8080)
        fun served(address: String) = InterfaceAddresses.isShown(InetAddress.getByName(address), shown)

        assertTrue(served("192.168.1.20"))
        assertTrue(served("::ffff:192.168.1.20")) // An IPv4 connection on a dual-stack socket.
        assertTrue(served("127.0.0.1"))
        assertTrue(served("::1"))

        assertFalse(served("100.96.154.46")) // Cellular.
        assertFalse(served("2607:fb90:1234::5")) // A global IPv6 address.
        assertFalse(served("fe80::1")) // IPv6 link-local: the app shows IPv4 only.
        assertFalse(InterfaceAddresses.isShown(InetAddress.getByName("192.168.1.20"), emptyList()))
    }
}
