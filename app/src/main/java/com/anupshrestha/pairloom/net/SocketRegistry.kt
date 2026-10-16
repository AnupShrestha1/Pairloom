package com.anupshrestha.pairloom.net

import java.io.Closeable

/**
 * Every socket one session opens. When the session ends, [closeAll] closes them all, and anything
 * registered afterwards is closed immediately. That way a socket created on a background thread
 * just as the session is torn down can never outlive it.
 */
class SocketRegistry {
    private val open = LinkedHashSet<Closeable>()
    private var closed = false

    /** Tracks [resource]. Returns false, after closing it, if the session has already ended. */
    fun register(resource: Closeable): Boolean {
        val accepted = synchronized(this) {
            if (closed) false else open.add(resource).let { true }
        }
        if (!accepted) closeQuietly(resource)
        return accepted
    }

    /** Stops tracking [resource], typically right after closing it. */
    fun release(resource: Closeable) {
        synchronized(this) { open.remove(resource) }
    }

    val isClosed: Boolean
        get() = synchronized(this) { closed }

    /** Closes everything still tracked and refuses new registrations. Safe to call twice. */
    fun closeAll() {
        val toClose = synchronized(this) {
            closed = true
            open.toList().also { open.clear() }
        }
        toClose.forEach(::closeQuietly)
    }

    private fun closeQuietly(resource: Closeable) {
        runCatching { resource.close() }
    }
}
