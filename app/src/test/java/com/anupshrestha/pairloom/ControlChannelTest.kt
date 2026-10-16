package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class ControlChannelTest {
    private val sockets = mutableListOf<Socket>()

    @After
    fun close() = sockets.forEach { runCatching { it.close() } }

    private fun pair(deadMs: Long, pingMs: Long): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        sockets += client
        sockets += accepted
        return ControlChannel(accepted, deadMs * 1_000_000, pingMs) {} to ControlChannel(client, deadMs * 1_000_000, pingMs) {}
    }

    /** Regression: a long pause during setup (pairing) must not count against the heartbeat. */
    @Test
    fun heartbeatTimerStartsWhenTheHeartbeatStarts() = runBlocking {
        val (a, b) = pair(deadMs = 400, pingMs = 50)
        a.sendMessage(Message.Status(1))
        b.receive(1_000)
        delay(700) // longer than the dead-link timeout, like users comparing codes
        val ra = async(Dispatchers.IO) { a.run {} }
        val rb = async(Dispatchers.IO) { b.run {} }
        delay(1_000) // both sides keep pinging; neither may declare the link dead
        assertTrue(!ra.isCompleted && !rb.isCompleted)
        a.close(sendBye = true)
        withTimeout(2_000) {
            assertEquals(ControlChannel.Exit.LocalClose, ra.await())
            assertEquals(ControlChannel.Exit.PeerClosed, rb.await())
        }
    }

    @Test
    fun silentPeerIsDetected() = runBlocking {
        val (a, _) = pair(deadMs = 300, pingMs = 50)
        val exit = withTimeout(3_000) { async(Dispatchers.IO) { a.run {} }.await() }
        assertEquals(ControlChannel.Exit.LinkTimeout, exit)
    }
}
