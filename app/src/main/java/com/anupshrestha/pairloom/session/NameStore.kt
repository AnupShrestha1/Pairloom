package com.anupshrestha.pairloom.session

import android.content.Context
import android.os.Build

/**
 * Names people choose: this phone's name (sent to the other phone when linking, instead of the
 * model code) and local nicknames for paired phones ("Asha's phone"), which win over whatever name
 * the other phone sends. App-private and never backed up, like the rest of the app's data.
 */
class NameStore(context: Context) {
    private val prefs = context.getSharedPreferences("names", Context.MODE_PRIVATE)

    /** This phone's name as others see it; the model until the user picks one. */
    var ownName: String
        get() = prefs.getString(KEY_OWN, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        set(value) {
            prefs.edit().putString(KEY_OWN, clean(value)).apply()
        }

    fun nickname(fingerprintHex: String): String? = prefs.getString(KEY_NICK + fingerprintHex.lowercase(), null)?.takeIf { it.isNotBlank() }

    /** Sets a nickname, or removes it when [name] is blank. */
    fun setNickname(fingerprintHex: String, name: String?) {
        val key = KEY_NICK + fingerprintHex.lowercase()
        val value = name?.let(::clean)
        prefs.edit().apply { if (value.isNullOrEmpty()) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        private const val KEY_OWN = "own"
        private const val KEY_NICK = "nick:"

        /** Names are one line, trimmed and kept short (the link protocol sends at most 64 bytes). */
        const val MAX_CHARS = 32

        fun clean(name: String): String = name.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
    }
}
