package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.Floor
import com.anupshrestha.pairloom.media.FrameSink
import com.anupshrestha.pairloom.media.FrameSource
import com.anupshrestha.pairloom.media.GroupHub
import com.anupshrestha.pairloom.media.MediaSession
import com.anupshrestha.pairloom.media.Restamper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Group push-to-talk (Phase 7): the floor, re-stamping, and a hub with simulated phones over loopback UDP. */
class GroupHubTest {
    private val ms = 1_000_000L

    @Test
    fun floorGoesToTheFirstTalkerAndShutsOthersOutForTheirSpurt() {
        val changes = mutableListOf<String?>()
        val f = Floor(idleNanos = 500 * ms, maxHoldNanos = 10_000 * ms) { changes += it }
        assertTrue(f.audio("a", 0))
        assertFalse(f.audio("b", 10 * ms)) // busy
        assertTrue(f.audio("a", 20 * ms))
        f.spurtEnded("a")
        assertNull(f.holder)
        assertFalse(f.audio("b", 30 * ms)) // b is still in the spurt it started while busy
        f.spurtEnded("b")
        assertTrue(f.audio("b", 40 * ms))
        assertEquals(listOf("a", null, "b"), changes)
    }

    @Test
    fun floorIsTakenBackWhenTheTalkerGoesQuietOrHoldsTooLong() {
        val f = Floor(idleNanos = 500 * ms, maxHoldNanos = 2_000 * ms)
        f.audio("a", 0)
        assertNull(f.tick(400 * ms))
        assertEquals("a", f.tick(500 * ms)) // quiet: a lost TALK_END or a phone that went away
        assertTrue(f.audio("a", 600 * ms)) // not shut out
        var t = 600 * ms
        while (t < 2_600 * ms) { f.audio("a", t); assertNull(f.tick(t)); t += 100 * ms }
        assertEquals("a", f.tick(2_600 * ms)) // held too long
        assertFalse(f.audio("a", 2_610 * ms)) // until they let go
        assertTrue(f.audio("b", 2_620 * ms))
    }

    @Test
    fun restamperKeepsListenerIndexesRisingAcrossTalkers() {
        val r = Restamper(minGap = 64)
        r.start(firstIndex = 5_000, clockIndex = 100)
        assertEquals(100L, r.map(5_000))
        assertEquals(101L, r.map(5_001))
        // The next talker's own clock is far behind; its spurt still comes after the last one.
        r.start(firstIndex = 200, clockIndex = 120)
        assertEquals(101L + 64, r.map(200))
        assertEquals(101L + 54, r.map(190)) // reordered at the start of the spurt: still after the last one
        assertNull(r.map(130)) // would land on or before the previous spurt
        // With a long pause the hub's clock wins.
        r.start(firstIndex = 7, clockIndex = 1_000)
        assertEquals(1_000L, r.map(7))
    }

    // Frames that say who sent them and their position, so each listener can check what it played.
    private class Tagged(private val talker: Int) : FrameSource {
        override val paced = false
        override val frameBytes = 960
        override fun read(index: Long): ByteArray = frame(talker, index)

        companion object {
            const val MAGIC = 0x5A5A

            fun frame(talker: Int, index: Long): ByteArray {
                val s = IntArray(480) { ((index * 7 + it) and 0x3FFF).toInt() }
                s[0] = MAGIC; s[1] = talker
                for (k in 0 until 4) s[2 + k] = ((index shr (16 * k)) and 0xFFFF).toInt()
                return ByteArray(960).also { b -> s.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
            }

            /** (talker, index) if [b] is an intact tagged frame. */
            fun parse(b: ByteArray): Pair<Int, Long>? {
                fun s(i: Int) = (b[2 * i].toInt() and 0xFF) or ((b[2 * i + 1].toInt() and 0xFF) shl 8)
                if (b.size != 960 || s(0) != MAGIC) return null
                var index = 0L
                for (k in 0 until 4) index = index or (s(2 + k).toLong() shl (16 * k))
                return if (b.contentEquals(frame(s(1), index))) s(1) to index else null
            }
        }
    }

    private class Heard : FrameSink {
        override val paced = false
        val frames = mutableListOf<Pair<Int, Long>>()
        override fun write(frame: ByteArray) {
            Tagged.parse(frame)?.let { synchronized(frames) { frames += it } }
        }

        fun snapshot() = synchronized(frames) { frames.toList() }
        fun from(talker: Int) = snapshot().count { it.first == talker }
    }

    private class Phone(val id: Int, hub: InetSocketAddress, hubObj: GroupHub) {
        private val lo = InetAddress.getLoopbackAddress()
        val heard = Heard()
        private val socket = DatagramSocket(InetSocketAddress(lo, 0))
        private val up = DirectionKey.random()
        private val down = DirectionKey.random()
        val session = MediaSession(
            socket, hub, up, down, source = Tagged(id), sink = heard, verify = null, pushToTalk = true, hangoverFrames = 5,
        )

        init {
            hubObj.addMember("phone$id", InetSocketAddress(lo, socket.localPort), fromMember = up, toMember = down)
        }

        var talking: Boolean
            get() = session.talking
            set(v) { session.talking = v }
    }

    @Test
    fun hubRelaysOneTalkerAtATimeToEveryOtherPhone() {
        val lo = InetAddress.getLoopbackAddress()
        val floorChanges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val hub = GroupHub(DatagramSocket(InetSocketAddress(lo, 0)), onFloor = { floorChanges += it })
        val hubAddress = InetSocketAddress(lo, hub.port)
        val phones = (1..3).map { Phone(it, hubAddress, hub) }
        val (a, b, c) = phones
        hub.start()
        phones.forEach { it.session.start() }
        try {
            Thread.sleep(300)
            // 1. Phone 1 talks; 2 and 3 hear it.
            a.talking = true; Thread.sleep(600); a.talking = false; Thread.sleep(400)
            // 2. Phone 2 takes over (its own frame count is unrelated to phone 1's).
            b.talking = true; Thread.sleep(600); b.talking = false; Thread.sleep(400)
            val beforeConflict = phones.map { it.heard.snapshot().size }
            // 3. Phones 1 and 3 press together: 1 is first and keeps the floor; 3 stays shut out
            //    for its whole press, even after 1 lets go.
            a.talking = true; Thread.sleep(40); c.talking = true
            Thread.sleep(500); a.talking = false
            Thread.sleep(500); c.talking = false
            Thread.sleep(400)
            val conflictHeardFrom3 = phones.map { it.heard.from(3) }
            // 4. Phone 3 presses again and is heard.
            c.talking = true; Thread.sleep(500); c.talking = false; Thread.sleep(400)

            val stats = hub.stats()
            for (p in phones) {
                val s = stats.getValue("phone${p.id}")
                assertEquals("auth failures for ${p.id}", 0L, s.authFailures)
                assertTrue("rtt to ${p.id}: ${s.rttAvgMs}", s.rttAvgMs != null && s.rttAvgMs!! < 50.0)
                assertEquals("phone ${p.id} heard itself", 0, p.heard.from(p.id))
                // What each phone played: runs by talker, each run's frames rising and contiguous.
                val heard = p.heard.snapshot()
                for (i in 1 until heard.size) {
                    if (heard[i].first == heard[i - 1].first) assertTrue("phone ${p.id} order at $i: ${heard[i - 1]} -> ${heard[i]}", heard[i].second > heard[i - 1].second)
                }
            }
            // Spurt 1 (60 frames held + hangover) and spurt 2 reached both listeners nearly whole.
            assertTrue("2 heard ${b.heard.from(1)} from 1", b.heard.from(1) >= 2 * 55)
            assertTrue("3 heard ${c.heard.from(1)} from 1", c.heard.from(1) >= 2 * 55)
            assertTrue("1 heard ${a.heard.from(2)} from 2", a.heard.from(2) >= 55)
            assertTrue("3 heard ${c.heard.from(2)} from 2", c.heard.from(2) >= 55)
            assertTrue(beforeConflict.all { it > 0 })
            // During the conflict nobody heard phone 3; afterwards everyone else did.
            assertEquals(listOf(0, 0, 0), conflictHeardFrom3)
            assertTrue("1 heard ${a.heard.from(3)} from 3", a.heard.from(3) >= 45)
            assertTrue("2 heard ${b.heard.from(3)} from 3", b.heard.from(3) >= 45)
            assertTrue("3 was shut out", stats.getValue("phone3").shutOut > 0)
            assertEquals(listOf("phone1", null, "phone2", null, "phone1", null, "phone3", null), floorChanges.toList())
        } finally {
            phones.forEach { it.session.stop() }
            hub.stop()
        }
    }

    @Test
    fun aPhoneThatLeavesMidSpurtFreesTheFloor() {
        val lo = InetAddress.getLoopbackAddress()
        val hub = GroupHub(DatagramSocket(InetSocketAddress(lo, 0)))
        val hubAddress = InetSocketAddress(lo, hub.port)
        val phones = (1..3).map { Phone(it, hubAddress, hub) }
        hub.start()
        phones.forEach { it.session.start() }
        try {
            phones[0].talking = true
            Thread.sleep(300)
            assertEquals("phone1", hub.holder)
            phones[0].session.stop() // the phone vanishes without a TALK_END
            Thread.sleep(700)
            assertNull(hub.holder)
            phones[1].talking = true; Thread.sleep(400); phones[1].talking = false; Thread.sleep(300)
            assertTrue("3 heard ${phones[2].heard.from(2)} from 2", phones[2].heard.from(2) >= 30)
            hub.removeMember("phone1")
            assertEquals(listOf("phone2", "phone3"), hub.memberIds)
        } finally {
            phones.drop(1).forEach { it.session.stop() }
            hub.stop()
        }
    }
}
