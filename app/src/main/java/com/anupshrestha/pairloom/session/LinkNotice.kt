package com.anupshrestha.pairloom.session

import com.anupshrestha.pairloom.media.MediaKind

/** What the ongoing notification shows; null when there is no link. */
sealed interface LinkNotice {
    val peer: String

    data class Linked(override val peer: String) : LinkNotice
    data class InCall(override val peer: String, val muted: Boolean) : LinkNotice
    data class PushToTalk(override val peer: String) : LinkNotice

    /** The other phone calls (or asks for push-to-talk) and this phone's user has to accept. */
    data class Incoming(override val peer: String, val pushToTalk: Boolean) : LinkNotice

    /** The link dropped and the phones are trying to get it back. */
    data class Reconnecting(override val peer: String) : LinkNotice

    /** Whether the microphone is in use (the foreground service needs the microphone type then). */
    val usesMicrophone: Boolean get() = this is InCall || this is PushToTalk
}

/**
 * What the ongoing notification shows for [state], or null without a link (the foreground service
 * then stops). [visible] is whether Pairloom is on screen: an incoming call rings as a
 * notification only when it is not. While reconnecting after a drop the notice stays up, which
 * keeps the service (and so the process) running between attempts.
 */
fun linkNotice(state: LinkUiState, visible: Boolean): LinkNotice? {
    val reconnecting = state.reconnectingTo
    if (reconnecting != null && state.link !is LinkState.Linked) return LinkNotice.Reconnecting(reconnecting)
    val l = state.link as? LinkState.Linked ?: return null
    val d = l.details
    return when {
        d.incomingCall && !visible -> LinkNotice.Incoming(d.peerName, pushToTalk = d.incomingKind == MediaKind.PTT)
        d.mediaActive && d.mediaKind == MediaKind.VOICE -> LinkNotice.InCall(d.peerName, d.muted)
        d.mediaActive && d.mediaKind == MediaKind.PTT -> LinkNotice.PushToTalk(d.peerName)
        else -> LinkNotice.Linked(d.peerName)
    }
}

/**
 * Connect's roles for two paired phones: the one with the lower key fingerprint hosts. Both phones
 * know both fingerprints, so they agree without a message.
 */
fun hostsFor(ownFingerprintHex: String, peerFingerprintHex: String): Boolean =
    ownFingerprintHex.lowercase() < peerFingerprintHex.lowercase()
