package com.anupshrestha.pairloom.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** This phone's side of the Wi-Fi Direct link: the group's interface and our IPv4 address on it. */
data class P2pEndpoint(
    val interfaceName: String,
    val localAddress: Inet4Address,
    val prefixLength: Int,
) {
    override fun toString(): String = "$interfaceName ${localAddress.hostAddress}/$prefixLength"
}

/** The connection could not be proven to run over the Wi-Fi Direct link, so it is refused. */
class LinkUnverifiedException(message: String) : IOException(message)

/**
 * Checks that keep the control link on the Wi-Fi Direct interface. They stop the host from
 * accepting connections from other networks (such as the home Wi-Fi) and stop the joining phone
 * from connecting anywhere but the group. They are not peer authentication: any device inside the
 * Wi-Fi Direct group still passes them.
 *
 * Each check returns null when it passes, or a human-readable reason when it fails.
 */
object LinkEndpoint {
    /** Wi-Fi Direct groups use small private subnets; anything wider is treated as suspicious. */
    const val MIN_PREFIX = 16
    const val MAX_PREFIX = 30

    /** Looks up the IPv4 address of [interfaceName], or returns null if it has none. */
    fun resolve(interfaceName: String): P2pEndpoint? {
        val nif = try {
            NetworkInterface.getByName(interfaceName)
        } catch (e: SocketException) {
            null
        } ?: return null
        val address = nif.interfaceAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return P2pEndpoint(interfaceName, address.address as Inet4Address, address.networkPrefixLength.toInt())
    }

    fun checkEndpoint(endpoint: P2pEndpoint): String? = when {
        endpoint.prefixLength !in MIN_PREFIX..MAX_PREFIX ->
            "unexpected subnet size /${endpoint.prefixLength} on ${endpoint.interfaceName}"

        endpoint.localAddress.isLoopbackAddress || endpoint.localAddress.isAnyLocalAddress ->
            "${endpoint.interfaceName} has no usable address"

        else -> null
    }

    /** The host's interface address must match the group owner address Android reported, if any. */
    fun checkHostAddress(endpoint: P2pEndpoint, reportedOwnerAddress: String?): String? {
        if (reportedOwnerAddress == null) return null
        val reported = parseIpv4(reportedOwnerAddress)
            ?: return "the reported group owner address $reportedOwnerAddress is not IPv4"
        return if (reported == endpoint.localAddress) {
            null
        } else {
            "${endpoint.interfaceName} has ${endpoint.localAddress.hostAddress} but Android reported ${reported.hostAddress} as the group owner"
        }
    }

    /** The other end must be another address inside this phone's Wi-Fi Direct subnet. */
    fun checkPeer(endpoint: P2pEndpoint, peer: InetAddress?): String? {
        val ipv4 = toIpv4(peer) ?: return "${peer?.hostAddress ?: "unknown address"} is not an IPv4 address"
        if (ipv4 == endpoint.localAddress) return "${ipv4.hostAddress} is this phone's own address"
        if (!sameSubnet(ipv4, endpoint.localAddress, endpoint.prefixLength)) {
            return "${ipv4.hostAddress} is outside the Wi-Fi Direct subnet ${endpoint.localAddress.hostAddress}/${endpoint.prefixLength}"
        }
        return null
    }

    /** After connecting, both ends of the socket must be the ones we intended. */
    fun checkConnected(endpoint: P2pEndpoint, local: InetAddress?, remote: InetAddress?, target: Inet4Address): String? {
        val localV4 = toIpv4(local)
        if (localV4 != endpoint.localAddress) {
            return "the socket left from ${local?.hostAddress}, not from ${endpoint.localAddress.hostAddress} on ${endpoint.interfaceName}"
        }
        if (toIpv4(remote) != target) return "the socket reached ${remote?.hostAddress}, not ${target.hostAddress}"
        return null
    }

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    /** Parses dotted-quad IPv4 text without ever doing a DNS lookup. */
    fun parseIpv4(text: String?): Inet4Address? {
        if (text == null) return null
        val parts = text.trim().removePrefix("/").split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** Accepts IPv4 and IPv4-mapped IPv6 (::ffff:a.b.c.d) addresses. */
    fun toIpv4(address: InetAddress?): Inet4Address? = when (address) {
        is Inet4Address -> address
        is Inet6Address -> {
            val b = address.address
            val mapped = (0..9).all { b[it] == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) as Inet4Address else null
        }

        else -> null
    }

    private fun toInt(address: Inet4Address): Int =
        address.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
}
