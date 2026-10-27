package com.anupshrestha.pairloom.debugtools

import com.anupshrestha.pairloom.media.GroupHost
import java.io.Closeable
import java.net.InetAddress

/** The virtual phone exists in debug builds only (src/debug); this build has none. */
object VirtualPhone {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun start(host: GroupHost, local: InetAddress, opus: Boolean, log: (String) -> Unit): Closeable? = null
}
