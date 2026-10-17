package com.anupshrestha.pairloom.p2p

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * This phone's own group credentials ([GroupCredentials]), kept in one small file that is replaced
 * atomically. Created on first use; [rotate] replaces them (after a paired phone is forgotten, so
 * that it can no longer join directly). A damaged file is replaced with new credentials: the only
 * cost is that paired phones ask once more, through the system prompt, until the next link.
 */
class GroupCredentialStore(private val file: File) {
    private val lock = Any()

    fun get(): GroupCredentials = synchronized(lock) {
        read() ?: GroupCredentials.generate().also { write(it) }
    }

    fun rotate(): GroupCredentials = synchronized(lock) {
        GroupCredentials.generate().also { write(it) }
    }

    private fun read(): GroupCredentials? {
        if (!file.exists()) return null
        val lines = runCatching { file.readText(Charsets.US_ASCII).split('\n') }.getOrNull() ?: return null
        if (lines.size < 3 || lines[0] != HEADER) return null
        return lines[1].takeIf { GroupCredentials.isValid(lines[1], lines[2]) }?.let { GroupCredentials(lines[1], lines[2]) }
    }

    private fun write(c: GroupCredentials) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write("$HEADER\n${c.networkName}\n${c.passphrase}\n".toByteArray(Charsets.US_ASCII))
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
        const val HEADER = "pairloom-group-v1"
    }
}
