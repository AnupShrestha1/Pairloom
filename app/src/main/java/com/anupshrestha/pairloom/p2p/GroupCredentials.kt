package com.anupshrestha.pairloom.p2p

import java.security.SecureRandom
import java.util.Random

/**
 * Credentials for this phone's Wi-Fi Direct group when it hosts.
 *
 * They are random, kept in app-private storage that is never backed up ([GroupCredentialStore]),
 * and given only to authenticated, paired phones, inside the TLS link, so that those phones can
 * join directly without the system's connection prompt. Any other phone still joins by asking, and
 * the host's user accepts. They are replaced when a paired phone is forgotten. The group itself
 * stays non-persistent. [toString] hides the passphrase so it cannot end up in a log by accident.
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

        private val NAME = Regex("DIRECT-[A-Za-z0-9]{2}-Pairloom")
        private val PASSPHRASE = Regex("[A-Za-z0-9]{8,63}")

        /** Only groups in Pairloom's own format are accepted from the other phone. */
        fun isValid(networkName: String, passphrase: String): Boolean =
            NAME.matches(networkName) && PASSPHRASE.matches(passphrase)

        private fun randomString(random: Random, length: Int): String =
            buildString(length) { repeat(length) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) } }
    }
}
