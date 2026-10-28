package com.anupshrestha.pairloom.session

import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.JoinCode
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Joining a group with its code (Phase 7, G6): J-PAKE (RFC 8236, Bouncy Castle's implementation,
 * over the 2048-bit NIST group) inside the mutual TLS link, after the TLS handshake. Both phones
 * prove they hold the same code without showing it, and a wrong guess teaches an attacker nothing
 * to test other codes with offline. Round 3 confirms the key, so both sides know the outcome.
 *
 * The secret is the code together with both phones' TLS key fingerprints, which binds the
 * handshake to this TLS link: someone in the middle, with TLS keys of its own, computes a
 * different secret and fails.
 *
 * The host speaks first, as in the rest of the session set-up. Blocking reads; run it off the
 * main thread. Returns 32 bytes both phones now share; throws [SecurityFailure] with
 * [FailureKind.JOIN_CODE_REJECTED] if the codes differ.
 */
class CodeJoin(
    private val code: JoinCode,
    private val ownFingerprintHex: String,
    private val peerFingerprintHex: String,
    private val random: SecureRandom = SecureRandom(),
    private val messageTimeoutMs: Int = 10_000,
) {
    fun run(channel: ControlChannel, isHost: Boolean): ByteArray {
        val hostFp = (if (isHost) ownFingerprintHex else peerFingerprintHex).lowercase()
        val memberFp = (if (isHost) peerFingerprintHex else ownFingerprintHex).lowercase()
        val ownId = if (isHost) HOST else MEMBER
        val peerId = if (isHost) MEMBER else HOST
        val secret = "${code.text}|$hostFp|$memberFp".toCharArray()
        val me = JPAKEParticipant(ownId, secret, JPAKEPrimeOrderGroups.NIST_2048, SHA256Digest(), random)
        secret.fill('\u0000')
        try {
            // Each round: the host sends first, the member answers, then both check.
            fun exchange(round: Int, own: List<BigInteger>): List<BigInteger> = if (isHost) {
                channel.sendMessage(Message.CodePake(round, own))
                receive(channel, round)
            } else {
                receive(channel, round).also { channel.sendMessage(Message.CodePake(round, own)) }
            }

            val r1 = me.createRound1PayloadToSend()
            val p1 = exchange(1, listOf(r1.gx1, r1.gx2) + r1.knowledgeProofForX1 + r1.knowledgeProofForX2)
            if (p1.size != 6) throw rejected("round 1 has ${p1.size} values")
            me.validateRound1PayloadReceived(JPAKERound1Payload(peerId, p1[0], p1[1], arrayOf(p1[2], p1[3]), arrayOf(p1[4], p1[5])))

            val r2 = me.createRound2PayloadToSend()
            val p2 = exchange(2, listOf(r2.a) + r2.knowledgeProofForX2s)
            if (p2.size != 3) throw rejected("round 2 has ${p2.size} values")
            me.validateRound2PayloadReceived(JPAKERound2Payload(peerId, p2[0], arrayOf(p2[1], p2[2])))

            val keying = me.calculateKeyingMaterial()
            val p3 = exchange(3, listOf(me.createRound3PayloadToSend(keying).macTag))
            if (p3.size != 1) throw rejected("round 3 has ${p3.size} values")
            me.validateRound3PayloadReceived(JPAKERound3Payload(peerId, p3[0]), keying)
            return MessageDigest.getInstance("SHA-256").digest("pairloom-code-v1".toByteArray() + keying.toByteArray())
        } catch (e: CryptoException) {
            // A wrong code shows up here, at the latest in round 3's key confirmation.
            throw rejected(e.message ?: "J-PAKE failed", e)
        } catch (e: IllegalStateException) {
            throw rejected(e.message ?: "J-PAKE failed", e)
        }
    }

    private fun receive(channel: ControlChannel, round: Int): List<BigInteger> {
        val m = channel.expect<Message.CodePake>(messageTimeoutMs)
        if (m.round != round) throw ControlProtocol.ProtocolException("expected J-PAKE round $round, got ${m.round}")
        return m.values
    }

    private fun rejected(detail: String, cause: Throwable? = null) =
        SecurityFailure(FailureKind.JOIN_CODE_REJECTED, "the join codes differ ($detail)", cause)

    companion object {
        private const val HOST = "pairloom-code-host"
        private const val MEMBER = "pairloom-code-member"
    }
}

/**
 * The host's brake on guessing: after [maxFailures] wrong codes within [windowMs], further joins
 * are refused until the window has passed. With about 16.7 million codes, a few guesses a minute
 * get an attacker nowhere while the group lasts. Thread-safe.
 */
class CodeGuard(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = ArrayDeque<Long>()

    @Synchronized
    fun mayTry(): Boolean {
        prune()
        return failures.size < maxFailures
    }

    @Synchronized
    fun failed() {
        failures.addLast(clock())
        prune()
    }

    private fun prune() {
        val now = clock()
        while (failures.isNotEmpty() && now - failures.first() >= windowMs) failures.removeFirst()
    }
}
