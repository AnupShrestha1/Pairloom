package com.anupshrestha.pairloom.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * A random 16-byte identifier for this install, created once and kept in `noBackupFilesDir`.
 * It lets a phone notice that a paired phone now presents a different key (a hard stop) instead
 * of treating it as a new phone. It is not secret and proves nothing by itself.
 */
object InstallId {
    const val BYTES = 16

    fun getOrCreate(file: File, random: SecureRandom = SecureRandom()): String {
        if (file.exists()) {
            val text = file.readText(Charsets.US_ASCII).trim()
            return text.takeIf { hexToBytesOrNull(it, BYTES) != null }?.lowercase()
                ?: throw IOException("the install ID file is corrupt")
        }
        val id = ByteArray(BYTES).also(random::nextBytes).toHex()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(id.toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return id
    }
}
