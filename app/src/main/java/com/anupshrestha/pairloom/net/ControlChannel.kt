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
 * The control link between the two phones, normally running on a mutual TLS socket. Session setup
 * (HELLO, STATUS, pairing) uses [send] and [receive]; after that [run] runs the ping/pong heartbeat
 * in both directions.
 *
 * All reads and writes are blocking and must run off the main thread. [close] is safe from any
 * thread and unblocks a pending read by closing the socket.
 */
class ControlChannel(
    private val socket: Socket,
    private val deadAfterNanos: Long = DEAD_AFTER_NANOS,
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
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

    /** Sends one message (session setup). */
    fun sendMessage(message: Message) = send(message)

    /**
     * Waits up to [timeoutMs] for the next message (session setup only, before [run]).
     *
     * @throws IOException on timeout, a closed socket, or invalid data
     */
    fun receive(timeoutMs: Int): Message {
        socket.soTimeout = timeoutMs
        try {
            return ControlProtocol.read(input).also { lastRxNanos = System.nanoTime() }
        } finally {
            runCatching { socket.soTimeout = 0 }
        }
    }

    /** Like [receive], but the message must be of type [T]. */
    inline fun <reified T : Message> expect(timeoutMs: Int): T {
        val message = receive(timeoutMs)
        return message as? T ?: throw ControlProtocol.ProtocolException("Expected ${T::class.simpleName}, got $message")
    }

    /**
     * Runs the heartbeat until the link ends and returns why. Reads happen on an IO thread; the
     * ping loop runs alongside. [onStats] and [onMedia] are called from IO threads; [onMedia]
     * receives the media-control messages (offer, answer, stop).
     */
    suspend fun run(onStats: (PingStats) -> Unit, onMedia: (Message) -> Unit = {}): Exit = coroutineScope {
        // Session setup can take a while (pairing waits for both users), so the dead-link timer
        // starts now, not at the last setup message.
        lastRxNanos = System.nanoTime()
        val pinger = launch(Dispatchers.IO) { pingLoop(onStats) }
        val result = withContext(Dispatchers.IO) { readLoop(onStats, onMedia) }
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

    /** True once [close] ran or the link ended. */
    val isClosed: Boolean get() = socket.isClosed

    private fun readLoop(onStats: (PingStats) -> Unit, onMedia: (Message) -> Unit): Exit {
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

                    is Message.MediaOffer, is Message.MediaAnswer, Message.MediaStop, is Message.GroupInfo -> onMedia(message)
                    else -> throw ControlProtocol.ProtocolException("Unexpected $message during the heartbeat")
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
            if (now - lastRxNanos > deadAfterNanos) {
                log("No data from the peer for ${deadAfterNanos / 1_000_000} ms, closing link")
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
            delay(pingIntervalMs)
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

    companion object {
        const val PING_INTERVAL_MS = 1000L
        const val DEAD_AFTER_NANOS = 5_000_000_000L
    }
}
