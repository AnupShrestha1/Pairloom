package com.anupshrestha.pairloom.p2p

import java.security.SecureRandom
import java.util.Random

/**
 * Random, single-use credentials for a host's non-persistent Wi-Fi Direct group.
 *
 * They exist only for the duration of one createGroup call. The joining phone never needs them:
 * Android hands them over in its own Wi-Fi Direct handshake once the host accepts.
 * [toString] hides the passphrase so it cannot end up in a log by accident.
 */
class GroupCredentials(val networkName: String, val passphrase: String) {
    override fun toString(): String = "GroupCredentials(networkName=$networkName, passphrase=<hidden>)"

    companion object {
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** 16 characters from 62 symbols, about 95 bits. Within Wi-Fi's 8 to 63 character limit. */
        const val PASSPHRASE_LENGTH = 16

        /**
         * Android requires group names to start with "DIRECT-" followed by two letters or digits,
         * and to fit in 32 bytes.
         */
        fun generate(random: Random = SecureRandom()): GroupCredentials {
            val networkName = "DIRECT-" + randomString(random, 2) + "-Pairloom"
            return GroupCredentials(networkName, randomString(random, PASSPHRASE_LENGTH))
        }

        private fun randomString(random: Random, length: Int): String =
            buildString(length) { repeat(length) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) } }
    }
}
