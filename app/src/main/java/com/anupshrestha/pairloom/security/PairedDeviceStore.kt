package com.anupshrestha.pairloom.security

import com.anupshrestha.pairloom.p2p.GroupCredentials
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * A phone the user paired with: its key fingerprint, install ID, name, when it was paired and,
 * once it has sent them, its Wi-Fi Direct group name and passphrase (for joining it directly).
 */
data class PairedDevice(
    val fingerprintHex: String,
    val installIdHex: String,
    val name: String,
    val pairedAtMs: Long,
    val groupName: String? = null,
    val groupPassphrase: String? = null,
    /** Its normal Wi-Fi channel (MHz) on the last link, 0 if unknown or not connected. */
    val staFrequencyMhz: Int = 0,
) {
    override fun toString(): String =
        "PairedDevice(fingerprintHex=$fingerprintHex, installIdHex=$installIdHex, name=$name, pairedAtMs=$pairedAtMs, " +
            "groupName=$groupName, groupPassphrase=${if (groupPassphrase == null) "null" else "<hidden>"}, staFrequencyMhz=$staFrequencyMhz)"
}

/** The paired-device file exists but cannot be trusted; sessions must be refused. */
class TrustStoreException(message: String) : IOException(message)

/**
 * The paired phones, kept in one versioned text file that is replaced atomically on every change.
 * Pure java.io, so it is unit-testable on the JVM. The app keeps the file in `noBackupFilesDir`.
 *
 * Format: a `pairloom-paired-v3` header line, then one tab-separated line per phone:
 * fingerprint hex, install ID hex, URL-safe base64 of the UTF-8 name, paired-at epoch ms, group
 * name and group passphrase (both `-` until known), and its Wi-Fi channel in MHz on the last link
 * (0 if unknown). Version 1 (first four fields) and version 2 (first six) files are still read. Anything else makes [load] fail closed with [TrustStoreException].
 */
class PairedDeviceStore(private val file: File) {
    private val lock = Any()

    fun load(): List<PairedDevice> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        parse(file.readText(Charsets.UTF_8))
    }

    /** Adds [device], replacing any record with the same fingerprint or install ID. */
    fun add(device: PairedDevice) = synchronized(lock) {
        val others = load().filter { it.fingerprintHex != device.fingerprintHex && it.installIdHex != device.installIdHex }
        write(others + device)
    }

    /** Stores the group of the paired phone with [fingerprintHex]; returns false if it is not paired. */
    fun updateGroup(fingerprintHex: String, groupName: String, passphrase: String, staFrequencyMhz: Int = 0): Boolean = synchronized(lock) {
        val all = load()
        val current = all.firstOrNull { it.fingerprintHex == fingerprintHex } ?: return false
        val updated = current.copy(groupName = groupName, groupPassphrase = passphrase, staFrequencyMhz = staFrequencyMhz.coerceIn(0, 65535))
        if (updated == current) return true
        write(all.map { if (it === current) updated else it })
        true
    }

    /** Removes the phone with [fingerprintHex]; returns false if it was not stored. */
    fun remove(fingerprintHex: String): Boolean = synchronized(lock) {
        val all = load()
        val kept = all.filter { it.fingerprintHex != fingerprintHex }
        if (kept.size == all.size) return false
        write(kept)
        true
    }

    private fun write(devices: List<PairedDevice>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(format(devices).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("could not replace ${file.name}: ${e.message}", e)
        }
    }

    companion object {
        const val HEADER = "pairloom-paired-v3"
        const val HEADER_V2 = "pairloom-paired-v2"
        const val HEADER_V1 = "pairloom-paired-v1"
        private const val NONE = "-"

        fun format(devices: List<PairedDevice>): String = buildString {
            append(HEADER).append('\n')
            for (d in devices) {
                val name = Base64.getUrlEncoder().withoutPadding().encodeToString(d.name.toByteArray(Charsets.UTF_8))
                val hasGroup = d.groupName != null && d.groupPassphrase != null
                append(d.fingerprintHex).append('\t').append(d.installIdHex).append('\t')
                    .append(name).append('\t').append(d.pairedAtMs).append('\t')
                    .append(if (hasGroup) d.groupName else NONE).append('\t')
                    .append(if (hasGroup) d.groupPassphrase else NONE).append('\t')
                    .append(d.staFrequencyMhz).append('\n')
            }
        }

        fun parse(text: String): List<PairedDevice> {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            val fields = when (lines.firstOrNull()) {
                HEADER -> 7
                HEADER_V2 -> 6
                HEADER_V1 -> 4
                else -> throw TrustStoreException("unknown paired-device file format")
            }
            val devices = lines.drop(1).mapIndexed { i, line ->
                val parts = line.split('\t')
                if (parts.size != fields) throw TrustStoreException("paired-device line ${i + 1} is malformed")
                val (fp, id, name64, at) = parts
                if (hexToBytesOrNull(fp, Fingerprints.BYTES) == null) throw TrustStoreException("bad fingerprint on line ${i + 1}")
                if (hexToBytesOrNull(id, InstallId.BYTES) == null) throw TrustStoreException("bad install ID on line ${i + 1}")
                val name = try {
                    String(Base64.getUrlDecoder().decode(name64), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    throw TrustStoreException("bad name on line ${i + 1}")
                }
                val pairedAt = at.toLongOrNull() ?: throw TrustStoreException("bad time on line ${i + 1}")
                var groupName: String? = null
                var groupPass: String? = null
                if (fields >= 6 && !(parts[4] == NONE && parts[5] == NONE)) {
                    if (!GroupCredentials.isValid(parts[4], parts[5])) throw TrustStoreException("bad group on line ${i + 1}")
                    groupName = parts[4]
                    groupPass = parts[5]
                }
                val frequency = if (fields == 7) {
                    parts[6].toIntOrNull()?.takeIf { it in 0..65535 } ?: throw TrustStoreException("bad frequency on line ${i + 1}")
                } else {
                    0
                }
                PairedDevice(fp.lowercase(), id.lowercase(), name, pairedAt, groupName, groupPass, frequency)
            }
            if (devices.map { it.fingerprintHex }.toSet().size != devices.size ||
                devices.map { it.installIdHex }.toSet().size != devices.size
            ) {
                throw TrustStoreException("duplicate paired-device entries")
            }
            return devices
        }
    }
}
