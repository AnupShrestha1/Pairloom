package com.anupshrestha.pairloom.media

import java.nio.ByteBuffer

/** What a media packet carries. */
enum class PacketKind(val code: Int) {
    /** One audio frame. */
    AUDIO(0),

    /** A round-trip probe; the peer echoes its payload back as [PROBE_REPLY]. */
    PROBE(1),
    PROBE_REPLY(2),
    ;

    companion object {
        fun fromCode(code: Int): PacketKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Media packet header (14 bytes, authenticated but not encrypted):
 * `version (1) | kind (1) | sequence (8) | media timestamp in samples (4)`.
 *
 * The sequence number counts every packet in one direction and is part of the AES-GCM nonce, so it
 * never repeats under one key. The timestamp is the audio position of the frame (for AUDIO).
 */
data class MediaHeader(val kind: PacketKind, val sequence: Long, val timestamp: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(VERSION.toByte())
        .put(kind.code.toByte())
        .putLong(sequence)
        .putInt(timestamp)
        .array()

    companion object {
        const val VERSION = 1
        const val SIZE = 14

        /** Parses the header at the start of [packet], or returns null if it is not a version-1 header. */
        fun decode(packet: ByteArray, length: Int = packet.size): MediaHeader? {
            if (length < SIZE) return null
            val b = ByteBuffer.wrap(packet, 0, SIZE)
            if (b.get().toInt() and 0xFF != VERSION) return null
            val kind = PacketKind.fromCode(b.get().toInt() and 0xFF) ?: return null
            val sequence = b.long
            if (sequence < 0) return null
            return MediaHeader(kind, sequence, b.int)
        }
    }
}
