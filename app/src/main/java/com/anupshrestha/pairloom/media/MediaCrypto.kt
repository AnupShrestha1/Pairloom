package com.anupshrestha.pairloom.media

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keys for one sending direction: a random 256-bit AES key and a random 4-byte salt, created by the
 * sender for each call and given to the receiver inside the TLS control channel.
 */
class DirectionKey(val key: ByteArray, val salt: ByteArray) {
    init {
        require(key.size == KEY_BYTES) { "media key must be $KEY_BYTES bytes" }
        require(salt.size == SALT_BYTES) { "media salt must be $SALT_BYTES bytes" }
    }

    companion object {
        const val KEY_BYTES = 32
        const val SALT_BYTES = 4

        fun random(random: SecureRandom = SecureRandom()) =
            DirectionKey(ByteArray(KEY_BYTES).also(random::nextBytes), ByteArray(SALT_BYTES).also(random::nextBytes))
    }
}

/** A packet that passed authentication. */
class OpenedPacket(val header: MediaHeader, val payload: ByteArray)

/**
 * AES-256-GCM for media packets. Nonce = salt (4) ‖ sequence (8); the 14-byte header is the
 * additional authenticated data, so header and payload are both protected. Not thread-safe: use one
 * instance per thread.
 */
class MediaSealer(private val dk: DirectionKey) {
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val keySpec = SecretKeySpec(dk.key, "AES")

    fun seal(header: MediaHeader, payload: ByteArray): ByteArray {
        val h = header.encode()
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(dk.salt, header.sequence)))
        cipher.updateAAD(h)
        return h + cipher.doFinal(payload)
    }

    companion object {
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val OVERHEAD = MediaHeader.SIZE + TAG_BYTES

        fun nonce(salt: ByteArray, sequence: Long): ByteArray = ByteBuffer.allocate(12).put(salt).putLong(sequence).array()
    }
}

class MediaOpener(private val dk: DirectionKey) {
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val keySpec = SecretKeySpec(dk.key, "AES")

    /** Returns the packet if it is well formed and authentic, otherwise null. */
    fun open(packet: ByteArray, length: Int = packet.size): OpenedPacket? {
        if (length < MediaSealer.OVERHEAD) return null
        val header = MediaHeader.decode(packet, length) ?: return null
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(MediaSealer.TAG_BITS, MediaSealer.nonce(dk.salt, header.sequence)))
            cipher.updateAAD(packet, 0, MediaHeader.SIZE)
            OpenedPacket(header, cipher.doFinal(packet, MediaHeader.SIZE, length - MediaHeader.SIZE))
        } catch (e: AEADBadTagException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}
