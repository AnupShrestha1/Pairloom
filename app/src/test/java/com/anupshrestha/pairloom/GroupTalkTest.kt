package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.AudioCodec
import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.GroupHost
import com.anupshrestha.pairloom.media.GroupMemberLink
import com.anupshrestha.pairloom.media.InProcessLink
import com.anupshrestha.pairloom.media.MediaEnd
import com.anupshrestha.pairloom.media.MediaSession
import com.anupshrestha.pairloom.net.ControlProtocol.LeaveReason
import com.anupshrestha.pairloom.net.ControlProtocol.LinkQuality
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

/** Group talk set-up (Phase 7, G2): joining, the roster, the floor, leaving, and the group's codec. */
class GroupTalkTest {
    private val lo: InetAddress = InetAddress.getLoopbackAddress()

    private fun waitFor(what: String, ms: Long = 2_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun member(host: GroupHost, name: String, ended: MutableList<MediaEnd>) = InProcessLink(host, name, lo) { send ->
        GroupMemberLink(
            lo, lo, send,
            newSession = { _, s, p, out, `in` -> MediaSession(s, p, out, `in`, verify = null, pushToTalk = true, hangoverFrames = 5) },
            onEnded = { ended += it },
        )
    }

    @Test
    fun membersJoinTalkInTurnAndLeave() {
        val host = GroupHost(lo)
        val ended = Collections.synchronizedList(mutableListOf<MediaEnd>())
        val links = listOf("Redmi", "Galaxy", "Pixel").map { member(host, it, ended) }
        val (a, b, c) = links.map { it.member }
        try {
            waitFor("roster everywhere") { links.all { it.member.roster.size == 3 } }
            assertTrue(a.roster.none { it.inTalk })
            assertEquals(links.map { it.memberId }, links.map { it.member.ownId })
            links.forEach { it.member.join() }
            waitFor("all in the talk") { links.all { it.member.session != null } && host.roster.all { it.inTalk } }
            waitFor("roster says so") { links.all { l -> l.member.roster.count { it.inTalk } == 3 } }
            assertEquals(AudioCodec.PCM, a.codec)

            a.talking = true
            waitFor("floor to Redmi") { links.all { it.member.floorHolder == links[0].memberId } }
            Thread.sleep(400)
            a.talking = false
            waitFor("floor free") { links.all { it.member.floorHolder == null } }
            assertTrue("Galaxy played ${b.session!!.stats().played}", b.session!!.stats().played >= 30)
            assertTrue("Pixel played ${c.session!!.stats().played}", c.session!!.stats().played >= 30)
            assertEquals(0L, a.session!!.stats().played)

            b.leave()
            waitFor("Galaxy out") { host.roster.single { it.id == links[1].memberId }.let { !it.inTalk } }
            assertEquals(listOf(MediaEnd.ENDED_HERE), ended.toList())
            c.talking = true
            waitFor("floor to Pixel") { a.floorHolder == links[2].memberId }
            assertNull(b.floorHolder) // not in the talk: no floor news
            c.talking = false

            a.leave(); c.leave()
            waitFor("hub stopped") { host.hubStats().isEmpty() && host.roster.none { it.inTalk } }
            assertFalse(links.any { it.member.isBusy })
        } finally {
            links.forEach { it.close() }
            host.close()
        }
    }

    @Test
    fun unlinkedMemberLeavesTheTalkAndTheRoster() {
        val host = GroupHost(lo)
        val ended = Collections.synchronizedList(mutableListOf<MediaEnd>())
        val links = listOf("Redmi", "Galaxy").map { member(host, it, ended) }
        try {
            links.forEach { it.member.join() }
            waitFor("both in") { host.roster.count { it.inTalk } == 2 }
            links[1].close() // its phone went away
            waitFor("roster of one") { links[0].member.roster.size == 1 && host.hubStats().keys == setOf(links[0].memberId) }
        } finally {
            links.forEach { runCatching { it.close() } }
            host.close()
        }
    }

    @Test
    fun theFirstJoinFixesTheCodecAndAMemberWhoCannotUseItIsRefused() {
        val host = GroupHost(lo, localCodecs = { AudioCodec.OPUS.bit })
        val sentA = Collections.synchronizedList(mutableListOf<Message>())
        val sentB = Collections.synchronizedList(mutableListOf<Message>())
        val a = host.link("Opus phone", lo) { sentA += it }!!
        val b = host.link("PCM phone", lo) { sentB += it }!!
        fun join(codecs: Int, port: Int) = DirectionKey.random().let { Message.GroupJoin(codecs, port, it.key.toHex(), it.salt.toHex()) }
        try {
            host.onMessage(a, join(AudioCodec.OPUS.bit or AudioCodec.PCM.bit, 40_001))
            waitFor("welcome") { sentA.any { it is Message.GroupWelcome } }
            assertEquals(AudioCodec.OPUS.code, sentA.filterIsInstance<Message.GroupWelcome>().single().codec)
            host.onMessage(b, join(AudioCodec.PCM.bit, 40_002))
            waitFor("refusal") { sentB.any { it is Message.GroupLeave } }
            assertEquals(LeaveReason.REFUSED, sentB.filterIsInstance<Message.GroupLeave>().single().reason)
            assertEquals(listOf(true, false), host.roster.map { it.inTalk })
        } finally {
            host.close()
        }
    }

    @Test
    fun linkQualityFollowsWhatTheHubHears() {
        assertEquals(LinkQuality.GOOD, GroupHost.linkQuality(10.0))
        assertEquals(LinkQuality.FAIR, GroupHost.linkQuality(4.0))
        assertEquals(LinkQuality.WEAK, GroupHost.linkQuality(0.5))
        assertEquals(LinkQuality.LOST, GroupHost.linkQuality(0.0))
    }

    @Test
    fun theRosterMarksTheHostAndKeepsADroppedPhoneAsOutOfRangeUntilItComesBack() {
        val host = GroupHost(lo, qualityEveryMs = 0, departedKeepNanos = 60_000_000_000L)
        val sent = Collections.synchronizedList(mutableListOf<Message>())
        try {
            host.link("Redmi", lo, key = "self", isHost = true) {}
            val galaxy = host.link("Galaxy", lo, key = "g") { sent += it }!!
            val pixel = host.link("Pixel", lo, key = "p") {}!!
            assertEquals(listOf(true, false, false), host.roster.map { it.isHost })
            host.unlink(pixel, dropped = true)
            val gone = host.roster.single { it.name == "Pixel" }
            assertEquals(LinkQuality.LOST, gone.quality)
            assertFalse(gone.inTalk)
            host.unlink(galaxy) // left with a goodbye: not shown
            assertEquals(listOf("Redmi", "Pixel"), host.roster.map { it.name })
            host.link("Pixel", lo, key = "p") {}
            assertEquals(listOf("Redmi", "Pixel"), host.roster.map { it.name })
            assertEquals(LinkQuality.UNKNOWN, host.roster.single { it.name == "Pixel" }.quality)
            // Forgotten once out of range for too long.
            val h2 = GroupHost(lo, qualityEveryMs = 0, departedKeepNanos = 1)
            val x = h2.link("X", lo, key = "x") {}!!
            h2.unlink(x, dropped = true)
            h2.refreshLinks(System.nanoTime() + 10)
            assertTrue(h2.roster.isEmpty())
            h2.close()
        } finally {
            host.close()
        }
    }

    @Test
    fun membersInTheTalkAreMeasuredAsGoodOverLoopback() {
        val host = GroupHost(lo, qualityEveryMs = 0)
        val ended = Collections.synchronizedList(mutableListOf<MediaEnd>())
        val links = listOf("Redmi", "Galaxy").map { member(host, it, ended) }
        try {
            links.forEach { it.member.join() }
            waitFor("both in") { host.roster.count { it.inTalk } == 2 }
            repeat(25) { host.refreshLinks(); Thread.sleep(100) }
            assertEquals(listOf(LinkQuality.GOOD, LinkQuality.GOOD), host.roster.map { it.quality })
            waitFor("members see it") { links.all { l -> l.member.roster.all { it.quality == LinkQuality.GOOD } } }
        } finally {
            links.forEach { it.close() }
            host.close()
        }
    }
}
