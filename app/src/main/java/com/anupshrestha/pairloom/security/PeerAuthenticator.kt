package com.anupshrestha.pairloom.security

/** How this phone sees the peer right after the TLS handshake. Sent to the peer as STATUS. */
enum class AuthStatus(val code: Int) {
    AUTHENTICATED(1),
    NEEDS_PAIRING(2),
    CONFLICT(3),
    ;

    companion object {
        fun fromCode(code: Int): AuthStatus? = entries.firstOrNull { it.code == code }
    }
}

data class PeerDecision(val status: AuthStatus, val record: PairedDevice?, val detail: String?)

/**
 * Classifies the peer by the key it proved in TLS and the install ID it announced. Pure, so the
 * hard-stop rules are unit-tested directly.
 */
object PeerAuthenticator {
    fun decide(
        ownFingerprintHex: String,
        peerFingerprintHex: String,
        peerInstallIdHex: String,
        ownInstallIdHex: String,
        paired: List<PairedDevice>,
    ): PeerDecision {
        if (peerFingerprintHex == ownFingerprintHex) {
            return PeerDecision(AuthStatus.CONFLICT, null, "the other phone presented this phone's own key")
        }
        if (peerInstallIdHex == ownInstallIdHex) {
            return PeerDecision(AuthStatus.CONFLICT, null, "the other phone presented this phone's own install ID")
        }
        val byKey = paired.firstOrNull { it.fingerprintHex == peerFingerprintHex }
        val byId = paired.firstOrNull { it.installIdHex == peerInstallIdHex }
        return when {
            byKey != null && byKey.installIdHex == peerInstallIdHex -> PeerDecision(AuthStatus.AUTHENTICATED, byKey, null)
            byKey != null -> PeerDecision(
                AuthStatus.CONFLICT, byKey, "the key of paired phone \"${byKey.name}\" came with a different install ID",
            )
            byId != null -> PeerDecision(
                AuthStatus.CONFLICT, byId, "paired phone \"${byId.name}\" now presents a different key",
            )
            else -> PeerDecision(AuthStatus.NEEDS_PAIRING, null, null)
        }
    }
}
