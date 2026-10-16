package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.net.LinkEndpoint
import com.anupshrestha.pairloom.net.P2pEndpoint
import com.anupshrestha.pairloom.net.SocketRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

class LinkEndpointTest {
    private fun v4(text: String): Inet4Address = LinkEndpoint.parseIpv4(text)!!

    private val host = P2pEndpoint("p2p0", v4("192.168.49.1"), 24)
    private val client = P2pEndpoint("p2p0", v4("192.168.49.128"), 24)

    @Test
    fun parsesOnlyNumericIpv4WithoutDns() {
        assertEquals("192.168.49.1", v4("192.168.49.1").hostAddress)
        assertEquals("192.168.49.1", v4("/192.168.49.1").hostAddress) // InetAddress.toString form
        for (bad in listOf(null, "", "localhost", "192.168.49", "192.168.49.256", "1.2.3.4.5", "+1.2.3.4", "a.b.c.d", "1..2.3")) {
            assertNull("should reject $bad", LinkEndpoint.parseIpv4(bad))
        }
    }

    @Test
    fun hostAcceptsOnlyOtherAddressesInsideTheGroupSubnet() {
        assertNull(LinkEndpoint.checkPeer(host, v4("192.168.49.128")))
        assertNull(LinkEndpoint.checkPeer(host, v4("192.168.49.200")))
        assertNotNull(LinkEndpoint.checkPeer(host, v4("192.168.1.20"))) // home Wi-Fi device
        assertNotNull(LinkEndpoint.checkPeer(host, v4("192.168.50.2")))
        assertNotNull(LinkEndpoint.checkPeer(host, v4("192.168.49.1"))) // ourselves
        assertNotNull(LinkEndpoint.checkPeer(host, null))
    }

    @Test
    fun ipv4MappedIpv6PeersAreUnderstood() {
        val mapped = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 192.toByte(), 168.toByte(), 49, 128.toByte()),
        )
        assertNull(LinkEndpoint.checkPeer(host, mapped))
        val realIpv6 = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 })
        assertNotNull(LinkEndpoint.checkPeer(host, realIpv6))
    }

    @Test
    fun clientRefusesTargetsOutsideTheGroup() {
        assertNull(LinkEndpoint.checkPeer(client, v4("192.168.49.1")))
        // A fallback target that happens to exist on another network must be refused.
        val otherNetwork = P2pEndpoint("p2p0", v4("10.20.0.5"), 24)
        assertNotNull(LinkEndpoint.checkPeer(otherNetwork, v4("192.168.49.1")))
    }

    @Test
    fun connectedSocketMustUseTheWifiDirectAddressAndIntendedTarget() {
        val target = v4("192.168.49.1")
        assertNull(LinkEndpoint.checkConnected(client, v4("192.168.49.128"), target, target))
        assertNotNull(LinkEndpoint.checkConnected(client, v4("192.168.1.20"), target, target)) // left via home Wi-Fi
        assertNotNull(LinkEndpoint.checkConnected(client, v4("192.168.49.128"), v4("192.168.49.2"), target))
        assertNotNull(LinkEndpoint.checkConnected(client, null, target, target))
    }

    @Test
    fun hostAddressMustMatchWhatAndroidReported() {
        assertNull(LinkEndpoint.checkHostAddress(host, null))
        assertNull(LinkEndpoint.checkHostAddress(host, "192.168.49.1"))
        assertNotNull(LinkEndpoint.checkHostAddress(host, "192.168.49.2"))
        assertNotNull(LinkEndpoint.checkHostAddress(host, "fe80::1"))
    }

    @Test
    fun suspiciousEndpointsAreRefused() {
        assertNull(LinkEndpoint.checkEndpoint(host))
        assertNotNull(LinkEndpoint.checkEndpoint(P2pEndpoint("p2p0", v4("192.168.49.1"), 8)))
        assertNotNull(LinkEndpoint.checkEndpoint(P2pEndpoint("p2p0", v4("192.168.49.1"), 32)))
        assertNotNull(LinkEndpoint.checkEndpoint(P2pEndpoint("p2p0", v4("0.0.0.0"), 24)))
        assertNotNull(LinkEndpoint.checkEndpoint(P2pEndpoint("lo", v4("127.0.0.1"), 24)))
    }

    @Test
    fun subnetMathHandlesBoundaries() {
        assertTrue(LinkEndpoint.sameSubnet(v4("192.168.49.1"), v4("192.168.49.255"), 24))
        assertFalse(LinkEndpoint.sameSubnet(v4("192.168.49.1"), v4("192.168.48.255"), 24))
        assertTrue(LinkEndpoint.sameSubnet(v4("10.0.0.1"), v4("10.0.255.1"), 16))
        assertFalse(LinkEndpoint.sameSubnet(v4("10.0.0.1"), v4("10.0.0.2"), 33))
    }

    private class Tracked : Closeable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun registryClosesEverythingOnTeardownAndRejectsLateSockets() {
        val registry = SocketRegistry()
        val a = Tracked()
        val b = Tracked()
        assertTrue(registry.register(a))
        assertTrue(registry.register(b))
        registry.release(b) // already handled by its owner
        registry.closeAll()
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        assertTrue(registry.isClosed)

        // A socket opened on a background thread after teardown is closed immediately.
        val late = Tracked()
        assertFalse(registry.register(late))
        assertEquals(1, late.closed)

        registry.closeAll() // second teardown is harmless
        assertEquals(1, a.closed)
    }
}
