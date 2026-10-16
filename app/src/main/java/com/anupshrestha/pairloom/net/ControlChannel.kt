package com.anupshrestha.pairloom.net

import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.session.PingStats
import com.anupshrestha.pairloom.session.PingTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/**
 * The TCP control link between the two phones: a HELLO exchange followed by a ping/pong heartbeat
 * in both directions.
 *
 * All reads and writes are blocking and must run off the main thread. [close] is safe from any
 * thread and unblocks a pending read by closing the socket.
 */
class ControlChannel(
    private val socket: Socket,
    private val localName: String,
    private val log: (String) -> Unit,
) : Closeable {
    sealed interface Exit {
        data object LocalClose : Exit
        data object PeerClosed : Exit
        data object LinkTimeout : Exit
        data class ProtocolError(val detail: String) : Exit
        data class IoError(val detail: String) : Exit
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private val writeLock = Any()

    @Volatile private var exit: Exit? = null

    @Volatile private var lastRxNanos = System.nanoTime()

    private val tracker = PingTracker()

    /**
     * Sends our HELLO and waits for the peer's. Returns the peer's device name.
     *
     * @throws ControlProtocol.VersionMismatchException if the peer speaks another version
     * @throws IOException on timeout, a closed socket, or invalid data
     */
    fun handshake(timeoutMs: Int): String {
        socket.soTimeout = timeoutMs
        send(Message.Hello(ControlProtocol.VERSION, localName))
        val first = ControlProtocol.read(input)
        socket.soTimeout = 0
        val hello = first as? Message.Hello
            ?: throw ControlProtocol.ProtocolException("Expected HELLO, got $first")
        if (hello.version != ControlProtocol.VERSION) {
            throw ControlProtocol.VersionMismatchException(hello.version)
        }
        lastRxNanos = System.nanoTime()
        return hello.deviceName
    }

    /**
     * Runs the heartbeat until the link ends and returns why. Reads happen on an IO thread; the
     * ping loop runs alongside. [onStats] is called from IO threads.
     */
    suspend fun run(onStats: (PingStats) -> Unit): Exit = coroutineScope {
        val pinger = launch(Dispatchers.IO) { pingLoop(onStats) }
        val result = withContext(Dispatchers.IO) { readLoop(onStats) }
        pinger.cancel()
        result
    }

    /** Closes the socket without telling the peer. */
    override fun close() = close(sendBye = false)

    /** Closes the socket, first telling the peer if [sendBye] is set. Safe to call repeatedly. */
    fun close(sendBye: Boolean) {
        if (exit == null) exit = Exit.LocalClose
        if (sendBye) runCatching { send(Message.Bye) }
        closeSocket()
    }

    private fun readLoop(onStats: (PingStats) -> Unit): Exit {
        val result: Exit = try {
            while (true) {
                val message = ControlProtocol.read(input)
                val now = System.nanoTime()
                lastRxNanos = now
                when (message) {
                    is Message.Ping -> send(Message.Pong(message.seq, message.sentNanos))
                    is Message.Pong -> {
                        if (tracker.onPong(message.seq, now)) onStats(tracker.snapshot())
                    }

                    Message.Bye -> {
                        if (exit == null) exit = Exit.PeerClosed
                        break
                    }

                    is Message.Hello -> throw ControlProtocol.ProtocolException("Unexpected HELLO")
                }
            }
            exit ?: Exit.PeerClosed
        } catch (e: EOFException) {
            exit ?: Exit.PeerClosed
        } catch (e: ControlProtocol.ProtocolException) {
            exit ?: Exit.ProtocolError(e.message ?: "protocol error")
        } catch (e: IOException) {
            exit ?: Exit.IoError(e.message ?: e.javaClass.simpleName)
        } finally {
            closeSocket()
        }
        return result
    }

    private suspend fun pingLoop(onStats: (PingStats) -> Unit) {
        var seq = 0
        while (currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            if (now - lastRxNanos > DEAD_AFTER_NANOS) {
                log("No data from the peer for ${DEAD_AFTER_NANOS / 1_000_000_000} s, closing link")
                if (exit == null) exit = Exit.LinkTimeout
                closeSocket()
                return
            }
            tracker.expire(now)
            try {
                tracker.onPingSent(seq, now)
                send(Message.Ping(seq, now))
                seq++
            } catch (e: IOException) {
                closeSocket()
                return
            }
            onStats(tracker.snapshot())
            delay(PING_INTERVAL_MS)
        }
    }

    private fun send(message: Message) {
        val bytes = ControlProtocol.encode(message)
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun closeSocket() {
        runCatching { socket.close() }
    }

    private companion object {
        const val PING_INTERVAL_MS = 1000L
        const val DEAD_AFTER_NANOS = 5_000_000_000L
    }
}
