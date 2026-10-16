package com.anupshrestha.pairloom.media

/**
 * Sliding anti-replay window over packet sequence numbers (in the style of IPsec/DTLS): accepts each
 * sequence number at most once, and nothing older than [size] behind the highest one seen.
 * Call [accept] only for packets that already passed authentication.
 */
class ReplayWindow(private val size: Int = 128) {
    init {
        require(size in 1..1024) { "window size out of range" }
    }

    private val seen = BooleanArray(size)
    private var highest = -1L

    fun accept(sequence: Long): Boolean {
        if (sequence < 0) return false
        if (sequence > highest) {
            val shift = sequence - highest
            if (shift >= size) {
                seen.fill(false)
            } else {
                // Clear the slots that slide into the window.
                for (s in highest + 1..sequence) seen[(s % size).toInt()] = false
            }
            highest = sequence
            seen[(sequence % size).toInt()] = true
            return true
        }
        if (highest - sequence >= size) return false
        val slot = (sequence % size).toInt()
        if (seen[slot]) return false
        seen[slot] = true
        return true
    }
}
