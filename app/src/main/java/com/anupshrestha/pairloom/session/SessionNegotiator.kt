package com.anupshrestha.pairloom.session

import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.AuthStatus
import com.anupshrestha.pairloom.security.Fingerprints
import com.anupshrestha.pairloom.security.PairedDevice
import com.anupshrestha.pairloom.security.PairedDeviceStore
import com.anupshrestha.pairloom.security.PairingCrypto
import com.anupshrestha.pairloom.security.PeerAuthenticator
import com.anupshrestha.pairloom.security.TrustStoreException
import com.anupshrestha.pairloom.security.hexToBytesOrNull
import com.anupshrestha.pairloom.security.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom

/** A session could not be secured; [kind] says why, for the UI. */
class SecurityFailure(val kind: FailureKind, detail: String, cause: Throwable? = null) : IOException(detail, cause)

/** Who is on the other end of an established, authenticated session. */
data class SessionPeer(
    val name: String,
    val fingerprintHex: String,
    val installIdHex: String,
    val newlyPaired: Boolean,
)

/** Shows the pairing code to the user. Implemented by the view model; faked in tests. */
interface PairingPrompt {
    /** Shows [code] for [peerName] and returns true if the user says both phones show it. Cancellable. */
    suspend fun confirm(peerName: String, code: String): Boolean

    /** The local user said the codes match; the other phone has not answered yet. */
    suspend fun waitingForPeer()
}

/**
 * Runs the session start inside an established mutual TLS channel (see
 * docs/security/phase2-secure-session.md): HELLO, STATUS, and, if either phone does not yet know
 * the other, numeric-comparison pairing. Returns only for an authenticated peer; everything else
 * is a [SecurityFailure], [ControlProtocol.ProtocolException] or [IOException], and nothing is
 * stored unless both users confirmed the code.
 *
 * Blocking reads happen on the caller's thread, which must be a background thread.
 */
class SessionNegotiator(
    private val ownFingerprint: ByteArray,
    private val ownInstallIdHex: String,
    private val localName: String,
    private val store: PairedDeviceStore,
    private val log: (String) -> Unit,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val messageTimeoutMs: Int = 10_000,
    private val confirmTimeoutMs: Long = 120_000,
) {
    /** With [allowPairing] false, only an already paired phone gets through (the group host's later members). */
    suspend fun negotiate(
        channel: ControlChannel,
        isServer: Boolean,
        peerFingerprint: ByteArray,
        prompt: PairingPrompt,
        allowPairing: Boolean = true,
    ): SessionPeer {
        val peerFpHex = peerFingerprint.toHex()
        val ownHello = Message.Hello(ControlProtocol.VERSION, ownInstallIdHex, localName)
        // The server speaks first: in TLS 1.3 the client must not send before the server accepted it.
        val peerHello = if (isServer) {
            channel.sendMessage(ownHello)
            channel.expect<Message.Hello>(messageTimeoutMs)
        } else {
            channel.expect<Message.Hello>(messageTimeoutMs).also { channel.sendMessage(ownHello) }
        }
        val paired = try {
            store.load()
        } catch (e: TrustStoreException) {
            throw SecurityFailure(FailureKind.TRUST_STORE_ERROR, e.message ?: "paired-device store unreadable", e)
        }
        val decision = PeerAuthenticator.decide(ownFingerprint.toHex(), peerFpHex, peerHello.installIdHex, ownInstallIdHex, paired)
        log("Peer ${peerHello.deviceName} key ${Fingerprints.short(peerFpHex)}: ${decision.status}${decision.detail?.let { " ($it)" }.orEmpty()}")

        val own = Message.Status(decision.status.code)
        val peerStatusMessage = if (isServer) {
            channel.sendMessage(own)
            channel.expect<Message.Status>(messageTimeoutMs)
        } else {
            channel.expect<Message.Status>(messageTimeoutMs).also { channel.sendMessage(own) }
        }
        val peerStatus = AuthStatus.fromCode(peerStatusMessage.code)
            ?: throw ControlProtocol.ProtocolException("unknown STATUS ${peerStatusMessage.code}")

        if (decision.status == AuthStatus.CONFLICT) {
            throw SecurityFailure(FailureKind.IDENTITY_CHANGED, decision.detail ?: "identity conflict")
        }
        if (peerStatus == AuthStatus.CONFLICT) {
            throw SecurityFailure(FailureKind.IDENTITY_CHANGED, "the other phone says this phone's identity does not match the one it paired with")
        }
        if (decision.status == AuthStatus.AUTHENTICATED && peerStatus == AuthStatus.AUTHENTICATED) {
            return SessionPeer(peerHello.deviceName, peerFpHex, peerHello.installIdHex, newlyPaired = false)
        }

        if (!allowPairing) {
            throw SecurityFailure(FailureKind.PAIRING_REJECTED, "${peerHello.deviceName} is not paired with this phone yet")
        }
        log("Pairing: comparing codes with ${peerHello.deviceName}")
        val code = pairingCode(channel, isServer, peerFingerprint)
        confirmBoth(channel, prompt, peerHello.deviceName, code)
        try {
            store.add(PairedDevice(peerFpHex, peerHello.installIdHex, peerHello.deviceName, clock()))
        } catch (e: IOException) {
            throw SecurityFailure(FailureKind.TRUST_STORE_ERROR, "could not save the paired phone: ${e.message}", e)
        }
        log("Paired with ${peerHello.deviceName}")
        return SessionPeer(peerHello.deviceName, peerFpHex, peerHello.installIdHex, newlyPaired = true)
    }

    /** Commitment exchange; the server (group owner) is the responder that commits first. */
    private fun pairingCode(channel: ControlChannel, isServer: Boolean, peerFingerprint: ByteArray): String {
        val serverFp = if (isServer) ownFingerprint else peerFingerprint
        val clientFp = if (isServer) peerFingerprint else ownFingerprint
        return if (isServer) {
            val serverNonce = PairingCrypto.newNonce(random)
            channel.sendMessage(Message.Commit(PairingCrypto.commitment(serverFp, clientFp, serverNonce).toHex()))
            val clientNonce = bytes(channel.expect<Message.Nonce>(messageTimeoutMs).valueHex)
            channel.sendMessage(Message.Reveal(serverNonce.toHex()))
            PairingCrypto.code(serverFp, clientFp, serverNonce, clientNonce)
        } else {
            val commitment = bytes(channel.expect<Message.Commit>(messageTimeoutMs).valueHex)
            val clientNonce = PairingCrypto.newNonce(random)
            channel.sendMessage(Message.Nonce(clientNonce.toHex()))
            val serverNonce = bytes(channel.expect<Message.Reveal>(messageTimeoutMs).valueHex)
            if (!PairingCrypto.commitmentMatches(commitment, serverFp, clientFp, serverNonce)) {
                throw SecurityFailure(FailureKind.PAIRING_FAILED, "the other phone's pairing commitment did not match")
            }
            PairingCrypto.code(serverFp, clientFp, serverNonce, clientNonce)
        }
    }

    /**
     * Both users must say the codes match. The peer's answer is read while the local prompt is
     * showing, so a "no" from either phone ends pairing at once. On any failure the channel is
     * closed first, which unblocks the pending read.
     */
    private suspend fun confirmBoth(channel: ControlChannel, prompt: PairingPrompt, peerName: String, code: String) = coroutineScope {
        val peerAnswer = async(Dispatchers.IO) { channel.expect<Message.Confirm>(confirmTimeoutMs.toInt() + 5_000).accepted }
        val localAnswer = async { withTimeoutOrNull(confirmTimeoutMs) { prompt.confirm(peerName, PairingCrypto.display(code)) } }
        try {
            val localFirst = select {
                localAnswer.onAwait { true }
                peerAnswer.onAwait { false }
            }
            if (!localFirst && !peerAnswer.await()) {
                localAnswer.cancel()
                throw SecurityFailure(FailureKind.PAIRING_REJECTED, "the other phone said the codes do not match")
            }
            when (localAnswer.await()) {
                null -> {
                    runCatching { channel.sendMessage(Message.Confirm(false)) }
                    throw SecurityFailure(FailureKind.PAIRING_TIMEOUT, "the codes were not confirmed in time")
                }
                false -> {
                    runCatching { channel.sendMessage(Message.Confirm(false)) }
                    throw SecurityFailure(FailureKind.PAIRING_REJECTED, "you said the codes do not match")
                }
                true -> channel.sendMessage(Message.Confirm(true))
            }
            if (!peerAnswer.isCompleted) prompt.waitingForPeer()
            if (!peerAnswer.await()) throw SecurityFailure(FailureKind.PAIRING_REJECTED, "the other phone said the codes do not match")
        } catch (e: Throwable) {
            channel.close(sendBye = false)
            localAnswer.cancel()
            throw e
        }
    }

    private fun bytes(hex: String): ByteArray =
        hexToBytesOrNull(hex, PairingCrypto.NONCE_BYTES) ?: throw ControlProtocol.ProtocolException("bad pairing value")
}
