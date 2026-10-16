package com.anupshrestha.pairloom.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** A phone the user paired with: its key fingerprint, install ID, name and when it was paired. */
data class PairedDevice(
    val fingerprintHex: String,
    val installIdHex: String,
    val name: String,
    val pairedAtMs: Long,
)

/** The paired-device file exists but cannot be trusted; sessions must be refused. */
class TrustStoreException(message: String) : IOException(message)

/**
 * The paired phones, kept in one versioned text file that is replaced atomically on every change.
 * Pure java.io, so it is unit-testable on the JVM. The app keeps the file in `noBackupFilesDir`.
 *
 * Format: a `pairloom-paired-v1` header line, then one tab-separated line per phone:
 * fingerprint hex, install ID hex, URL-safe base64 of the UTF-8 name, paired-at epoch ms.
 * Anything else makes [load] fail closed with [TrustStoreException].
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
        const val HEADER = "pairloom-paired-v1"

        fun format(devices: List<PairedDevice>): String = buildString {
            append(HEADER).append('\n')
            for (d in devices) {
                val name = Base64.getUrlEncoder().withoutPadding().encodeToString(d.name.toByteArray(Charsets.UTF_8))
                append(d.fingerprintHex).append('\t').append(d.installIdHex).append('\t')
                    .append(name).append('\t').append(d.pairedAtMs).append('\n')
            }
        }

        fun parse(text: String): List<PairedDevice> {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != HEADER) throw TrustStoreException("unknown paired-device file format")
            val devices = lines.drop(1).mapIndexed { i, line ->
                val parts = line.split('\t')
                if (parts.size != 4) throw TrustStoreException("paired-device line ${i + 1} is malformed")
                val (fp, id, name64, at) = parts
                if (hexToBytesOrNull(fp, Fingerprints.BYTES) == null) throw TrustStoreException("bad fingerprint on line ${i + 1}")
                if (hexToBytesOrNull(id, InstallId.BYTES) == null) throw TrustStoreException("bad install ID on line ${i + 1}")
                val name = try {
                    String(Base64.getUrlDecoder().decode(name64), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    throw TrustStoreException("bad name on line ${i + 1}")
                }
                val pairedAt = at.toLongOrNull() ?: throw TrustStoreException("bad time on line ${i + 1}")
                PairedDevice(fp.lowercase(), id.lowercase(), name, pairedAt)
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
