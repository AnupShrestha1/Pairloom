package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.p2p.GroupCredentials
import com.anupshrestha.pairloom.p2p.P2pPermissions
import com.anupshrestha.pairloom.session.GroupSnapshot
import com.anupshrestha.pairloom.session.JoinFallback
import com.anupshrestha.pairloom.session.LinkDetails
import com.anupshrestha.pairloom.session.LinkState
import com.anupshrestha.pairloom.session.PingTracker
import com.anupshrestha.pairloom.session.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.session.LinkNotice
import com.anupshrestha.pairloom.session.linkNotice
import com.anupshrestha.pairloom.session.hostsFor
import com.anupshrestha.pairloom.session.LinkUiState
import com.anupshrestha.pairloom.session.DisconnectReason
import org.junit.Test

class LinkStateTest {
    private val ms = 1_000_000L

    @Test
    fun rttIsMeasuredFromTheMatchingPing() {
        val tracker = PingTracker()
        tracker.onPingSent(0, 0)
        tracker.onPingSent(1, 1000 * ms)
        assertTrue(tracker.onPong(1, 1012 * ms))
        assertTrue(tracker.onPong(0, 1100 * ms))
        val stats = tracker.snapshot()
        assertEquals(2, stats.sent)
        assertEquals(2, stats.received)
        assertEquals(0, stats.lost)
        assertEquals(12.0, stats.minRttMs!!, 0.001)
        assertEquals(1100.0, stats.maxRttMs!!, 0.001)
        assertEquals(1100.0, stats.lastRttMs!!, 0.001)
        assertEquals(556.0, stats.avgRttMs!!, 0.001)
    }

    @Test
    fun unknownOrDuplicatePongIsIgnored() {
        val tracker = PingTracker()
        tracker.onPingSent(5, 0)
        assertFalse(tracker.onPong(9, 10 * ms))
        assertTrue(tracker.onPong(5, 10 * ms))
        assertFalse(tracker.onPong(5, 20 * ms))
        assertEquals(1, tracker.snapshot().received)
    }

    @Test
    fun unansweredPingsAreCountedLostOnceAndLatePongIsIgnored() {
        val tracker = PingTracker(lostAfterNanos = 3000 * ms)
        tracker.onPingSent(0, 0)
        tracker.onPingSent(1, 2000 * ms)
        tracker.expire(2500 * ms)
        assertEquals(0, tracker.snapshot().lost)
        tracker.expire(3500 * ms)
        assertEquals(1, tracker.snapshot().lost)
        tracker.expire(3600 * ms)
        assertEquals(1, tracker.snapshot().lost)
        assertFalse(tracker.onPong(0, 3700 * ms))
        assertTrue(tracker.onPong(1, 3700 * ms))
    }

    @Test
    fun emptyTrackerReportsNoRtt() {
        val stats = PingTracker().snapshot()
        assertEquals(0, stats.sent)
        assertNull(stats.lastRttMs)
        assertNull(stats.avgRttMs)
    }

    @Test
    fun onlyInProgressStatesBlockANewSession() {
        val details = LinkDetails(true, null, "peer", 1, 1)
        assertTrue(LinkState.Hosting(false, null).isActive)
        assertTrue(LinkState.Discovering(emptyList(), false).isActive)
        assertTrue(LinkState.Connecting("peer").isActive)
        assertTrue(LinkState.OpeningSocket(false).isActive)
        assertTrue(LinkState.Linked(details).isActive)
        assertFalse(LinkState.Idle.isActive)
        assertFalse(LinkState.NeedsPermissions(true).isActive)
        assertFalse(LinkState.LocationServicesOff.isActive)
    }

    private val peer = "02:00:00:00:00:01"

    private fun group(isOwner: Boolean, owner: String?) =
        GroupSnapshot(networkName = "DIRECT-ab-Test", isGroupOwner = isOwner, ownerDeviceAddress = owner, frequencyMhz = 2462, clientCount = 0)

    @Test
    fun fallbackRequiresBeingAClientOfTheChosenPeer() {
        assertTrue(JoinFallback.isClientOfPeer(group(false, peer), peer))
        assertTrue(JoinFallback.isClientOfPeer(group(false, peer.uppercase()), peer))
        assertFalse(JoinFallback.isClientOfPeer(null, peer))
        assertFalse(JoinFallback.isClientOfPeer(group(true, peer), peer)) // this phone owns the group
        assertFalse(JoinFallback.isClientOfPeer(group(false, "aa:bb:cc:dd:ee:ff"), peer)) // someone else's group
        assertFalse(JoinFallback.isClientOfPeer(group(false, null), peer))
    }

    @Test
    fun fallbackWaitsForTheGracePeriod() {
        val g = group(false, peer)
        assertFalse(JoinFallback.shouldUse(g, peer, clientSinceMs = null, nowMs = 10_000))
        assertFalse(JoinFallback.shouldUse(g, peer, clientSinceMs = 10_000, nowMs = 12_999))
        assertTrue(JoinFallback.shouldUse(g, peer, clientSinceMs = 10_000, nowMs = 13_000))
        assertFalse(JoinFallback.shouldUse(group(true, peer), peer, clientSinceMs = 0, nowMs = 60_000))
    }

    @Test
    fun groupCredentialsMeetAndroidRules() {
        repeat(200) {
            val c = GroupCredentials.generate()
            assertTrue(c.networkName, c.networkName.matches(Regex("^DIRECT-[a-zA-Z0-9]{2}.*")))
            assertTrue(c.networkName.toByteArray(Charsets.UTF_8).size <= 32)
            assertEquals(GroupCredentials.PASSPHRASE_LENGTH, c.passphrase.length)
            assertTrue(c.passphrase.length in 8..63)
            assertTrue(c.passphrase.all { it.isLetterOrDigit() && it.code < 128 })
        }
    }

    @Test
    fun groupCredentialsAreRandomAndNeverPrintThePassphrase() {
        val passphrases = (1..50).map { GroupCredentials.generate().passphrase }.toSet()
        assertEquals(50, passphrases.size)
        val c = GroupCredentials.generate()
        assertFalse(c.toString().contains(c.passphrase))
        assertTrue(c.toString().contains(c.networkName))
    }

    @Test
    fun permissionsFollowTheAndroidVersion() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), P2pPermissions.required(26))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), P2pPermissions.required(32))
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), P2pPermissions.required(33))
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), P2pPermissions.required(36))
        assertTrue(P2pPermissions.locationServicesRequired(32))
        assertFalse(P2pPermissions.locationServicesRequired(33))
        assertNotNull(P2pPermissions.required(30))
    }

    // --- background notification and Connect roles ----------------------------------------------

    private fun linked(
        incoming: MediaKind? = null,
        active: MediaKind? = null,
        muted: Boolean = false,
        reconnectingTo: String? = null,
    ) = LinkUiState(
        link = LinkState.Linked(
            LinkDetails(
                isGroupOwner = true, ownerAddress = null, peerName = "SM-A037F", groupFormedMs = 0, socketMs = 0,
                incomingCall = incoming != null, incomingKind = incoming,
                mediaActive = active != null, mediaKind = active, muted = muted,
            ),
        ),
        reconnectingTo = reconnectingTo,
    )

    @Test
    fun theNotificationFollowsTheLink() {
        assertNull(linkNotice(LinkUiState(), visible = true)) // no link: no service
        assertEquals(LinkNotice.Linked("SM-A037F"), linkNotice(linked(), visible = true))
        assertEquals(LinkNotice.InCall("SM-A037F", muted = true), linkNotice(linked(active = MediaKind.VOICE, muted = true), visible = false))
        assertEquals(LinkNotice.PushToTalk("SM-A037F"), linkNotice(linked(active = MediaKind.PTT), visible = true))
        // The test tone is not a call: just "connected".
        assertEquals(LinkNotice.Linked("SM-A037F"), linkNotice(linked(active = MediaKind.TONE), visible = true))
    }

    @Test
    fun anIncomingCallRingsOnlyWhilePairloomIsNotOnScreen() {
        assertEquals(LinkNotice.Linked("SM-A037F"), linkNotice(linked(incoming = MediaKind.VOICE), visible = true))
        assertEquals(LinkNotice.Incoming("SM-A037F", pushToTalk = false), linkNotice(linked(incoming = MediaKind.VOICE), visible = false))
        assertEquals(LinkNotice.Incoming("SM-A037F", pushToTalk = true), linkNotice(linked(incoming = MediaKind.PTT), visible = false))
    }

    @Test
    fun reconnectingKeepsTheNotificationUpBetweenAttempts() {
        val dropped = LinkUiState(link = LinkState.Disconnected(DisconnectReason.LINK_TIMEOUT, null), reconnectingTo = "SM-A037F")
        assertEquals(LinkNotice.Reconnecting("SM-A037F"), linkNotice(dropped, visible = true))
        // Once linked again the link wins, even before the flag is cleared.
        assertEquals(LinkNotice.Linked("SM-A037F"), linkNotice(linked(reconnectingTo = "SM-A037F"), visible = true))
        assertTrue(LinkNotice.InCall("x", false).usesMicrophone)
        assertTrue(LinkNotice.PushToTalk("x").usesMicrophone)
        assertTrue(!LinkNotice.Reconnecting("x").usesMicrophone)
    }

    @Test
    fun exactlyOnePhoneHosts() {
        val redmi = "4e4f" + "0".repeat(60)
        val a03s = "6E56" + "0".repeat(60)
        assertTrue(hostsFor(redmi, a03s))
        assertTrue(!hostsFor(a03s, redmi))
        assertTrue("case does not matter", hostsFor(redmi.uppercase(), a03s.lowercase()))
    }
}
